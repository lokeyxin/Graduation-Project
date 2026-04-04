package com.ragproject.ragserver.dto.response;

import java.time.LocalDateTime;

public class DocumentResponse {
    private Long documentId;
    private String documentName;
    private Integer status;
    private LocalDateTime createdAt;

    public DocumentResponse(Long documentId, String documentName, Integer status, LocalDateTime createdAt) {
        this.documentId = documentId;
        this.documentName = documentName;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public String getDocumentName() {
        return documentName;
    }

    public Integer getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
