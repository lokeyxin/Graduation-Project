package com.ragproject.ragserver.model.graph;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

@RelationshipProperties
public class GraphEntityRelation {
    @Id
    @GeneratedValue
    private Long id;

    @TargetNode
    private GraphEntityNode target;

    @Property("relationType")
    private String relationType;

    @Property("evidence")
    private String evidence;

    @Property("documentId")
    private Long documentId;

    @Property("knowledgeId")
    private Long knowledgeId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public GraphEntityNode getTarget() {
        return target;
    }

    public void setTarget(GraphEntityNode target) {
        this.target = target;
    }

    public String getRelationType() {
        return relationType;
    }

    public void setRelationType(String relationType) {
        this.relationType = relationType;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
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
}
