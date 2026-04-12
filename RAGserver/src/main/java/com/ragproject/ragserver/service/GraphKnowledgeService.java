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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "app.graph", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GraphKnowledgeService {
    private static final Logger log = LoggerFactory.getLogger(GraphKnowledgeService.class);

    private static final Set<String> ALLOWED_ENTITY_TYPES = Set.of("PERSON", "ORGANIZATION", "LOCATION", "CONCEPT", "EVENT");
    private static final Set<String> ALLOWED_RELATION_TYPES = Set.of("RELATED_TO", "MENTIONS", "WORKS_FOR", "LOCATED_IN", "PART_OF");

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

        for (ExtractedEntity extractedEntity : extraction.getEntities()) {
            String entityName = normalizeText(extractedEntity == null ? null : extractedEntity.getName());
            String entityType = normalizeEnum(extractedEntity == null ? null : extractedEntity.getType());
            if (!StringUtils.hasText(entityName) || !ALLOWED_ENTITY_TYPES.contains(entityType)) {
                continue;
            }

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
