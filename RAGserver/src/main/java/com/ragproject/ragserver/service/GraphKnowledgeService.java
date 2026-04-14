package com.ragproject.ragserver.service;

import com.ragproject.ragserver.dto.graph.ExtractedEntity;
import com.ragproject.ragserver.dto.graph.ExtractedRelation;
import com.ragproject.ragserver.dto.graph.GraphExtractionResult;
import com.ragproject.ragserver.mapper.GraphEntityLinkMapper;
import com.ragproject.ragserver.model.GraphEntityLink;
import com.ragproject.ragserver.model.KnowledgeItem;
import com.ragproject.ragserver.model.graph.GraphEntityNode;
import com.ragproject.ragserver.model.graph.GraphEntityRelation;
import com.ragproject.ragserver.repository.GraphEntityNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(prefix = "app.graph", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GraphKnowledgeService {
    private static final Logger log = LoggerFactory.getLogger(GraphKnowledgeService.class);

    private static final Set<String> ALLOWED_ENTITY_TYPES = Set.of("PERSON", "ORGANIZATION", "LOCATION", "CONCEPT", "EVENT");
    private static final Set<String> ALLOWED_RELATION_TYPES = Set.of("RELATED_TO", "MENTIONS", "WORKS_FOR", "LOCATED_IN", "PART_OF");
    private static final Pattern PERSON_PATTERN = Pattern.compile("^[\\p{IsHan}]{2,4}$|^[A-Za-z]+(?:\\s+[A-Za-z]+)+$");
    private static final Pattern ORG_HINT_PATTERN = Pattern.compile("公司|集团|大学|学院|学校|医院|法院|检察院|政府|委员会|研究所|实验室|协会|银行");
    private static final Pattern LOCATION_HINT_PATTERN = Pattern.compile("省|市|区|县|镇|乡|村|路|街|大道|园区|中国|美国|欧洲|亚洲|北京|上海|广州|深圳");
    private static final Pattern EVENT_HINT_PATTERN = Pattern.compile("会议|峰会|发布会|论坛|比赛|战争|事故|活动|展会|运动会|演练");

    private final EntityExtractionService entityExtractionService;
    private final GraphEntityNodeRepository graphEntityNodeRepository;
    private final GraphEntityLinkMapper graphEntityLinkMapper;

    public GraphKnowledgeService(EntityExtractionService entityExtractionService,
                                 GraphEntityNodeRepository graphEntityNodeRepository,
                                 GraphEntityLinkMapper graphEntityLinkMapper) {
        this.entityExtractionService = entityExtractionService;
        this.graphEntityNodeRepository = graphEntityNodeRepository;
        this.graphEntityLinkMapper = graphEntityLinkMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void extractAndPersist(Long documentId, String documentName, KnowledgeItem item, int chunkNo) {
        if (item == null || item.getKnowledgeId() == null || !StringUtils.hasText(item.getAnswer())) {
            return;
        }

        GraphExtractionResult extraction = entityExtractionService.extract(documentName, item.getAnswer());
        if (extraction.getEntities() == null || extraction.getEntities().isEmpty()) {
            return;
        }

        Map<String, GraphEntityNode> entityNodeMap = new HashMap<>();
        List<GraphEntityLink> links = new ArrayList<>();
        Map<String, Integer> typeDistribution = new LinkedHashMap<>();

        for (ExtractedEntity extractedEntity : extraction.getEntities()) {
            String entityName = normalizeText(extractedEntity == null ? null : extractedEntity.getName());
            String entityType = normalizeEnum(extractedEntity == null ? null : extractedEntity.getType());
            entityType = correctEntityType(entityName, entityType);
            if (!StringUtils.hasText(entityName) || !ALLOWED_ENTITY_TYPES.contains(entityType)) {
                continue;
            }
            typeDistribution.merge(entityType, 1, Integer::sum);

            String normalizedName = normalizeName(entityName);
            GraphEntityNode node = getOrCreateNode(documentId, item.getKnowledgeId(), entityName, normalizedName, entityType);
            entityNodeMap.put(buildEntityKey(entityName, entityType), node);

            GraphEntityLink link = new GraphEntityLink();
            link.setDocumentId(documentId);
            link.setKnowledgeId(item.getKnowledgeId());
            link.setGraphNodeId(node.getNodeId());
            link.setEntityName(node.getName());
            link.setEntityType(node.getType());
            link.setSourceChunkNo(chunkNo);
            links.add(link);
        }

        if (!links.isEmpty()) {
            graphEntityLinkMapper.insertBatch(links);
        }

        if (!typeDistribution.isEmpty()) {
            log.info("Graph entity distribution. documentId={}, knowledgeId={}, distribution={}",
                    documentId, item.getKnowledgeId(), typeDistribution);
        }

        if (extraction.getRelations() == null || extraction.getRelations().isEmpty()) {
            return;
        }

        for (ExtractedRelation relation : extraction.getRelations()) {
            String relationType = normalizeEnum(relation == null ? null : relation.getType());
            if (!ALLOWED_RELATION_TYPES.contains(relationType)) {
                continue;
            }

            GraphEntityNode source = locateNode(entityNodeMap, relation == null ? null : relation.getFrom());
            GraphEntityNode target = locateNode(entityNodeMap, relation == null ? null : relation.getTo());
            if (source == null || target == null) {
                continue;
            }

            if (hasRelation(source, target, relationType)) {
                continue;
            }

            GraphEntityRelation edge = new GraphEntityRelation();
            edge.setTarget(target);
            edge.setRelationType(relationType);
            edge.setEvidence(truncate(normalizeText(relation == null ? null : relation.getEvidence()), 500));
            edge.setDocumentId(documentId);
            edge.setKnowledgeId(item.getKnowledgeId());
            source.getRelations().add(edge);
            graphEntityNodeRepository.save(source);
        }

        log.info("Graph extraction persisted. documentId={}, knowledgeId={}, entities={}, relations={}",
                documentId, item.getKnowledgeId(), links.size(), extraction.getRelations().size());
    }

    @Transactional
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        graphEntityNodeRepository.deleteAllByDocumentId(documentId);
        graphEntityLinkMapper.deleteByDocumentId(documentId);
    }

    private GraphEntityNode getOrCreateNode(Long documentId,
                                            Long knowledgeId,
                                            String name,
                                            String normalizedName,
                                            String type) {
        Optional<GraphEntityNode> existing = graphEntityNodeRepository
                .findFirstByNormalizedNameAndTypeAndDocumentId(normalizedName, type, documentId);
        if (existing.isPresent()) {
            return existing.get();
        }

        GraphEntityNode node = new GraphEntityNode();
        node.setDocumentId(documentId);
        node.setKnowledgeId(knowledgeId);
        node.setName(name);
        node.setNormalizedName(normalizedName);
        node.setType(type);
        return graphEntityNodeRepository.save(node);
    }

    private boolean hasRelation(GraphEntityNode source, GraphEntityNode target, String relationType) {
        if (source.getRelations() == null || source.getRelations().isEmpty()) {
            return false;
        }

        for (GraphEntityRelation relation : source.getRelations()) {
            if (relation.getTarget() == null || relation.getTarget().getNodeId() == null) {
                continue;
            }
            if (target.getNodeId() == null) {
                continue;
            }
            if (relationType.equalsIgnoreCase(relation.getRelationType())
                    && target.getNodeId().equals(relation.getTarget().getNodeId())) {
                return true;
            }
        }
        return false;
    }

    private GraphEntityNode locateNode(Map<String, GraphEntityNode> entityNodeMap, String entityName) {
        String normalized = normalizeText(entityName);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }

        for (Map.Entry<String, GraphEntityNode> entry : entityNodeMap.entrySet()) {
            if (entry.getKey().startsWith(normalizeName(normalized) + "#")) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String normalizeEnum(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String correctEntityType(String entityName, String entityType) {
        if (!StringUtils.hasText(entityName)) {
            return entityType;
        }

        String normalizedType = normalizeEnum(entityType);
        if (!"CONCEPT".equals(normalizedType)) {
            return normalizedType;
        }

        if (ORG_HINT_PATTERN.matcher(entityName).find()) {
            return "ORGANIZATION";
        }
        if (LOCATION_HINT_PATTERN.matcher(entityName).find()) {
            return "LOCATION";
        }
        if (EVENT_HINT_PATTERN.matcher(entityName).find()) {
            return "EVENT";
        }
        if (PERSON_PATTERN.matcher(entityName).find()) {
            return "PERSON";
        }
        return normalizedType;
    }

    private String normalizeName(String value) {
        return normalizeText(value).toLowerCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim();
    }

    private String buildEntityKey(String name, String type) {
        return normalizeName(name) + "#" + type;
    }

    private String truncate(String value, int maxLen) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }
}
