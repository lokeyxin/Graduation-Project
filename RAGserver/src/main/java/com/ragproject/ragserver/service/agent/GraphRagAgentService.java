package com.ragproject.ragserver.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragproject.ragserver.service.RagRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class GraphRagAgentService {
    private static final Logger log = LoggerFactory.getLogger(GraphRagAgentService.class);

    private static final String ROUTER_SYSTEM_PROMPT = """
            你是一个RAG路由Agent，只负责判断是否需要调用知识图谱工具。
            你必须只输出严格 JSON，不要输出 Markdown、解释或多余文本。

            输出格式：
            {
              "useGraph": true/false,
              "selectedTool": "graph_rag" 或 "vector_rag" 或 "direct_answer",
              "reason": "简短原因"
            }

            判断规则：
            1) 当问题涉及实体关系、人物/组织/地点关联、多跳推理、知识图谱、上下游、来源证据、文档间关联时，useGraph=true。
            2) 当问题是一般闲聊、常识问答、写作、翻译、简单定义、情感表达时，useGraph=false。
            3) 如果无法确定，优先选择 false。
            """;

    private static final String ANSWER_SYSTEM_PROMPT_PREFIX = "你是客服知识库问答助手。你会收到按重排序后的多条知识片段，请综合所有相关片段回答，不要默认只采用第一条。若多条片段存在冲突，优先采用分数更高且表述更完整的片段，并在回答中给出明确说明。若知识片段不足，请明确说明并给出尽可能有帮助的建议。\n\n知识片段:\n";

    private final ChatClient routerChatClient;
    private final ChatClient answerChatClient;
    private final ObjectMapper objectMapper;
    private final RagRetrievalService ragRetrievalService;
    private final GraphRagToolService graphRagToolService;

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
        AgentRouteDecision decision = decideRoute(trimmedQuestion);

        RagRetrievalService.RetrievalResult retrievalResult;
        boolean usedGraph;

        if (Boolean.TRUE.equals(decision.useGraph())) {
            retrievalResult = graphRagToolService.retrieve(trimmedQuestion, topK, includeDebug);
            usedGraph = hasGraphSignal(retrievalResult.contexts());
            if (!StringUtils.hasText(retrievalResult.contextText())) {
                log.info("Graph route selected but no graph context returned, fallback to vector retrieval. questionLength={}", trimmedQuestion.length());
                retrievalResult = ragRetrievalService.retrieveForEval(trimmedQuestion, topK, includeDebug, false);
                usedGraph = false;
            }
        } else {
            retrievalResult = ragRetrievalService.retrieveForEval(trimmedQuestion, topK, includeDebug, false);
            usedGraph = false;
        }

        String answer = generateAnswer(trimmedQuestion, retrievalResult.contextText());
        if (!StringUtils.hasText(answer)) {
            answer = "";
        }

        return new AgentAnswerResult(
                answer,
                usedGraph,
                decision.selectedTool(),
                decision.reason(),
                retrievalResult.contextText(),
                retrievalResult.contexts(),
                retrievalResult.costMs());
    }

    private AgentRouteDecision decideRoute(String question) {
        try {
            String content = routerChatClient.prompt()
                    .system(ROUTER_SYSTEM_PROMPT)
                    .user(question)
                    .call()
                    .content();
            AgentRouteDecision decision = parseDecision(content);
            if (decision != null) {
                return decision;
            }
        } catch (Exception ex) {
            log.warn("Agent route decision failed, fallback to heuristic. reason={}", ex.getMessage());
        }
        return fallbackDecision(question);
    }

    private AgentRouteDecision parseDecision(String rawContent) {
        if (!StringUtils.hasText(rawContent)) {
            return null;
        }

        try {
            String normalized = normalizeJson(rawContent);
            AgentRouteDecision decision = objectMapper.readValue(normalized, AgentRouteDecision.class);
            boolean useGraph = Boolean.TRUE.equals(decision.useGraph());
            String selectedTool = normalizeToolName(decision.selectedTool(), useGraph);
            return new AgentRouteDecision(useGraph, selectedTool, safeReason(decision.reason()));
        } catch (Exception ex) {
            log.debug("Agent route response parse failed. content={}, reason={}", rawContent, ex.getMessage());
            return null;
        }
    }

    private AgentRouteDecision fallbackDecision(String question) {
        boolean useGraph = looksGraphRelevant(question);
        return new AgentRouteDecision(
                useGraph,
                useGraph ? "graph_rag" : "vector_rag",
                useGraph ? "heuristic fallback: graph relevant" : "heuristic fallback: general question");
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

    private String normalizeToolName(String toolName, boolean useGraph) {
        if (!StringUtils.hasText(toolName)) {
            return useGraph ? "graph_rag" : "vector_rag";
        }
        String normalized = toolName.trim();
        if ("graph".equalsIgnoreCase(normalized)) {
            return "graph_rag";
        }
        if ("vector".equalsIgnoreCase(normalized)) {
            return "vector_rag";
        }
        if ("direct".equalsIgnoreCase(normalized) || "direct_answer".equalsIgnoreCase(normalized)) {
            return "direct_answer";
        }
        return normalized;
    }

    private String safeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "";
        }
        return reason.trim();
    }

    public record AgentRouteDecision(Boolean useGraph, String selectedTool, String reason) {
        public static AgentRouteDecision empty() {
            return new AgentRouteDecision(false, "vector_rag", "");
        }
    }

    public record AgentAnswerResult(String answer,
                                    boolean usedGraph,
                                    String selectedTool,
                                    String routeReason,
                                    String contextText,
                                    List<RagRetrievalService.RetrievalContext> retrievalContexts,
                                    long retrievalCostMs) {
        public static AgentAnswerResult empty() {
            return new AgentAnswerResult("", false, "vector_rag", "", "", List.of(), 0L);
        }
    }
}