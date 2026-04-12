package com.ragproject.ragserver.dto.graph;

import java.util.ArrayList;
import java.util.List;

public class GraphExtractionResult {
    private List<ExtractedEntity> entities = new ArrayList<>();
    private List<ExtractedRelation> relations = new ArrayList<>();

    public List<ExtractedEntity> getEntities() {
        return entities;
    }

    public void setEntities(List<ExtractedEntity> entities) {
        this.entities = entities;
    }

    public List<ExtractedRelation> getRelations() {
        return relations;
    }

    public void setRelations(List<ExtractedRelation> relations) {
        this.relations = relations;
    }
}
