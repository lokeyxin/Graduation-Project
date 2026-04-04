package com.ragproject.ragserver.controller;

import com.ragproject.ragserver.dto.request.ChatStreamRequest;
import com.ragproject.ragserver.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ChatService chatService;

    public AIController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatStreamRequest request,
                                 HttpServletRequest servletRequest) {
        Long userId = (Long) servletRequest.getAttribute("currentUserId");
        return chatService.streamChat(userId, request.getSessionId(), request.getMessage());
    }
}