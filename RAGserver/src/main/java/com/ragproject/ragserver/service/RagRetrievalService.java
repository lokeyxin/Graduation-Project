package com.ragproject.ragserver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

@Service
public class RagRetrievalService {
    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);

    private final VectorStore vectorStore;
    private final RerankService rerankService;
    private final GraphRetrievalService graphRetrievalService;

    @Value("${app.rag.rerank.recall-top-k:20}")
    private int recallTopK;

    @Value("${app.rag.rerank.final-top-k:5}")
    private int finalTopK;

    @Value("${app.rag.rerank.alpha:0.7}")
    private double alpha;

    @Value("${app.rag.rerank.beta:0.3}")
    private double beta;

    @Value("${app.graph.retrieval.vector-threshold:0.65}")
    private double graphVectorThreshold;

    @Value("${app.rag.hybrid.rrf-k:60}")
    private int rrfK;

    @Value("${app.rag.hybrid.graph-vector-top-k:40}")
    private int graphVectorTopK;

    @Value("${app.rag.hybrid.rrf-weight:0.2}")
    private double rrfWeight;

    public RagRetrievalService(VectorStore vectorStore,
                               RerankService rerankService,
                               ObjectProvider<GraphRetrievalService> graphRetrievalServiceProvider) {
        this.vectorStore = vectorStore;
        this.rerankService = rerankService;
        this.graphRetrievalService = graphRetrievalServiceProvider.getIfAvailable();
    }

    public String retrieveContext(String question) {
        if (!StringUtils.hasText(question)) {
            return "";
        }

        try {
            SearchRequest request = SearchRequest.builder()
                    .query(question.trim())
                    .topK(Math.max(recallTopK, 1))
                    .build();

            List<Document> documents = vectorStore.similaritySearch(request);
            if (documents == null || documents.isEmpty()) {
                documents = List.of();
            }

            List<Candidate> vectorCandidates = buildCandidates(documents, "vector", Map.of(), false);
            List<Candidate> graphCandidates = buildGraphVectorCandidates(question.trim());
            List<Candidate> merged = mergeCandidates(vectorCandidates, graphCandidates);
            if (merged.isEmpty()) {
                return "";
            }

            List<Candidate> rankedCandidates = applyRerank(question.trim(), merged);
            List<Candidate> finalCandidates = rankedCandidates.stream()
                    .limit(Math.max(finalTopK, 1))
                    .toList();

            StringBuilder contextBuilder = new StringBuilder();
            int hitIndex = 1;
            for (Candidate candidate : finalCandidates) {
                String content = candidate.content();
                if (!StringUtils.hasText(content)) {
                    continue;
                }
                contextBuilder
                        .append("命中知识")
                        .append(hitIndex++)
                        .append(" [vector=")
                        .append(formatScore(candidate.vectorScore()))
                        .append(", rerank=")
                        .append(formatScore(candidate.rerankScore()))
                        .append(", final=")
                        .append(formatScore(candidate.finalScore()))
                        .append(", route=")
                        .append(candidate.route())
                        .append("]:\n")
                        .append(content.trim())
                        .append("\n\n");
            }

            return contextBuilder.toString().trim();
        } catch (Exception ex) {
            log.warn("RAG retrieval failed, fallback to normal chat. reason={}", ex.getMessage());
            return "";
        }
    }

    private List<Candidate> buildCandidates(List<Document> documents,
                                            String route,
                                            Map<Long, Double> knowledgeScoreMap,
                                            boolean graphRoute) {
        List<Candidate> candidates = new ArrayList<>();
        for (Document document : documents) {
            String content = document.getText();
            if (!StringUtils.hasText(content)) {
                continue;
            }
            double vectorScore = extractVectorScore(document);
            Long knowledgeId = extractKnowledgeId(document);
            double graphScore = knowledgeId == null ? 0.0 : knowledgeScoreMap.getOrDefault(knowledgeId, 0.0);
            String finalRoute = graphRoute ? "graph" : route;
            candidates.add(new Candidate(content, knowledgeId, vectorScore, graphScore, 0.0, 0.0, vectorScore, finalRoute));
        }
        return candidates;
    }

    private List<Candidate> buildGraphVectorCandidates(String question) {
        if (graphRetrievalService == null) {
            return List.of();
        }

        GraphRetrievalService.GraphRetrievalResult graphResult = graphRetrievalService.retrieve(question);
        if (graphResult.knowledgeIds() == null || graphResult.knowledgeIds().isEmpty()) {
            return List.of();
        }

        SearchRequest graphVectorRequest = SearchRequest.builder()
                .query(question)
                .topK(Math.max(graphVectorTopK, 1))
                .build();

        List<Document> graphVectorDocs = vectorStore.similaritySearch(graphVectorRequest);
        if (graphVectorDocs == null || graphVectorDocs.isEmpty()) {
            return List.of();
        }

        Set<Long> allowedKnowledgeIds = new HashSet<>(graphResult.knowledgeIds());
        List<Candidate> filtered = new ArrayList<>();
        for (Candidate candidate : buildCandidates(graphVectorDocs, "graph", graphResult.knowledgeScoreMap(), true)) {
            if (candidate.knowledgeId() == null) {
                continue;
            }
            if (!allowedKnowledgeIds.contains(candidate.knowledgeId())) {
                continue;
            }
            if (candidate.vectorScore() < graphVectorThreshold) {
                continue;
            }
            filtered.add(candidate);
        }
        return filtered;
    }

    private List<Candidate> mergeCandidates(List<Candidate> vectorCandidates, List<Candidate> graphCandidates) {
        Map<String, Candidate> merged = new LinkedHashMap<>();

        List<Candidate> vectorSorted = vectorCandidates.stream()
                .sorted(Comparator.comparingDouble(Candidate::vectorScore).reversed())
                .toList();
        List<Candidate> graphSorted = graphCandidates.stream()
                .sorted(Comparator.comparingDouble(Candidate::vectorScore).reversed())
                .toList();

        Map<String, Integer> vectorRankMap = rankMap(vectorSorted);
        Map<String, Integer> graphRankMap = rankMap(graphSorted);

        for (Candidate candidate : vectorSorted) {
            merged.put(keyOf(candidate), candidate);
        }
        for (Candidate candidate : graphSorted) {
            String key = keyOf(candidate);
            Candidate existing = merged.get(key);
            if (existing == null) {
                merged.put(key, candidate);
            } else {
                merged.put(key, mergeOne(existing, candidate));
            }
        }

        List<Candidate> withRrf = new ArrayList<>();
        int safeRrfK = Math.max(rrfK, 1);
        for (Map.Entry<String, Candidate> entry : merged.entrySet()) {
            String key = entry.getKey();
            Candidate candidate = entry.getValue();

            double rrfScore = 0.0;
            Integer vr = vectorRankMap.get(key);
            if (vr != null) {
                rrfScore += 1.0 / (safeRrfK + vr);
            }
            Integer gr = graphRankMap.get(key);
            if (gr != null) {
                rrfScore += 1.0 / (safeRrfK + gr);
            }

            String route = candidate.route();
            if (vr != null && gr != null) {
                route = "hybrid";
            } else if (gr != null) {
                route = "graph";
            } else {
                route = "vector";
            }

            withRrf.add(new Candidate(
                    candidate.content(),
                    candidate.knowledgeId(),
                    candidate.vectorScore(),
                    candidate.graphScore(),
                    candidate.rerankScore(),
                    rrfScore,
                    candidate.finalScore(),
                    route));
        }

        return withRrf;
    }

    private List<Candidate> applyRerank(String query, List<Candidate> candidates) {
        List<String> documents = candidates.stream().map(Candidate::content).toList();
        List<RerankService.RerankResult> rerankResults = rerankService.rerank(query, documents, Math.max(finalTopK, 1));

        if (rerankResults.isEmpty()) {
            // fallback to vector ranking when rerank is unavailable
            return candidates.stream()
                    .sorted(Comparator.comparingDouble((Candidate c) -> c.vectorScore() + rrfWeight * c.rrfScore()).reversed())
                    .toList();
        }

        Map<Integer, Double> rerankScoreMap = new HashMap<>();
        for (RerankService.RerankResult rerankResult : rerankResults) {
            rerankScoreMap.put(rerankResult.index(), rerankResult.score());
        }

        List<Candidate> reranked = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            double rerankScore = rerankScoreMap.getOrDefault(i, 0.0);
            double finalScore = alpha * candidate.vectorScore()
                + beta * rerankScore
                + rrfWeight * candidate.rrfScore();
            reranked.add(new Candidate(
                candidate.content(),
                candidate.knowledgeId(),
                candidate.vectorScore(),
                candidate.graphScore(),
                rerankScore,
                candidate.rrfScore(),
                finalScore,
                candidate.route()));
        }

        return reranked.stream()
                .sorted(Comparator.comparingDouble(Candidate::finalScore).reversed())
                .toList();
    }

    private double extractVectorScore(Document document) {
        Object score = document.getMetadata().get("score");
        if (score == null) {
            score = document.getMetadata().get("similarity");
        }
        if (score == null) {
            return 0.0;
        }
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(score));
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private Long extractKnowledgeId(Document document) {
        Object id = document.getMetadata().get("knowledgeId");
        if (id == null) {
            id = document.getMetadata().get("knowledge_id");
        }
        if (id instanceof Number number) {
            return number.longValue();
        }
        if (id == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(id));
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Integer> rankMap(List<Candidate> sortedCandidates) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < sortedCandidates.size(); i++) {
            map.put(keyOf(sortedCandidates.get(i)), i + 1);
        }
        return map;
    }

    private String keyOf(Candidate candidate) {
        if (candidate.knowledgeId() != null) {
            return "k#" + candidate.knowledgeId();
        }
        return "c#" + Integer.toHexString(candidate.content().trim().hashCode());
    }

    private Candidate mergeOne(Candidate left, Candidate right) {
        double vectorScore = Math.max(left.vectorScore(), right.vectorScore());
        double graphScore = Math.max(left.graphScore(), right.graphScore());
        String route = left.route().equals(right.route()) ? left.route() : "hybrid";
        return new Candidate(left.content(), left.knowledgeId(), vectorScore, graphScore, 0.0, 0.0, vectorScore, route);
    }

    private String formatScore(double score) {
        return String.format("%.4f", score);
    }

    private record Candidate(
            String content,
            Long knowledgeId,
            double vectorScore,
            double graphScore,
            double rerankScore,
            double rrfScore,
            double finalScore,
            String route
    ) {
    }
}
