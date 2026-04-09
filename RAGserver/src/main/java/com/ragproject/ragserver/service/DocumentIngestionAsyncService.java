package com.ragproject.ragserver.service;

import com.ragproject.ragserver.common.BusinessException;
import com.ragproject.ragserver.mapper.DocumentMapper;
import com.ragproject.ragserver.mapper.KnowledgeItemMapper;
import com.ragproject.ragserver.model.KnowledgeItem;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentIngestionAsyncService {
    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionAsyncService.class);

    // 文档切片长度。值越小检索粒度越细，但向量条目数量会增加。
    private static final int CHUNK_SIZE = 700;

    // 文档状态约定：0失败，1成功，2处理中。
    private static final int STATUS_FAILED = 0;
    private static final int STATUS_ACTIVE = 1;

    private final DocumentMapper documentMapper;
    private final KnowledgeItemMapper knowledgeItemMapper;
    private final KnowledgeIndexService knowledgeIndexService;

    public DocumentIngestionAsyncService(DocumentMapper documentMapper,
                                         KnowledgeItemMapper knowledgeItemMapper,
                                         ObjectProvider<KnowledgeIndexService> knowledgeIndexServiceProvider) {
        this.documentMapper = documentMapper;
        this.knowledgeItemMapper = knowledgeItemMapper;
        this.knowledgeIndexService = knowledgeIndexServiceProvider.getIfAvailable();
    }

    /**
     * 后台异步任务：解析 docx 并完成知识项入库。
     * 设计要点：
     * 1. 通过 @Async 将耗时任务移出请求线程；
     * 2. 失败时统一把文档状态回写为失败，方便前端轮询观察；
     * 3. 先落库再增量索引，保证知识来源可追踪。
     */
    @Async
    @Transactional
    public void ingestDocumentAsync(Long documentId, String documentName, String sourcePath) {
        long start = System.currentTimeMillis();
        try {
            List<String> chunks = parseAndChunkDocument(sourcePath, documentName);
            if (chunks.isEmpty()) {
                throw new BusinessException("D400", "文档内容为空，无法入库");
            }

            List<KnowledgeItem> items = buildKnowledgeItems(documentId, documentName, chunks);
            knowledgeItemMapper.insertBatch(items);

            // 索引服务可能在某些测试/配置环境下被关闭，因此做可选调用。
            if (knowledgeIndexService != null) {
                knowledgeIndexService.addKnowledgeItems(items);
            }

            documentMapper.updateStatusById(documentId, STATUS_ACTIVE);
            log.info("Document async ingestion finished. documentId={}, chunks={}, costMs={}",
                    documentId, items.size(), System.currentTimeMillis() - start);
        } catch (Exception ex) {
            documentMapper.updateStatusById(documentId, STATUS_FAILED);
            log.error("Document async ingestion failed. documentId={}, file={}", documentId, documentName, ex);
        }
    }

    /**
     * 解析本地文档并切片：
     * - docx: 使用 Apache POI 提取段落文本；
     * - pdf: 使用 PDFBox 提取全文文本；
     * - md/txt/json: 按 UTF-8 原文读取。
     */
    private List<String> parseAndChunkDocument(String sourcePath, String documentName) {
        String extension = resolveExtension(sourcePath, documentName);
        String text = switch (extension) {
            case ".docx" -> extractDocxText(sourcePath);
            case ".pdf" -> extractPdfText(sourcePath);
            case ".md", ".txt", ".json" -> extractPlainText(sourcePath);
            default -> throw new BusinessException("D415", "暂不支持该文件格式: " + extension);
        };

        return chunkText(text);
    }

    private String resolveExtension(String sourcePath, String documentName) {
        String name = documentName;
        if (!StringUtils.hasText(name)) {
            name = sourcePath;
        }
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) {
            throw new BusinessException("D415", "文件缺少有效后缀");
        }
        return name.substring(idx).toLowerCase();
    }

    private String extractDocxText(String sourcePath) {
        List<String> paragraphs = new ArrayList<>();
        Path path = Paths.get(sourcePath);

        try (InputStream in = Files.newInputStream(path); XWPFDocument doc = new XWPFDocument(in)) {
            doc.getParagraphs().forEach(p -> {
                String text = p.getText();
                if (StringUtils.hasText(text)) {
                    paragraphs.add(text.trim());
                }
            });
        } catch (IOException ex) {
            throw new BusinessException("D422", "Word 文档解析失败");
        }

        return String.join("\n", paragraphs).trim();
    }

    private String extractPdfText(String sourcePath) {
        Path path = Paths.get(sourcePath);
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document).trim();
        } catch (IOException ex) {
            throw new BusinessException("D422", "PDF 文档解析失败");
        }
    }

    private String extractPlainText(String sourcePath) {
        Path path = Paths.get(sourcePath);
        try {
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            throw new BusinessException("D422", "文本类文档解析失败");
        }
    }

    private List<String> chunkText(String merged) {
        if (!StringUtils.hasText(merged)) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < merged.length(); i += CHUNK_SIZE) {
            int end = Math.min(i + CHUNK_SIZE, merged.length());
            String chunk = merged.substring(i, end).trim();
            // 小于 10 字通常噪声较高，先过滤减少无效索引。
            if (chunk.length() >= 10) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    /**
     * 构造知识项对象并挂接 documentId，确保后续可按文档追踪和清理。
     */
    private List<KnowledgeItem> buildKnowledgeItems(Long documentId, String documentName, List<String> chunks) {
        List<KnowledgeItem> items = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeItem item = new KnowledgeItem();
            item.setDocumentId(documentId);
            item.setQuestion("【" + documentName + "】第" + (i + 1) + "段");
            item.setAnswer(chunks.get(i));
            item.setStatus(STATUS_ACTIVE);
            items.add(item);
        }
        return items;
    }
}
