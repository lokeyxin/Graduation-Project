package com.ragproject.ragserver.service;

import com.ragproject.ragserver.mapper.KnowledgeItemMapper;
import com.ragproject.ragserver.model.KnowledgeItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
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
    private static final int MAX_PROVIDER_BATCH_LIMIT = 10;

    private final KnowledgeItemMapper knowledgeItemMapper;
    private final VectorStore vectorStore;

    @Value("${app.rag.index.max-batch-size:10}")
    private int indexBatchSize;

    public KnowledgeIndexService(KnowledgeItemMapper knowledgeItemMapper, VectorStore vectorStore) {
        this.knowledgeItemMapper = knowledgeItemMapper;
        this.vectorStore = vectorStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void buildIndexOnStartup() {
        try {
            // 启动时按“状态有效”全量加载知识项，保证向量库和关系库初始一致。
            List<KnowledgeItem> items = knowledgeItemMapper.findActiveItems();
            if (items.isEmpty()) {
                log.info("No active knowledge items found, skip vector indexing");
                return;
            }

            List<Document> documents = items.stream()
                    .map(this::toDocument)
                    .toList();

            addDocumentsInBatches(documents, "startup");
            log.info("Knowledge index initialized, itemCount={}", documents.size());
        } catch (Exception ex) {
            log.warn("Failed to initialize knowledge index, fallback to normal chat. reason={}", ex.getMessage());
        }
    }

    public void addKnowledgeItems(List<KnowledgeItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        // 增量模式：只将本次新增知识项加入向量库，避免每次上传都全量重建索引。
        List<Document> documents = items.stream()
                .map(this::toDocument)
                .toList();
        addDocumentsInBatches(documents, "incremental");
        log.info("Incremental knowledge indexing finished, itemCount={}", documents.size());
    }

    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        String filterExpression = "documentId == " + documentId;
        vectorStore.delete(filterExpression);
        log.info("Vector index delete finished. documentId={}", documentId);
    }

    private void addDocumentsInBatches(List<Document> documents, String source) {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        int safeBatchSize = Math.min(Math.max(indexBatchSize, 1), MAX_PROVIDER_BATCH_LIMIT);
        int total = documents.size();
        for (int start = 0; start < total; start += safeBatchSize) {
            int end = Math.min(start + safeBatchSize, total);
            vectorStore.add(documents.subList(start, end));
        }

        if (indexBatchSize > MAX_PROVIDER_BATCH_LIMIT) {
            log.warn("Configured app.rag.index.max-batch-size={} exceeds provider limit {}, clamped automatically",
                    indexBatchSize, MAX_PROVIDER_BATCH_LIMIT);
        }
        log.info("Vector index write finished. source={}, itemCount={}, batchSize={}", source, total, safeBatchSize);
    }

    private Document toDocument(KnowledgeItem item) {
        Map<String, Object> metadata = new HashMap<>();
        // 向量库文档元数据不允许 null 值，这里只写入非空字段。
        if (item.getKnowledgeId() != null) {
            metadata.put("knowledgeId", item.getKnowledgeId());
        }
        if (item.getDocumentId() != null) {
            metadata.put("documentId", item.getDocumentId());
        }

        String question = StringUtils.hasText(item.getQuestion()) ? item.getQuestion().trim() : "";
        String answer = StringUtils.hasText(item.getAnswer()) ? item.getAnswer().trim() : "";
        metadata.put("question", question);
        String content = "标准问题: " + question + "\n标准答案: " + answer;
        
        // 保留轻量日志便于排障，避免打印过大的原文导致日志噪声。
        log.info("Indexing document. knowledgeId={}, documentId={}", item.getKnowledgeId(), item.getDocumentId());
        return new Document(content, metadata);
    }
}
