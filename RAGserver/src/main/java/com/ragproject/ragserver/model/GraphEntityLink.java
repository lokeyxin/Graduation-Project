package com.ragproject.ragserver.model;

import java.time.LocalDateTime;

public class GraphEntityLink {
    private Long id;
    private Long documentId;
    private Long knowledgeId;
    private Long graphNodeId;
    private String entityName;
    private String entityType;
    private Integer sourceChunkNo;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Long getKnowledgeId() {
        return knowledgeId;
    }

    public void setKnowledgeId(Long knowledgeId) {
        this.knowledgeId = knowledgeId;
    }

    public Long getGraphNodeId() {
        return graphNodeId;
    }

    public void setGraphNodeId(Long graphNodeId) {
        this.graphNodeId = graphNodeId;
    }

    public String getEntityName() {
        return entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public Integer getSourceChunkNo() {
        return sourceChunkNo;
    }

    public void setSourceChunkNo(Integer sourceChunkNo) {
        this.sourceChunkNo = sourceChunkNo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
