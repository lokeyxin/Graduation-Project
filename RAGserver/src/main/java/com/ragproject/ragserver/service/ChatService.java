package com.ragproject.ragserver.service;

import com.ragproject.ragserver.common.BusinessException;
import com.ragproject.ragserver.dto.request.ChatEvalRequest;
import com.ragproject.ragserver.dto.response.ChatEvalResponse;
import com.ragproject.ragserver.dto.response.RetrievedContextItem;
import com.ragproject.ragserver.mapper.ChatMessageMapper;
import com.ragproject.ragserver.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final SessionService sessionService;
    private final ChatMessageMapper chatMessageMapper;
    private final ObjectProvider<com.ragproject.ragserver.service.agent.GraphRagAgentService> graphRagAgentServiceProvider;

    public ChatService(SessionService sessionService,
                       ChatMessageMapper chatMessageMapper,
                       ObjectProvider<com.ragproject.ragserver.service.agent.GraphRagAgentService> graphRagAgentServiceProvider) {
        this.sessionService = sessionService;
        this.chatMessageMapper = chatMessageMapper;
        this.graphRagAgentServiceProvider = graphRagAgentServiceProvider;
    }

    public SseEmitter streamChat(Long userId, Long sessionId, String message) {
        if (!StringUtils.hasText(message)) {
            throw new BusinessException("C001", "消息内容不能为空");
        }

        String requestId = UUID.randomUUID().toString();
        String trimmedMessage = message.trim();
        log.info("Chat stream started. requestId={}, userId={}, sessionId={}, messageLength={}",
                requestId, userId, sessionId, trimmedMessage.length());

        sessionService.ensureSessionOwner(userId, sessionId);

        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(sessionId);
        userMessage.setRole("user");
        userMessage.setContent(trimmedMessage);
        userMessage.setTokenCount(0);
        chatMessageMapper.insert(userMessage);

        SseEmitter emitter = new SseEmitter(0L);
        Thread thread = new Thread(() -> {
            long start = System.currentTimeMillis();
            StringBuilder fullAnswer = new StringBuilder();
            try {
                var agentResult = graphRagAgentServiceProvider.getObject().answer(trimmedMessage);
                String ragContext = agentResult.contextText();
                boolean useRagContext = StringUtils.hasText(ragContext);
                int contextHitCount = countContextHits(ragContext);
                log.info("Chat routing finished. requestId={}, selectedTool={}, usedGraph={}, reason={}, contextHitCount={}, contextLength={}",
                        requestId,
                        agentResult.selectedTool(),
                        agentResult.usedGraph(),
                        agentResult.routeReason(),
                        contextHitCount,
                        useRagContext ? ragContext.length() : 0);

                String answer = agentResult.answer();
                if (!StringUtils.hasText(answer)) {
                    answer = "";
                }

                String[] chunks = answer.split("(?<=\\G.{12})");
                for (String chunk : chunks) {
                    fullAnswer.append(chunk);
                    emitter.send(SseEmitter.event().name("message").data(chunk));
                    Thread.sleep(20);
                }

                ChatMessage assistantMessage = new ChatMessage();
                assistantMessage.setSessionId(sessionId);
                assistantMessage.setRole("assistant");
                assistantMessage.setContent(fullAnswer.toString());
                assistantMessage.setTokenCount(0);
                chatMessageMapper.insert(assistantMessage);

                long costMs = System.currentTimeMillis() - start;
                log.info("Chat stream completed. requestId={}, answerLength={}, costMs={}",
                        requestId, fullAnswer.length(), costMs);

                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception ex) {
                long costMs = System.currentTimeMillis() - start;
                log.error("Chat stream failed. requestId={}, costMs={}, reason={}",
                        requestId, costMs, ex.getMessage(), ex);
                try {
                    emitter.send(SseEmitter.event().name("error").data("生成失败: " + ex.getMessage()));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(ex);
            }
        }, "chat-stream-thread");

        thread.start();
        return emitter;
    }

    public ChatEvalResponse evalChat(Long userId, ChatEvalRequest request) {
        if (request == null) {
            throw new BusinessException("C001", "请求不能为空");
        }
        if (request.getSessionId() == null) {
            throw new BusinessException("C001", "sessionId 不能为空");
        }
        if (!StringUtils.hasText(request.getQuestion())) {
            throw new BusinessException("C001", "问题内容不能为空");
        }

        String requestId = UUID.randomUUID().toString();
        String trimmedQuestion = request.getQuestion().trim();
        Long sessionId = request.getSessionId();
        boolean includeDebug = Boolean.TRUE.equals(request.getIncludeDebug());

        sessionService.ensureSessionOwner(userId, sessionId);

        long start = System.currentTimeMillis();
        var agentResult = graphRagAgentServiceProvider.getObject().answer(trimmedQuestion, request.getTopK(), includeDebug);

        String ragContext = agentResult.contextText();
        boolean usedRag = agentResult.usedGraph() || StringUtils.hasText(ragContext);
        String answer = agentResult.answer();
        if (!StringUtils.hasText(answer)) {
            answer = "";
        }

        java.util.List<String> retrievedContexts = agentResult.retrievalContexts().stream()
                .map(RagRetrievalService.RetrievalContext::content)
                .toList();

        java.util.List<RetrievedContextItem> contextItems;
        if (includeDebug) {
            contextItems = agentResult.retrievalContexts().stream()
                    .map(item -> new RetrievedContextItem(
                            item.knowledgeId(),
                            item.content(),
                            item.vectorScore(),
                            item.rerankScore(),
                            item.finalScore(),
                            item.route(),
                            item.rank()))
                    .toList();
        } else {
            contextItems = java.util.List.of();
        }

        java.util.List<String> subQuestions = includeDebug ? agentResult.subQuestions() : java.util.List.of();
        java.util.List<ChatEvalResponse.AgentIterationItem> iterationItems;
        if (includeDebug) {
            iterationItems = agentResult.iterationSummaries().stream()
                    .map(item -> new ChatEvalResponse.AgentIterationItem(
                            item.round(),
                            item.selectedTool(),
                            item.usedGraph(),
                            item.contextCount(),
                            item.retrievalCostMs(),
                            item.evidenceSufficient(),
                            item.evidenceReason()))
                    .toList();
        } else {
            iterationItems = java.util.List.of();
        }

        long latencyMs = System.currentTimeMillis() - start;
        log.info("Chat eval completed. requestId={}, sessionId={}, questionLength={}, contexts={}, usedRag={}, selectedTool={}, latencyMs={}",
                requestId, sessionId, trimmedQuestion.length(), retrievedContexts.size(), usedRag, agentResult.selectedTool(), latencyMs);

        return new ChatEvalResponse(
                request.getQuestionId(),
                request.getSourceId(),
                sessionId,
                trimmedQuestion,
                answer,
                retrievedContexts,
                contextItems,
                retrievedContexts.size(),
                latencyMs,
                usedRag,
                requestId,
                agentResult.selectedTool(),
                includeDebug ? agentResult.routeReason() : null,
                includeDebug ? agentResult.usedGraph() : null,
                includeDebug ? agentResult.queryAnalysis().complexity() : null,
                includeDebug ? Boolean.valueOf(subQuestions.size() > 1) : null,
                subQuestions,
                iterationItems,
                includeDebug ? agentResult.finalStopReason() : null);
    }

    private int countContextHits(String ragContext) {
        if (!StringUtils.hasText(ragContext)) {
            return 0;
        }

        int count = 0;
        int fromIndex = 0;
        String marker = "命中知识";
        while (true) {
            int index = ragContext.indexOf(marker, fromIndex);
            if (index < 0) {
                break;
            }
            count++;
            fromIndex = index + marker.length();
        }
        return count;
    }
}
