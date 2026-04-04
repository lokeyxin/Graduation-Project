package com.ragproject.ragserver.dto.response;

import java.time.LocalDateTime;

public class MessageResponse {
    private Long messageId;
    private Long sessionId;
    private String role;
    private String content;
    private LocalDateTime createdAt;

    public MessageResponse(Long messageId, Long sessionId, String role, String content, LocalDateTime createdAt) {
        this.messageId = messageId;
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
    }

    public Long getMessageId() {
        return messageId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
