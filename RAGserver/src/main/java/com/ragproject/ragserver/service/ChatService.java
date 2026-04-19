package com.ragproject.ragserver.service;

import com.ragproject.ragserver.common.BusinessException;
import com.ragproject.ragserver.dto.request.ChatEvalRequest;
import com.ragproject.ragserver.dto.response.ChatEvalResponse;
import com.ragproject.ragserver.dto.response.RetrievedContextItem;
import com.ragproject.ragserver.mapper.ChatMessageMapper;
import com.ragproject.ragserver.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String SYSTEM_PROMPT_PREFIX = "你是客服知识库问答助手。你会收到按重排序后的多条知识片段，请综合所有相关片段回答，不要默认只采用第一条。若多条片段存在冲突，优先采用分数更高且表述更完整的片段，并在回答中给出明确说明。若知识片段不足，请明确说明并给出尽可能有帮助的建议。\n\n知识片段:\n";

    private final ChatClient chatClient;
    private final SessionService sessionService;
    private final ChatMessageMapper chatMessageMapper;
    private final RagRetrievalService ragRetrievalService;

    public ChatService(ChatClient.Builder chatClientBuilder,
                       SessionService sessionService,
                       ChatMessageMapper chatMessageMapper,
                       RagRetrievalService ragRetrievalService) {
        this.chatClient = chatClientBuilder.build();
        this.sessionService = sessionService;
        this.chatMessageMapper = chatMessageMapper;
        this.ragRetrievalService = ragRetrievalService;
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
                String ragContext = ragRetrievalService.retrieveContext(trimmedMessage);
                boolean useRagContext = StringUtils.hasText(ragContext);
                int contextHitCount = countContextHits(ragContext);
                log.info("Chat retrieval finished. requestId={}, useRagContext={}, contextHitCount={}, contextLength={}",
                        requestId, useRagContext, contextHitCount, useRagContext ? ragContext.length() : 0);

                String answer = generateAnswer(trimmedMessage, ragContext);

                if (answer == null) {
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
        RagRetrievalService.RetrievalResult retrievalResult =
                ragRetrievalService.retrieveForEval(trimmedQuestion, request.getTopK(), includeDebug);

        String ragContext = retrievalResult.contextText();
        boolean usedRag = StringUtils.hasText(ragContext);
        String answer = generateAnswer(trimmedQuestion, ragContext);
        if (answer == null) {
            answer = "";
        }

        java.util.List<String> retrievedContexts = retrievalResult.contexts().stream()
                .map(RagRetrievalService.RetrievalContext::content)
                .toList();

        java.util.List<RetrievedContextItem> contextItems;
        if (includeDebug) {
            contextItems = retrievalResult.contexts().stream()
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

        long latencyMs = System.currentTimeMillis() - start;
        log.info("Chat eval completed. requestId={}, sessionId={}, questionLength={}, contexts={}, usedRag={}, latencyMs={}",
                requestId, sessionId, trimmedQuestion.length(), retrievedContexts.size(), usedRag, latencyMs);

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
                requestId);
    }

    private String generateAnswer(String question, String ragContext) {
        if (StringUtils.hasText(ragContext)) {
            return chatClient.prompt()
                    .system(SYSTEM_PROMPT_PREFIX + ragContext)
                    .user(question)
                    .call()
                    .content();
        }
        return chatClient.prompt().user(question).call().content();
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
