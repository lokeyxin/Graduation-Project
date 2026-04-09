package com.ragproject.ragserver.controller;

import com.ragproject.ragserver.common.ApiResponse;
import com.ragproject.ragserver.dto.request.CreateSessionRequest;
import com.ragproject.ragserver.dto.response.MessageResponse;
import com.ragproject.ragserver.dto.response.SessionResponse;
import com.ragproject.ragserver.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {
    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ApiResponse<SessionResponse> createSession(@RequestBody CreateSessionRequest request,
                                                      HttpServletRequest servletRequest) {
        Long userId = (Long) servletRequest.getAttribute("currentUserId");
        return ApiResponse.ok(sessionService.createSession(userId, request.getTitle()));
    }

    @GetMapping
    public ApiResponse<List<SessionResponse>> listSessions(HttpServletRequest servletRequest) {
        Long userId = (Long) servletRequest.getAttribute("currentUserId");
        return ApiResponse.ok(sessionService.listSessions(userId));
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResponse<List<MessageResponse>> listMessages(@PathVariable Long sessionId,
                                                           HttpServletRequest servletRequest) {
        Long userId = (Long) servletRequest.getAttribute("currentUserId");
        return ApiResponse.ok(sessionService.listMessages(userId, sessionId));
    }

    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable Long sessionId,
                                           HttpServletRequest servletRequest) {
        Long userId = (Long) servletRequest.getAttribute("currentUserId");
        sessionService.deleteSession(userId, sessionId);
        return ApiResponse.ok(null);
    }
}
