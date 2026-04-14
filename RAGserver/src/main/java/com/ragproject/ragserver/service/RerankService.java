package com.ragproject.ragserver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class RerankService {
    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${app.rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${app.rag.rerank.model:qwen3-rerank}")
    private String rerankModel;

    @Value("${app.rag.rerank.endpoint:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}")
    private String endpoint;

    @Value("${app.rag.rerank.api-key:${spring.ai.openai.api-key:}}")
    private String apiKey;

    @Value("${app.rag.rerank.timeout-ms:8000}")
    private int timeoutMs;

    public RerankService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        if (!rerankEnabled || !StringUtils.hasText(query) || documents == null || documents.isEmpty()) {
            log.debug("Rerank skipped. enabled={}, queryPresent={}, documentCount={}",
                    rerankEnabled, StringUtils.hasText(query), documents == null ? 0 : documents.size());
            return List.of();
        }
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(apiKey)) {
            log.warn("Rerank is enabled but endpoint/apiKey is missing, skip rerank");
            return List.of();
        }

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> payload = Map.of(
                    "model", rerankModel,
                "input", Map.of(
                    "query", query,
                    "documents", documents
                ),
                "parameters", Map.of(
                    "top_n", Math.max(topN, 1)
                )
            );

            String body = restClient.post()
                .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            if (!StringUtils.hasText(body)) {
                return List.of();
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode resultsNode = root.path("output").path("results");
            if (!resultsNode.isArray()) {
                // compatibility fallback for providers that return OpenAI-style data
                resultsNode = root.path("data");
            }
            if (!resultsNode.isArray()) {
                return List.of();
            }

            List<RerankResult> results = new ArrayList<>();
            for (JsonNode item : resultsNode) {
                int index = item.path("index").asInt(-1);
                if (index < 0) {
                    continue;
                }
                double score = 0.0;
                if (item.has("relevance_score")) {
                    score = item.path("relevance_score").asDouble(0.0);
                } else if (item.has("score")) {
                    score = item.path("score").asDouble(0.0);
                }
                results.add(new RerankResult(index, score));
            }

            results.sort(Comparator.comparingDouble(RerankResult::score).reversed());
            log.debug("Rerank success. documentCount={}, topN={}, resultCount={}, costMs={}",
                    documents.size(), Math.max(topN, 1), results.size(), System.currentTimeMillis() - start);
            return results;
        } catch (Exception ex) {
            long costMs = System.currentTimeMillis() - start;
            log.warn("Rerank failed, fallback to vector ranking. failureType={}, costMs={}, reason={}",
                    classifyFailure(ex), costMs, ex.getMessage());
            return List.of();
        }
    }

    private String classifyFailure(Exception ex) {
        if (ex instanceof ResourceAccessException) {
            String message = ex.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("connection reset")) {
                    return "connection-reset";
                }
                if (lower.contains("timed out") || lower.contains("timeout")) {
                    return "timeout";
                }
            }
            return "resource-access";
        }
        return "other";
    }

    public record RerankResult(int index, double score) {
    }
}
