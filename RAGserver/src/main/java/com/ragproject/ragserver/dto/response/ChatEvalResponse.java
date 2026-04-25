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
    private String selectedTool;
    private String routeReason;
    private Boolean usedGraph;
    private String queryComplexity;
    private Boolean decomposed;
    private List<String> subQuestions;
    private List<AgentIterationItem> iterationSummaries;
    private String finalStopReason;

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
                            String requestId,
                            String selectedTool,
                            String routeReason,
                            Boolean usedGraph,
                            String queryComplexity,
                            Boolean decomposed,
                            List<String> subQuestions,
                            List<AgentIterationItem> iterationSummaries,
                            String finalStopReason) {
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
        this.selectedTool = selectedTool;
        this.routeReason = routeReason;
        this.usedGraph = usedGraph;
        this.queryComplexity = queryComplexity;
        this.decomposed = decomposed;
        this.subQuestions = subQuestions;
        this.iterationSummaries = iterationSummaries;
        this.finalStopReason = finalStopReason;
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

    public String getSelectedTool() {
        return selectedTool;
    }

    public String getRouteReason() {
        return routeReason;
    }

    public Boolean getUsedGraph() {
        return usedGraph;
    }

    public String getQueryComplexity() {
        return queryComplexity;
    }

    public Boolean getDecomposed() {
        return decomposed;
    }

    public List<String> getSubQuestions() {
        return subQuestions;
    }

    public List<AgentIterationItem> getIterationSummaries() {
        return iterationSummaries;
    }

    public String getFinalStopReason() {
        return finalStopReason;
    }

    public static class AgentIterationItem {
        private Integer round;
        private String selectedTool;
        private Boolean usedGraph;
        private Integer contextCount;
        private Long retrievalCostMs;
        private Boolean evidenceSufficient;
        private String evidenceReason;

        public AgentIterationItem(Integer round,
                                  String selectedTool,
                                  Boolean usedGraph,
                                  Integer contextCount,
                                  Long retrievalCostMs,
                                  Boolean evidenceSufficient,
                                  String evidenceReason) {
            this.round = round;
            this.selectedTool = selectedTool;
            this.usedGraph = usedGraph;
            this.contextCount = contextCount;
            this.retrievalCostMs = retrievalCostMs;
            this.evidenceSufficient = evidenceSufficient;
            this.evidenceReason = evidenceReason;
        }

        public Integer getRound() {
            return round;
        }

        public String getSelectedTool() {
            return selectedTool;
        }

        public Boolean getUsedGraph() {
            return usedGraph;
        }

        public Integer getContextCount() {
            return contextCount;
        }

        public Long getRetrievalCostMs() {
            return retrievalCostMs;
        }

        public Boolean getEvidenceSufficient() {
            return evidenceSufficient;
        }

        public String getEvidenceReason() {
            return evidenceReason;
        }
    }
}