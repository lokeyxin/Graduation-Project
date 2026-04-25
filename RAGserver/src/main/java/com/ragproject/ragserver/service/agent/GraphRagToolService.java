package com.ragproject.ragserver.service.agent;

import com.ragproject.ragserver.service.RagRetrievalService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GraphRagToolService {
    private final RagRetrievalService ragRetrievalService;

    public GraphRagToolService(RagRetrievalService ragRetrievalService) {
        this.ragRetrievalService = ragRetrievalService;
    }

    public RagRetrievalService.RetrievalResult retrieve(String question, Integer topK, boolean includeDebug) {
        if (!StringUtils.hasText(question)) {
            return new RagRetrievalService.RetrievalResult("", java.util.List.of(), 0L);
        }
        return ragRetrievalService.retrieveForEval(question, topK, includeDebug, true);
    }
}