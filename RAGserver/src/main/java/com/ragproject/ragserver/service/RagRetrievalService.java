package com.ragproject.ragserver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RagRetrievalService {
    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);

    private final VectorStore vectorStore;
    private final RerankService rerankService;

    @Value("${app.rag.rerank.recall-top-k:20}")
    private int recallTopK;

    @Value("${app.rag.rerank.final-top-k:5}")
    private int finalTopK;

    @Value("${app.rag.rerank.alpha:0.7}")
    private double alpha;

    @Value("${app.rag.rerank.beta:0.3}")
    private double beta;

    public RagRetrievalService(VectorStore vectorStore, RerankService rerankService) {
        this.vectorStore = vectorStore;
        this.rerankService = rerankService;
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
                return "";
            }

            List<Candidate> candidates = buildCandidates(documents);
            List<Candidate> rankedCandidates = applyRerank(question.trim(), candidates);
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

    private List<Candidate> buildCandidates(List<Document> documents) {
        List<Candidate> candidates = new ArrayList<>();
        for (Document document : documents) {
            String content = document.getText();
            if (!StringUtils.hasText(content)) {
                continue;
            }
            double vectorScore = extractVectorScore(document);
            candidates.add(new Candidate(content, vectorScore, 0.0, vectorScore));
        }
        return candidates;
    }

    private List<Candidate> applyRerank(String query, List<Candidate> candidates) {
        List<String> documents = candidates.stream().map(Candidate::content).toList();
        List<RerankService.RerankResult> rerankResults = rerankService.rerank(query, documents, Math.max(finalTopK, 1));

        if (rerankResults.isEmpty()) {
            // fallback to vector ranking when rerank is unavailable
            return candidates.stream()
                    .sorted(Comparator.comparingDouble(Candidate::vectorScore).reversed())
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
            double finalScore = alpha * candidate.vectorScore() + beta * rerankScore;
            reranked.add(new Candidate(candidate.content(), candidate.vectorScore(), rerankScore, finalScore));
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

    private String formatScore(double score) {
        return String.format("%.4f", score);
    }

    private record Candidate(String content, double vectorScore, double rerankScore, double finalScore) {
    }
}
