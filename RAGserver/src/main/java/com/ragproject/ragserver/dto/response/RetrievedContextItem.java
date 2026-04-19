package com.ragproject.ragserver.dto.response;

public class RetrievedContextItem {
    private Long knowledgeId;
    private String content;
    private Double vectorScore;
    private Double rerankScore;
    private Double finalScore;
    private String route;
    private Integer rank;

    public RetrievedContextItem(Long knowledgeId,
                                String content,
                                Double vectorScore,
                                Double rerankScore,
                                Double finalScore,
                                String route,
                                Integer rank) {
        this.knowledgeId = knowledgeId;
        this.content = content;
        this.vectorScore = vectorScore;
        this.rerankScore = rerankScore;
        this.finalScore = finalScore;
        this.route = route;
        this.rank = rank;
    }

    public Long getKnowledgeId() {
        return knowledgeId;
    }

    public String getContent() {
        return content;
    }

    public Double getVectorScore() {
        return vectorScore;
    }

    public Double getRerankScore() {
        return rerankScore;
    }

    public Double getFinalScore() {
        return finalScore;
    }

    public String getRoute() {
        return route;
    }

    public Integer getRank() {
        return rank;
    }
}