package com.ragproject.ragserver.dto.response;

public class DocumentUploadResponse {
    private Long documentId;
    private String documentName;
    private Integer status;
    private Integer knowledgeItemCount;

    public DocumentUploadResponse(Long documentId, String documentName, Integer status, Integer knowledgeItemCount) {
        this.documentId = documentId;
        this.documentName = documentName;
        this.status = status;
        this.knowledgeItemCount = knowledgeItemCount;
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

    public Integer getKnowledgeItemCount() {
        return knowledgeItemCount;
    }
}
