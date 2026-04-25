package com.ragproject.ragserver.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragproject.ragserver.service.RagRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class GraphRagAgentService {
    private static final Logger log = LoggerFactory.getLogger(GraphRagAgentService.class);

        private static final String ANALYSIS_SYSTEM_PROMPT = """
                        你是一个查询分析工具，只负责判断检索路由与是否拆解。
            你必须只输出严格 JSON，不要输出 Markdown、解释或多余文本。

            输出格式：
            {
                            "shouldUseGraph": true/false,
                            "shouldDecompose": true/false,
                            "complexity": "simple|multi_hop|multi_entity|comparison|unclear",
              "reason": "简短原因"
            }

            判断规则：
            1) 当问题涉及实体关系、人物/组织/地点关联、多跳推理、知识图谱、上下游、来源证据、文档间关联时，useGraph=true。
            2) 当问题是一般闲聊、常识问答、写作、翻译、简单定义、情感表达时，useGraph=false。
                        3) 当问题包含多个条件、多个实体、对比、因果链、步骤拆分时 shouldDecompose=true。
                        4) 如果无法确定，优先选择 shouldUseGraph=false 且 shouldDecompose=false。
                        """;

        private static final String DECOMPOSITION_SYSTEM_PROMPT = """
                        你是问题拆解工具。
                        给定原问题后，请拆成可独立检索的原子子问题。
                        要求：
                        1) 子问题需要互补，避免语义重复。
                        2) 子问题保留关键实体、时间、约束。
                        3) 子问题必须是问句，长度简洁。
                        4) 若原问题不需要拆解，返回一个与原问题等价的子问题。

                        输出严格 JSON：
                        {
                            "subQuestions": ["..."],
                            "reason": "简短原因"
                        }
                        """;

        private static final String EVIDENCE_CHECK_SYSTEM_PROMPT = """
                        你是 EvidenceCheckAdvisor，只负责验证回答是否被证据支持。
                        你必须只输出严格 JSON，不要输出 Markdown、解释或多余文本。

                        输出严格 JSON：
                        {
                            "sufficient": true/false,
                            "reason": "简短原因",
                            "nextAction": "finish|retry_same|retry_graph|retry_vector|retry_decompose"
                        }

                        判断原则：
                        1) 回答关键结论都能在证据中找到依据，且不存在明显冲突时 sufficient=true。
                        2) 若证据不足、冲突未解决、回答超出证据范围，sufficient=false。
                        3) 若已可交付或继续检索价值不大，nextAction=finish。
            """;

    private static final String ANSWER_SYSTEM_PROMPT_PREFIX = "你是客服知识库问答助手。你会收到按重排序后的多条知识片段，请综合所有相关片段回答，不要默认只采用第一条。若多条片段存在冲突，优先采用分数更高且表述更完整的片段，并在回答中给出明确说明。若知识片段不足，请明确说明并给出尽可能有帮助的建议。\n\n知识片段:\n";

    private final ChatClient routerChatClient;
    private final ChatClient answerChatClient;
    private final ObjectMapper objectMapper;
    private final RagRetrievalService ragRetrievalService;
    private final GraphRagToolService graphRagToolService;

    @Value("${app.agent.analysis.enable-decomposition:true}")
    private boolean decompositionEnabled;

    @Value("${app.agent.analysis.max-sub-questions:3}")
    private int maxSubQuestions;

    @Value("${app.agent.analysis.parallel-retrieval:2}")
    private int parallelRetrieval;

    @Value("${app.agent.evidence.enabled:true}")
    private boolean evidenceCheckEnabled;

    @Value("${app.agent.evidence.max-rounds:2}")
    private int evidenceMaxRounds;

    @Value("${app.agent.merge.default-top-k:8}")
    private int mergedDefaultTopK;

    public GraphRagAgentService(ChatClient.Builder chatClientBuilder,
                                ObjectMapper objectMapper,
                                RagRetrievalService ragRetrievalService,
                                GraphRagToolService graphRagToolService) {
        this.routerChatClient = chatClientBuilder.build();
        this.answerChatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.ragRetrievalService = ragRetrievalService;
        this.graphRagToolService = graphRagToolService;
    }

    public AgentAnswerResult answer(String question) {
        return answer(question, null, false);
    }

    public AgentAnswerResult answer(String question, Integer topK, boolean includeDebug) {
        if (!StringUtils.hasText(question)) {
            return AgentAnswerResult.empty();
        }

        String trimmedQuestion = question.trim();
        QueryAnalysisDecision analysisDecision = analyzeQuery(trimmedQuestion);
        List<String> subQuestions = resolveSubQuestions(trimmedQuestion, analysisDecision);

        boolean useGraph = Boolean.TRUE.equals(analysisDecision.shouldUseGraph());
        int rounds = evidenceCheckEnabled ? Math.max(1, evidenceMaxRounds) : 1;
        String finalAnswer = "";
        MergedRetrieval mergedRetrieval = MergedRetrieval.empty(useGraph);
        String finalStopReason = "single_round";
        List<IterationSummary> iterationSummaries = new ArrayList<>();

        for (int round = 1; round <= rounds; round++) {
            mergedRetrieval = retrieveWithSubQuestions(subQuestions, topK, includeDebug, useGraph);
            finalAnswer = generateAnswer(trimmedQuestion, mergedRetrieval.contextText());
            if (!StringUtils.hasText(finalAnswer)) {
                finalAnswer = "";
            }

            EvidenceCheckDecision evidenceCheckDecision = evidenceCheckEnabled
                    ? checkEvidence(trimmedQuestion, finalAnswer, mergedRetrieval.contextText())
                    : EvidenceCheckDecision.finish("evidence check disabled");

            iterationSummaries.add(new IterationSummary(
                    round,
                    mergedRetrieval.selectedTool(),
                    mergedRetrieval.usedGraph(),
                    mergedRetrieval.contexts().size(),
                    mergedRetrieval.totalCostMs(),
                    Boolean.TRUE.equals(evidenceCheckDecision.sufficient()),
                    evidenceCheckDecision.reason()));

            String nextAction = normalizeNextAction(evidenceCheckDecision.nextAction());
            if (Boolean.TRUE.equals(evidenceCheckDecision.sufficient())) {
                finalStopReason = "evidence_sufficient";
                break;
            }
            if ("finish".equals(nextAction)) {
                finalStopReason = "advisor_finish";
                break;
            }
            if (round >= rounds) {
                finalStopReason = "max_rounds_reached";
                break;
            }

            if ("retry_graph".equals(nextAction)) {
                useGraph = true;
            } else if ("retry_vector".equals(nextAction)) {
                useGraph = false;
            } else if ("retry_decompose".equals(nextAction)) {
                subQuestions = resolveSubQuestions(trimmedQuestion,
                        new QueryAnalysisDecision(useGraph, true, analysisDecision.complexity(), "advisor requested decomposition"));
            }
        }

        return new AgentAnswerResult(
                finalAnswer,
                mergedRetrieval.usedGraph(),
                mergedRetrieval.selectedTool(),
                mergeRouteReason(analysisDecision.reason(), mergedRetrieval.routeReason(), finalStopReason),
                mergedRetrieval.contextText(),
                mergedRetrieval.contexts(),
                mergedRetrieval.totalCostMs(),
                analysisDecision,
                subQuestions,
                iterationSummaries,
                finalStopReason);
    }

    private QueryAnalysisDecision analyzeQuery(String question) {
        try {
            String content = routerChatClient.prompt()
                    .system(ANALYSIS_SYSTEM_PROMPT)
                    .user(question)
                    .call()
                    .content();
            QueryAnalysisDecision decision = parseAnalysis(content);
            if (decision != null) {
                return decision;
            }
        } catch (Exception ex) {
            log.warn("Query analysis failed, fallback to heuristic. reason={}", ex.getMessage());
        }
        return fallbackAnalysis(question);
    }

    private QueryAnalysisDecision parseAnalysis(String rawContent) {
        if (!StringUtils.hasText(rawContent)) {
            return null;
        }

        try {
            String normalized = normalizeJson(rawContent);
            QueryAnalysisDecision decision = objectMapper.readValue(normalized, QueryAnalysisDecision.class);
            boolean shouldUseGraph = Boolean.TRUE.equals(decision.shouldUseGraph());
            boolean shouldDecompose = decompositionEnabled && Boolean.TRUE.equals(decision.shouldDecompose());
            String complexity = safeComplexity(decision.complexity());
            String reason = safeReason(decision.reason());
            return new QueryAnalysisDecision(shouldUseGraph, shouldDecompose, complexity, reason);
        } catch (Exception ex) {
            log.debug("Query analysis response parse failed. content={}, reason={}", rawContent, ex.getMessage());
            return null;
        }
    }

    private QueryAnalysisDecision fallbackAnalysis(String question) {
        boolean shouldUseGraph = looksGraphRelevant(question);
        boolean shouldDecompose = decompositionEnabled && looksDecomposeRelevant(question);
        return new QueryAnalysisDecision(
                shouldUseGraph,
                shouldDecompose,
                shouldDecompose ? "multi_entity" : "simple",
                shouldUseGraph ? "heuristic: graph relevant" : "heuristic: general question");
    }

    private List<String> resolveSubQuestions(String question, QueryAnalysisDecision analysisDecision) {
        if (!decompositionEnabled || !Boolean.TRUE.equals(analysisDecision.shouldDecompose())) {
            return List.of(question);
        }

        int maxCount = Math.max(1, maxSubQuestions);
        String userPrompt = "原问题: " + question + "\n最多拆分数量: " + maxCount;
        try {
            String content = routerChatClient.prompt()
                    .system(DECOMPOSITION_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();
            DecompositionDecision decision = parseDecomposition(content, maxCount);
            if (decision != null && decision.subQuestions() != null && !decision.subQuestions().isEmpty()) {
                return decision.subQuestions();
            }
        } catch (Exception ex) {
            log.warn("Question decomposition failed, fallback to single query. reason={}", ex.getMessage());
        }
        return List.of(question);
    }

    private DecompositionDecision parseDecomposition(String rawContent, int maxCount) {
        if (!StringUtils.hasText(rawContent)) {
            return null;
        }
        try {
            String normalized = normalizeJson(rawContent);
            DecompositionDecision decision = objectMapper.readValue(normalized, DecompositionDecision.class);
            List<String> cleaned = sanitizeSubQuestions(decision.subQuestions(), maxCount);
            if (cleaned.isEmpty()) {
                return null;
            }
            return new DecompositionDecision(cleaned, safeReason(decision.reason()));
        } catch (Exception ex) {
            log.debug("Question decomposition parse failed. content={}, reason={}", rawContent, ex.getMessage());
            return null;
        }
    }

    private List<String> sanitizeSubQuestions(List<String> rawSubQuestions, int maxCount) {
        if (rawSubQuestions == null || rawSubQuestions.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String item : rawSubQuestions) {
            if (!StringUtils.hasText(item)) {
                continue;
            }
            String normalized = item.trim();
            String dedupKey = normalized.toLowerCase(Locale.ROOT);
            if (!unique.containsKey(dedupKey)) {
                unique.put(dedupKey, normalized);
            }
            if (unique.size() >= maxCount) {
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    private MergedRetrieval retrieveWithSubQuestions(List<String> subQuestions,
                                                     Integer topK,
                                                     boolean includeDebug,
                                                     boolean useGraph) {
        List<String> actualSubQuestions = subQuestions == null || subQuestions.isEmpty()
                ? List.of()
                : subQuestions;
        if (actualSubQuestions.isEmpty()) {
            return MergedRetrieval.empty(useGraph);
        }

        int poolSize = Math.max(1, Math.min(Math.max(parallelRetrieval, 1), actualSubQuestions.size()));
        ExecutorService executorService = Executors.newFixedThreadPool(poolSize);
        try {
            List<CompletableFuture<SubQuestionRetrieval>> futures = actualSubQuestions.stream()
                    .map(subQuestion -> CompletableFuture.supplyAsync(
                            () -> retrieveSingle(subQuestion, topK, includeDebug, useGraph),
                            executorService))
                    .toList();

            List<SubQuestionRetrieval> retrievals = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
            return mergeRetrievals(retrievals, topK, useGraph);
        } finally {
            executorService.shutdown();
        }
    }

    private SubQuestionRetrieval retrieveSingle(String subQuestion,
                                                Integer topK,
                                                boolean includeDebug,
                                                boolean useGraph) {
        RagRetrievalService.RetrievalResult retrievalResult;
        boolean usedGraph;
        String selectedTool;
        String routeReason;

        if (useGraph) {
            retrievalResult = graphRagToolService.retrieve(subQuestion, topK, includeDebug);
            usedGraph = hasGraphSignal(retrievalResult.contexts());
            selectedTool = "graph_rag";
            routeReason = "analysis decided graph retrieval";
            if (!StringUtils.hasText(retrievalResult.contextText())) {
                retrievalResult = ragRetrievalService.retrieveForEval(subQuestion, topK, includeDebug, false);
                usedGraph = false;
                selectedTool = "vector_rag";
                routeReason = "graph retrieval empty, fallback to vector";
            }
        } else {
            retrievalResult = ragRetrievalService.retrieveForEval(subQuestion, topK, includeDebug, false);
            usedGraph = false;
            selectedTool = "vector_rag";
            routeReason = "analysis decided vector retrieval";
        }

        return new SubQuestionRetrieval(subQuestion, retrievalResult, usedGraph, selectedTool, routeReason);
    }

    private MergedRetrieval mergeRetrievals(List<SubQuestionRetrieval> retrievals, Integer topK, boolean defaultUseGraph) {
        if (retrievals == null || retrievals.isEmpty()) {
            return MergedRetrieval.empty(defaultUseGraph);
        }

        Map<String, SourceContext> deduplicated = new LinkedHashMap<>();
        boolean usedGraph = false;
        long totalCostMs = 0L;
        List<String> routeReasons = new ArrayList<>();

        for (SubQuestionRetrieval retrieval : retrievals) {
            if (retrieval == null) {
                continue;
            }
            usedGraph = usedGraph || retrieval.usedGraph();
            totalCostMs += retrieval.retrievalResult().costMs();
            routeReasons.add(retrieval.routeReason());

            for (RagRetrievalService.RetrievalContext context : retrieval.retrievalResult().contexts()) {
                String key = contextKey(context);
                SourceContext existing = deduplicated.get(key);
                if (existing == null || context.finalScore() > existing.context().finalScore()) {
                    deduplicated.put(key, new SourceContext(retrieval.subQuestion(), context));
                }
            }
        }

        int desiredTopK = topK == null ? Math.max(1, mergedDefaultTopK) : Math.max(topK, 1);
        List<SourceContext> sortedContexts = deduplicated.values().stream()
                .sorted(Comparator.comparingDouble((SourceContext item) -> item.context().finalScore()).reversed())
                .limit(desiredTopK)
                .toList();

        List<RagRetrievalService.RetrievalContext> finalContexts = new ArrayList<>();
        StringBuilder mergedContextBuilder = new StringBuilder();
        int rank = 1;
        for (SourceContext sourceContext : sortedContexts) {
            RagRetrievalService.RetrievalContext context = sourceContext.context();
            finalContexts.add(new RagRetrievalService.RetrievalContext(
                    context.knowledgeId(),
                    context.content(),
                    context.vectorScore(),
                    context.rerankScore(),
                    context.finalScore(),
                    context.route(),
                    rank));

            mergedContextBuilder
                    .append("命中知识")
                    .append(rank)
                    .append(" [vector=")
                    .append(formatScore(context.vectorScore()))
                    .append(", rerank=")
                    .append(formatScore(context.rerankScore()))
                    .append(", final=")
                    .append(formatScore(context.finalScore()))
                    .append(", route=")
                    .append(context.route())
                    .append(", source=")
                    .append(sourceContext.sourceQuestion())
                    .append("]:\n")
                    .append(context.content())
                    .append("\n\n");
            rank++;
        }

        String mergedContext = mergedContextBuilder.toString().trim();
        String selectedTool = usedGraph ? "graph_rag" : (defaultUseGraph ? "graph_rag" : "vector_rag");
        String routeReason = String.join("; ", routeReasons);
        return new MergedRetrieval(selectedTool, routeReason, usedGraph, mergedContext, finalContexts, totalCostMs);
    }

    private EvidenceCheckDecision checkEvidence(String question, String answer, String contextText) {
        try {
            String userPrompt = "问题:\n" + question + "\n\n回答:\n" + answer + "\n\n证据:\n" + contextText;
            String content = routerChatClient.prompt()
                    .system(EVIDENCE_CHECK_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();
            EvidenceCheckDecision decision = parseEvidenceCheck(content);
            if (decision != null) {
                return decision;
            }
        } catch (Exception ex) {
            log.warn("Evidence check failed, fallback to deterministic rule. reason={}", ex.getMessage());
        }

        boolean sufficient = StringUtils.hasText(contextText) && StringUtils.hasText(answer);
        if (!StringUtils.hasText(contextText)) {
            return new EvidenceCheckDecision(false, "fallback: empty context", "retry_same");
        }
        return new EvidenceCheckDecision(sufficient, "fallback: evidence check unavailable", sufficient ? "finish" : "retry_same");
    }

    private EvidenceCheckDecision parseEvidenceCheck(String rawContent) {
        if (!StringUtils.hasText(rawContent)) {
            return null;
        }
        try {
            String normalized = normalizeJson(rawContent);
            EvidenceCheckDecision decision = objectMapper.readValue(normalized, EvidenceCheckDecision.class);
            boolean sufficient = Boolean.TRUE.equals(decision.sufficient());
            return new EvidenceCheckDecision(sufficient, safeReason(decision.reason()), normalizeNextAction(decision.nextAction()));
        } catch (Exception ex) {
            log.debug("Evidence check parse failed. content={}, reason={}", rawContent, ex.getMessage());
            return null;
        }
    }

    private String generateAnswer(String question, String ragContext) {
        if (StringUtils.hasText(ragContext)) {
            return answerChatClient.prompt()
                    .system(ANSWER_SYSTEM_PROMPT_PREFIX + ragContext)
                    .user(question)
                    .call()
                    .content();
        }
        return answerChatClient.prompt().user(question).call().content();
    }

    private String contextKey(RagRetrievalService.RetrievalContext context) {
        if (context.knowledgeId() != null) {
            return "k#" + context.knowledgeId();
        }
        return "c#" + Integer.toHexString(context.content().trim().hashCode());
    }

    private String formatScore(double score) {
        return String.format(Locale.ROOT, "%.4f", score);
    }

    private boolean hasGraphSignal(List<RagRetrievalService.RetrievalContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return false;
        }
        for (RagRetrievalService.RetrievalContext context : contexts) {
            if (context == null || !StringUtils.hasText(context.route())) {
                continue;
            }
            String route = context.route().trim();
            if ("graph".equalsIgnoreCase(route) || "hybrid".equalsIgnoreCase(route)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksGraphRelevant(String question) {
        String value = question == null ? "" : question.toLowerCase();
        return value.contains("图谱")
                || value.contains("关系")
                || value.contains("关联")
                || value.contains("上下游")
                || value.contains("证据")
                || value.contains("实体")
                || value.contains("谁和谁")
                || value.contains("之间")
                || value.contains("来源")
                || value.contains("知识图谱")
                || value.contains("entity")
                || value.contains("relation")
                || value.contains("link");
    }

    private boolean looksDecomposeRelevant(String question) {
        String value = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return value.contains("以及")
                || value.contains("分别")
                || value.contains("对比")
                || value.contains("比较")
                || value.contains("先")
                || value.contains("再")
                || value.contains("并且")
                || value.contains("和")
                || value.contains("影响")
                || value.contains("原因");
    }

    private String normalizeJson(String raw) {
        String value = raw.trim();
        if (value.startsWith("```")) {
            int firstBreak = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                value = value.substring(firstBreak + 1, lastFence).trim();
            }
        }
        return value;
    }

    private String safeComplexity(String complexity) {
        if (!StringUtils.hasText(complexity)) {
            return "simple";
        }
        String normalized = complexity.trim().toLowerCase(Locale.ROOT);
        if ("multi_hop".equals(normalized)
                || "multi_entity".equals(normalized)
                || "comparison".equals(normalized)
                || "unclear".equals(normalized)
                || "simple".equals(normalized)) {
            return normalized;
        }
        return "unclear";
    }

    private String safeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "";
        }
        return reason.trim();
    }

    private String normalizeNextAction(String nextAction) {
        if (!StringUtils.hasText(nextAction)) {
            return "retry_same";
        }
        String normalized = nextAction.trim().toLowerCase(Locale.ROOT);
        if ("finish".equals(normalized)
                || "retry_same".equals(normalized)
                || "retry_graph".equals(normalized)
                || "retry_vector".equals(normalized)
                || "retry_decompose".equals(normalized)) {
            return normalized;
        }
        return "retry_same";
    }

    private String mergeRouteReason(String analysisReason, String retrievalReason, String stopReason) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(analysisReason)) {
            builder.append(analysisReason.trim());
        }
        if (StringUtils.hasText(retrievalReason)) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(retrievalReason.trim());
        }
        if (StringUtils.hasText(stopReason)) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append("stop=").append(stopReason);
        }
        return builder.toString();
    }

    public record QueryAnalysisDecision(Boolean shouldUseGraph,
                                        Boolean shouldDecompose,
                                        String complexity,
                                        String reason) {
        public static QueryAnalysisDecision empty() {
            return new QueryAnalysisDecision(false, false, "simple", "");
        }
    }

    public record DecompositionDecision(List<String> subQuestions, String reason) {
    }

    public record EvidenceCheckDecision(Boolean sufficient, String reason, String nextAction) {
        public static EvidenceCheckDecision finish(String reason) {
            return new EvidenceCheckDecision(true, reason, "finish");
        }
    }

    public record IterationSummary(int round,
                                   String selectedTool,
                                   boolean usedGraph,
                                   int contextCount,
                                   long retrievalCostMs,
                                   boolean evidenceSufficient,
                                   String evidenceReason) {
    }

    private record SubQuestionRetrieval(String subQuestion,
                                        RagRetrievalService.RetrievalResult retrievalResult,
                                        boolean usedGraph,
                                        String selectedTool,
                                        String routeReason) {
    }

    private record SourceContext(String sourceQuestion, RagRetrievalService.RetrievalContext context) {
    }

    private record MergedRetrieval(String selectedTool,
                                   String routeReason,
                                   boolean usedGraph,
                                   String contextText,
                                   List<RagRetrievalService.RetrievalContext> contexts,
                                   long totalCostMs) {
        private static MergedRetrieval empty(boolean useGraph) {
            return new MergedRetrieval(
                    useGraph ? "graph_rag" : "vector_rag",
                    "",
                    false,
                    "",
                    List.of(),
                    0L);
        }
    }

    public record AgentAnswerResult(String answer,
                                    boolean usedGraph,
                                    String selectedTool,
                                    String routeReason,
                                    String contextText,
                                    List<RagRetrievalService.RetrievalContext> retrievalContexts,
                                    long retrievalCostMs,
                                    QueryAnalysisDecision queryAnalysis,
                                    List<String> subQuestions,
                                    List<IterationSummary> iterationSummaries,
                                    String finalStopReason) {
        public static AgentAnswerResult empty() {
            return new AgentAnswerResult(
                    "",
                    false,
                    "vector_rag",
                    "",
                    "",
                    List.of(),
                    0L,
                    QueryAnalysisDecision.empty(),
                    List.of(),
                    List.of(),
                    "empty");
        }
    }
}