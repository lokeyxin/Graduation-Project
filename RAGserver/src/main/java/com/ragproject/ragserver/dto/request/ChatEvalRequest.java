package com.ragproject.ragserver.dto.request;

public class ChatEvalRequest {
    private Long sessionId;
    private String question;
    private String questionId;
    private String sourceId;
    private Integer topK;
    private Boolean includeDebug;

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Boolean getIncludeDebug() {
        return includeDebug;
    }

    public void setIncludeDebug(Boolean includeDebug) {
        this.includeDebug = includeDebug;
    }
}