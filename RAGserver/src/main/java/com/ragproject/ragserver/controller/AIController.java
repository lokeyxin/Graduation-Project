package com.ragproject.ragserver.controller;

import com.ragproject.ragserver.common.ApiResponse;
import com.ragproject.ragserver.dto.request.ChatEvalRequest;
import com.ragproject.ragserver.dto.request.ChatStreamRequest;
import com.ragproject.ragserver.dto.response.ChatEvalResponse;
import com.ragproject.ragserver.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * @author DengJinxin
 * @data 2026年03月24日13:15
 */
@RestController
@RequestMapping("/api/v1/chat")
public class AIController {
    private static final Logger log = LoggerFactory.getLogger(AIController.class);

    private final ChatService chatService;

    public AIController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatStreamRequest request,
                                 HttpServletRequest servletRequest) {
        Long userId = (Long) servletRequest.getAttribute("currentUserId");
        int messageLength = request != null && request.getMessage() != null ? request.getMessage().length() : 0;
        Long sessionId = request != null ? request.getSessionId() : null;
        log.info("Chat stream request accepted. userId={}, sessionId={}, messageLength={}",
                userId, sessionId, messageLength);
        return chatService.streamChat(userId, request.getSessionId(), request.getMessage());
    }

    @PostMapping("/eval")
    public ApiResponse<ChatEvalResponse> chatEval(@RequestBody ChatEvalRequest request,
                                                  HttpServletRequest servletRequest) {
        Long userId = (Long) servletRequest.getAttribute("currentUserId");
        int messageLength = request != null && request.getQuestion() != null ? request.getQuestion().length() : 0;
        Long sessionId = request != null ? request.getSessionId() : null;
        log.info("Chat eval request accepted. userId={}, sessionId={}, messageLength={}",
                userId, sessionId, messageLength);
        return ApiResponse.ok(chatService.evalChat(userId, request));
    }
}