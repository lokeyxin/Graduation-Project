package com.ragproject.ragserver.dto.response;

import java.util.List;

public class ChatEvalResponse {
    private String questionId;
    private String sourceId;
    private Long sessionId;
    private String question;
    private String answer;
    private List<String> retrievedContexts;
    private List<RetrievedContextItem> retrievedContextItems;
    private Integer retrievedCount;
    private Long latencyMs;
    private Boolean usedRag;
    private String requestId;

    public ChatEvalResponse(String questionId,
                            String sourceId,
                            Long sessionId,
                            String question,
                            String answer,
                            List<String> retrievedContexts,
                            List<RetrievedContextItem> retrievedContextItems,
                            Integer retrievedCount,
                            Long latencyMs,
                            Boolean usedRag,
                            String requestId) {
        this.questionId = questionId;
        this.sourceId = sourceId;
        this.sessionId = sessionId;
        this.question = question;
        this.answer = answer;
        this.retrievedContexts = retrievedContexts;
        this.retrievedContextItems = retrievedContextItems;
        this.retrievedCount = retrievedCount;
        this.latencyMs = latencyMs;
        this.usedRag = usedRag;
        this.requestId = requestId;
    }

    public String getQuestionId() {
        return questionId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
    }

    public List<String> getRetrievedContexts() {
        return retrievedContexts;
    }

    public List<RetrievedContextItem> getRetrievedContextItems() {
        return retrievedContextItems;
    }

    public Integer getRetrievedCount() {
        return retrievedCount;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public Boolean getUsedRag() {
        return usedRag;
    }

    public String getRequestId() {
        return requestId;
    }
}