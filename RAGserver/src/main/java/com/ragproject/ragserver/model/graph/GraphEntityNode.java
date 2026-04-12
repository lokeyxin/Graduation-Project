package com.ragproject.ragserver.model.graph;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

@Node("Entity")
public class GraphEntityNode {
    @Id
    @GeneratedValue
    private Long nodeId;

    @Property("name")
    private String name;

    @Property("normalizedName")
    private String normalizedName;

    @Property("type")
    private String type;

    @Property("documentId")
    private Long documentId;

    @Property("knowledgeId")
    private Long knowledgeId;

    @Relationship(type = "RELATED")
    private Set<GraphEntityRelation> relations = new HashSet<>();

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public void setNormalizedName(String normalizedName) {
        this.normalizedName = normalizedName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    public Set<GraphEntityRelation> getRelations() {
        return relations;
    }

    public void setRelations(Set<GraphEntityRelation> relations) {
        this.relations = relations;
    }
}
