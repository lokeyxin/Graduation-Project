package com.ragproject.ragserver.service;

import com.ragproject.ragserver.mapper.GraphEntityLinkMapper;
import com.ragproject.ragserver.model.GraphEntityLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "app.graph", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GraphRetrievalService {
    private static final Logger log = LoggerFactory.getLogger(GraphRetrievalService.class);

    private final KeywordWindowExtractor keywordWindowExtractor;
    private final Neo4jClient neo4jClient;
    private final GraphEntityLinkMapper graphEntityLinkMapper;

    @Value("${app.graph.retrieval.fulltext-index:entityNameIndex}")
    private String fulltextIndex;

    @Value("${app.graph.retrieval.keyword-window:6}")
    private int keywordWindow;

    @Value("${app.graph.retrieval.max-keywords:20}")
    private int maxKeywords;

    @Value("${app.graph.retrieval.fulltext-top-k:30}")
    private int fulltextTopK;

    @Value("${app.graph.retrieval.expand-depth:2}")
    private int expandDepth;

    @Value("${app.graph.retrieval.max-expand-nodes:200}")
    private int maxExpandNodes;

    public GraphRetrievalService(KeywordWindowExtractor keywordWindowExtractor,
                                 Neo4jClient neo4jClient,
                                 GraphEntityLinkMapper graphEntityLinkMapper) {
        this.keywordWindowExtractor = keywordWindowExtractor;
        this.neo4jClient = neo4jClient;
        this.graphEntityLinkMapper = graphEntityLinkMapper;
    }

    public GraphRetrievalResult retrieve(String question) {
        if (!StringUtils.hasText(question)) {
            return GraphRetrievalResult.empty();
        }

        List<String> keywords = keywordWindowExtractor.extract(question, keywordWindow, maxKeywords);
        if (keywords.isEmpty()) {
            return GraphRetrievalResult.empty();
        }

        Map<Long, Double> seedNodeScoreMap = new HashMap<>();
        for (String keyword : keywords) {
            querySeedNodes(keyword, Math.max(fulltextTopK, 1), seedNodeScoreMap);
        }

        if (seedNodeScoreMap.isEmpty()) {
            return new GraphRetrievalResult(keywords, List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        Set<Long> allNodeIds = new LinkedHashSet<>(seedNodeScoreMap.keySet());
        Map<Long, Double> nodeScoreMap = new HashMap<>(seedNodeScoreMap);
        expandNeighbors(seedNodeScoreMap.keySet(), allNodeIds, nodeScoreMap);

        List<Long> nodeIds = new ArrayList<>(allNodeIds);
        if (nodeIds.isEmpty()) {
            return new GraphRetrievalResult(keywords, List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        List<GraphEntityLink> links = graphEntityLinkMapper.findByGraphNodeIds(nodeIds);
        if (links == null || links.isEmpty()) {
            return new GraphRetrievalResult(
                    keywords,
                    sortByScore(seedNodeScoreMap),
                    nodeIds,
                    List.of(),
                    List.of(),
                    Map.of());
        }

        Set<Long> knowledgeIdSet = new LinkedHashSet<>();
        Set<Long> documentIdSet = new LinkedHashSet<>();
        Map<Long, Double> knowledgeScoreMap = new HashMap<>();

        for (GraphEntityLink link : links) {
            Long knowledgeId = link.getKnowledgeId();
            Long documentId = link.getDocumentId();
            Long graphNodeId = link.getGraphNodeId();
            if (knowledgeId == null || graphNodeId == null) {
                continue;
            }

            knowledgeIdSet.add(knowledgeId);
            if (documentId != null) {
                documentIdSet.add(documentId);
            }

            double nodeScore = nodeScoreMap.getOrDefault(graphNodeId, 0.2d);
            knowledgeScoreMap.merge(knowledgeId, nodeScore, Math::max);
        }

        return new GraphRetrievalResult(
                keywords,
                sortByScore(seedNodeScoreMap),
                nodeIds,
                new ArrayList<>(knowledgeIdSet),
                new ArrayList<>(documentIdSet),
                knowledgeScoreMap);
    }

    private void querySeedNodes(String keyword, int limit, Map<Long, Double> seedNodeScoreMap) {
        String cypher = """
                CALL db.index.fulltext.queryNodes($indexName, $keyword) YIELD node, score
                WHERE node:Entity
                RETURN id(node) AS nodeId, score AS score
                ORDER BY score DESC
                LIMIT $limit
                """;
        try {
                var rows = neo4jClient.query(cypher)
                    .bind(fulltextIndex).to("indexName")
                    .bind(keyword).to("keyword")
                    .bind(limit).to("limit")
                    .fetch()
                    .all();

            for (Map<String, Object> row : rows) {
                Long nodeId = asLong(row.get("nodeId"));
                Double score = asDouble(row.get("score"));
                if (nodeId == null || score == null) {
                    continue;
                }
                seedNodeScoreMap.merge(nodeId, score, Math::max);
            }
        } catch (Exception ex) {
            log.warn("Graph fulltext query failed. keyword={}, reason={}", keyword, ex.getMessage());
        }
    }

    private void expandNeighbors(Iterable<Long> seedNodeIds, Set<Long> allNodeIds, Map<Long, Double> nodeScoreMap) {
        int safeDepth = Math.max(expandDepth, 0);
        if (safeDepth <= 0) {
            return;
        }

        Deque<Long> frontier = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        for (Long id : seedNodeIds) {
            if (id != null) {
                frontier.add(id);
                visited.add(id);
            }
        }

        for (int depth = 1; depth <= safeDepth && !frontier.isEmpty(); depth++) {
            int currentLevelSize = frontier.size();
            List<Long> levelSeeds = new ArrayList<>(currentLevelSize);
            for (int i = 0; i < currentLevelSize; i++) {
                Long id = frontier.poll();
                if (id != null) {
                    levelSeeds.add(id);
                }
            }

            List<Long> neighbors = queryNeighborNodeIds(levelSeeds, maxExpandNodes);
            for (Long neighbor : neighbors) {
                if (neighbor == null || visited.contains(neighbor)) {
                    continue;
                }
                visited.add(neighbor);
                frontier.add(neighbor);
                allNodeIds.add(neighbor);
                double decayScore = Math.max(0.1d, 0.5d / depth);
                nodeScoreMap.putIfAbsent(neighbor, decayScore);
                if (allNodeIds.size() >= maxExpandNodes) {
                    return;
                }
            }
        }
    }

    private List<Long> queryNeighborNodeIds(List<Long> sourceNodeIds, int limit) {
        if (sourceNodeIds == null || sourceNodeIds.isEmpty()) {
            return List.of();
        }

        String cypher = """
                UNWIND $nodeIds AS nid
                MATCH (n:Entity)-[:RELATED]-(m:Entity)
                WHERE id(n) = nid
                RETURN DISTINCT id(m) AS nodeId
                LIMIT $limit
                """;
        try {
                var rows = neo4jClient.query(cypher)
                    .bind(sourceNodeIds).to("nodeIds")
                    .bind(limit).to("limit")
                    .fetch()
                    .all();

            List<Long> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Long nodeId = asLong(row.get("nodeId"));
                if (nodeId != null) {
                    result.add(nodeId);
                }
            }
            return result;
        } catch (Exception ex) {
            log.warn("Graph neighbor expansion failed. reason={}", ex.getMessage());
            return List.of();
        }
    }

    private List<Long> sortByScore(Map<Long, Double> scoreMap) {
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    public record GraphRetrievalResult(
            List<String> keywords,
            List<Long> seedNodeIds,
            List<Long> expandedNodeIds,
            List<Long> knowledgeIds,
            List<Long> documentIds,
            Map<Long, Double> knowledgeScoreMap
    ) {
        public static GraphRetrievalResult empty() {
            return new GraphRetrievalResult(List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
        }
    }
}
