package com.ragproject.ragserver.service;

import com.ragproject.ragserver.mapper.KnowledgeItemMapper;
import com.ragproject.ragserver.model.KnowledgeItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "app.rag.index", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeIndexService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);

    private final KnowledgeItemMapper knowledgeItemMapper;
    private final VectorStore vectorStore;

    public KnowledgeIndexService(KnowledgeItemMapper knowledgeItemMapper, VectorStore vectorStore) {
        this.knowledgeItemMapper = knowledgeItemMapper;
        this.vectorStore = vectorStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void buildIndexOnStartup() {
        try {
            List<KnowledgeItem> items = knowledgeItemMapper.findActiveItems();
            if (items.isEmpty()) {
                log.info("No active knowledge items found, skip vector indexing");
                return;
            }

            List<Document> documents = items.stream()
                    .map(this::toDocument)
                    .toList();

            vectorStore.add(documents);
            log.info("Knowledge index initialized, itemCount={}", documents.size());
        } catch (Exception ex) {
            log.warn("Failed to initialize knowledge index, fallback to normal chat. reason={}", ex.getMessage());
        }
    }

    public void addKnowledgeItems(List<KnowledgeItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        List<Document> documents = items.stream()
                .map(this::toDocument)
                .toList();
        vectorStore.add(documents);
        log.info("Incremental knowledge indexing finished, itemCount={}", documents.size());
    }

    private Document toDocument(KnowledgeItem item) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("knowledgeId", item.getKnowledgeId());
        metadata.put("question", item.getQuestion());

        String question = StringUtils.hasText(item.getQuestion()) ? item.getQuestion().trim() : "";
        String answer = StringUtils.hasText(item.getAnswer()) ? item.getAnswer().trim() : "";
        String content = "标准问题: " + question + "\n标准答案: " + answer;
        
        log.info("Indexing document: {}", content);
        return new Document(content, metadata);
    }
}
