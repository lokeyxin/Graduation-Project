package com.ragproject.ragserver.service;

import com.ragproject.ragserver.common.BusinessException;
import com.ragproject.ragserver.mapper.ChatMessageMapper;
import com.ragproject.ragserver.model.ChatMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatService {
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

        sessionService.ensureSessionOwner(userId, sessionId);

        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(sessionId);
        userMessage.setRole("user");
        userMessage.setContent(message.trim());
        userMessage.setTokenCount(0);
        chatMessageMapper.insert(userMessage);

        SseEmitter emitter = new SseEmitter(0L);
        Thread thread = new Thread(() -> {
            StringBuilder fullAnswer = new StringBuilder();
            try {
                String trimmedMessage = message.trim();
                String ragContext = ragRetrievalService.retrieveContext(trimmedMessage);

                String answer;
                if (StringUtils.hasText(ragContext)) {
                    answer = chatClient.prompt()
                            .system("你是客服知识库问答助手。你会收到按重排序后的多条知识片段，请综合所有相关片段回答，不要默认只采用第一条。若多条片段存在冲突，优先采用分数更高且表述更完整的片段，并在回答中给出明确说明。若知识片段不足，请明确说明并给出尽可能有帮助的建议。\n\n知识片段:\n" + ragContext)
                            .user(trimmedMessage)
                            .call()
                            .content();
                } else {
                    answer = chatClient.prompt().user(trimmedMessage).call().content();
                }

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

                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception ex) {
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
}
