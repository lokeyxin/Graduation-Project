package com.ragproject.ragserver.dto.response;

import java.time.LocalDateTime;

public class SessionResponse {
    private Long sessionId;
    private String title;
    private LocalDateTime createdAt;

    public SessionResponse(Long sessionId, String title, LocalDateTime createdAt) {
        this.sessionId = sessionId;
        this.title = title;
        this.createdAt = createdAt;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public String getTitle() {
        return title;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
