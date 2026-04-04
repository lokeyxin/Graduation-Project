package com.ragproject.ragserver.service;

import com.ragproject.ragserver.common.BusinessException;
import com.ragproject.ragserver.dto.response.DocumentResponse;
import com.ragproject.ragserver.dto.response.DocumentUploadResponse;
import com.ragproject.ragserver.mapper.DocumentMapper;
import com.ragproject.ragserver.mapper.KnowledgeItemMapper;
import com.ragproject.ragserver.model.Document;
import com.ragproject.ragserver.model.KnowledgeItem;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final int CHUNK_SIZE = 700;
    private static final int STATUS_FAILED = 0;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_PROCESSING = 2;

    private final DocumentMapper documentMapper;
    private final KnowledgeItemMapper knowledgeItemMapper;
    private final KnowledgeIndexService knowledgeIndexService;

    @Value("${app.upload.knowledge-dir:uploads/knowledge}")
    private String knowledgeUploadDir;

    @Value("${app.upload.allowed-extension:.docx}")
    private String allowedExtension;

    public DocumentService(DocumentMapper documentMapper,
                           KnowledgeItemMapper knowledgeItemMapper,
                           ObjectProvider<KnowledgeIndexService> knowledgeIndexServiceProvider) {
        this.documentMapper = documentMapper;
        this.knowledgeItemMapper = knowledgeItemMapper;
        this.knowledgeIndexService = knowledgeIndexServiceProvider.getIfAvailable();
    }

    public DocumentUploadResponse uploadAndIngest(Long userId, MultipartFile file) {
        validateUpload(file);

        String originalName = file.getOriginalFilename();
        String savedPath = saveFile(file);

        Document document = new Document();
        document.setUserId(userId);
        document.setDocumentName(originalName);
        document.setSourcePath(savedPath);
        document.setStatus(STATUS_PROCESSING);
        documentMapper.insert(document);

        try {
            List<String> chunks = parseAndChunkDocx(file);
            if (chunks.isEmpty()) {
                throw new BusinessException("D400", "文档内容为空，无法入库");
            }

            List<KnowledgeItem> items = buildKnowledgeItems(originalName, chunks);
            knowledgeItemMapper.insertBatch(items);
            if (knowledgeIndexService != null) {
                knowledgeIndexService.addKnowledgeItems(items);
            }

            documentMapper.updateStatusById(document.getDocumentId(), STATUS_ACTIVE);
            return new DocumentUploadResponse(document.getDocumentId(), originalName, STATUS_ACTIVE, items.size());
        } catch (BusinessException ex) {
            documentMapper.updateStatusById(document.getDocumentId(), STATUS_FAILED);
            throw ex;
        } catch (Exception ex) {
            documentMapper.updateStatusById(document.getDocumentId(), STATUS_FAILED);
            log.error("Document upload failed. userId={}, documentId={}, file={}", userId, document.getDocumentId(), originalName, ex);
            throw new BusinessException("D500", "文档解析或入库失败");
        }
    }

    public List<DocumentResponse> listDocuments(Long userId) {
        return documentMapper.findByUserId(userId).stream()
                .map(doc -> new DocumentResponse(doc.getDocumentId(), doc.getDocumentName(), doc.getStatus(), doc.getCreatedAt()))
                .toList();
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("D400", "上传文件不能为空");
        }

        String originalName = file.getOriginalFilename();
        if (!StringUtils.hasText(originalName)) {
            throw new BusinessException("D400", "文件名不能为空");
        }

        String normalized = originalName.toLowerCase();
        if (!normalized.endsWith(allowedExtension.toLowerCase())) {
            throw new BusinessException("D415", "目前仅支持 .docx 文件上传");
        }
    }

    private String saveFile(MultipartFile file) {
        Path uploadRoot = Paths.get(knowledgeUploadDir).toAbsolutePath().normalize();
        String originalName = file.getOriginalFilename();
        String extension = originalName.substring(originalName.lastIndexOf('.'));
        String targetName = UUID.randomUUID() + extension;

        try {
            Files.createDirectories(uploadRoot);
            Path target = uploadRoot.resolve(targetName);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target.toString();
        } catch (IOException ex) {
            throw new BusinessException("D500", "文件保存失败");
        }
    }

    private List<String> parseAndChunkDocx(MultipartFile file) {
        List<String> paragraphs = new ArrayList<>();
        try (InputStream in = file.getInputStream(); XWPFDocument doc = new XWPFDocument(in)) {
            doc.getParagraphs().forEach(p -> {
                String text = p.getText();
                if (StringUtils.hasText(text)) {
                    paragraphs.add(text.trim());
                }
            });
        } catch (IOException ex) {
            throw new BusinessException("D422", "Word 文档解析失败");
        }

        String merged = String.join("\n", paragraphs).trim();
        if (!StringUtils.hasText(merged)) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < merged.length(); i += CHUNK_SIZE) {
            int end = Math.min(i + CHUNK_SIZE, merged.length());
            String chunk = merged.substring(i, end).trim();
            if (chunk.length() >= 10) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    private List<KnowledgeItem> buildKnowledgeItems(String documentName, List<String> chunks) {
        List<KnowledgeItem> items = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeItem item = new KnowledgeItem();
            item.setQuestion("【" + documentName + "】第" + (i + 1) + "段");
            item.setAnswer(chunks.get(i));
            item.setStatus(STATUS_ACTIVE);
            items.add(item);
        }
        return items;
    }
}
