package com.ragproject.ragserver.service;

import com.ragproject.ragserver.common.BusinessException;
import com.ragproject.ragserver.dto.response.DocumentResponse;
import com.ragproject.ragserver.dto.response.DocumentUploadResponse;
import com.ragproject.ragserver.mapper.DocumentMapper;
import com.ragproject.ragserver.mapper.KnowledgeItemMapper;
import com.ragproject.ragserver.model.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final int STATUS_PROCESSING = 2;

    private final DocumentMapper documentMapper;
    private final KnowledgeItemMapper knowledgeItemMapper;
    private final DocumentIngestionAsyncService documentIngestionAsyncService;
    private final KnowledgeIndexService knowledgeIndexService;

    @Value("${app.upload.knowledge-dir:uploads/knowledge}")
    private String knowledgeUploadDir;

    @Value("${app.upload.allowed-extension:.docx}")
    private String allowedExtension;

    /**
     * 该服务只负责“快速返回”的同步步骤：校验、保存文件、落文档元数据、触发异步任务。
     *
     * 解析 Word 与向量入库是耗时操作，放在异步服务中执行，避免大文件阻塞上传接口线程。
     */
    public DocumentService(DocumentMapper documentMapper,
                           KnowledgeItemMapper knowledgeItemMapper,
                           DocumentIngestionAsyncService documentIngestionAsyncService,
                           ObjectProvider<KnowledgeIndexService> knowledgeIndexServiceProvider) {
        this.documentMapper = documentMapper;
        this.knowledgeItemMapper = knowledgeItemMapper;
        this.documentIngestionAsyncService = documentIngestionAsyncService;
        this.knowledgeIndexService = knowledgeIndexServiceProvider.getIfAvailable();
    }

    /**
     * 上传接口主流程：
     * 1. 同步完成基础校验和文件落盘；
     * 2. 文档状态置为“处理中”；
     * 3. 立即返回，后台异步执行“解析 + 切片 + 知识项入库 + 向量索引”。
     */
    public DocumentUploadResponse uploadAndIngestAsync(Long userId, MultipartFile file, boolean overwrite) {
        validateUpload(file);

        String originalName = file.getOriginalFilename();
        Document existing = documentMapper.findByUserIdAndDocumentName(userId, originalName);
        if (existing != null && !overwrite) {
            throw new BusinessException("D409", "已存在同名文件，请确认是否替换");
        }

        String savedPath = saveFile(file);
        Long documentId;

        if (existing == null) {
            try {
                Document document = new Document();
                document.setUserId(userId);
                document.setDocumentName(originalName);
                document.setSourcePath(savedPath);
                document.setStatus(STATUS_PROCESSING);
                documentMapper.insert(document);
                documentId = document.getDocumentId();
            } catch (DuplicateKeyException ex) {
                deleteFileQuietly(savedPath);
                throw new BusinessException("D409", "已存在同名文件，请确认是否替换");
            }
        } else {
            knowledgeItemMapper.deleteByDocumentId(existing.getDocumentId());
            deleteFileQuietly(existing.getSourcePath());
            documentMapper.updateForReplace(existing.getDocumentId(), savedPath, STATUS_PROCESSING);
            documentId = existing.getDocumentId();
        }

        // 异步任务只传“轻量且可序列化”的参数，避免在后台线程持有 MultipartFile 流对象。
        documentIngestionAsyncService.ingestDocxAsync(documentId, originalName, savedPath);

        log.info("Document accepted for async ingestion. userId={}, documentId={}, file={}",
                userId, documentId, originalName);
        return new DocumentUploadResponse(documentId, originalName, STATUS_PROCESSING, null);
    }

    public List<DocumentResponse> listDocuments(Long userId) {
        return documentMapper.findByUserId(userId).stream()
                .map(doc -> new DocumentResponse(doc.getDocumentId(), doc.getDocumentName(), doc.getStatus(), doc.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void deleteDocument(Long userId, Long documentId) {
        Document document = documentMapper.findById(documentId);
        if (document == null) {
            throw new BusinessException("D404", "文档不存在或已删除");
        }

        if (!document.getUserId().equals(userId)) {
            throw new BusinessException("D403", "无权删除该文档");
        }

        if (document.getStatus() != null && document.getStatus() == STATUS_PROCESSING) {
            throw new BusinessException("D409", "文档处理中，暂不支持删除");
        }

        if (knowledgeIndexService != null) {
            try {
                knowledgeIndexService.deleteByDocumentId(documentId);
            } catch (Exception ex) {
                throw new BusinessException("D500", "文档索引删除失败，请稍后重试");
            }
        }

        knowledgeItemMapper.deleteByDocumentId(documentId);
        int deleted = documentMapper.deleteById(documentId);
        if (deleted <= 0) {
            throw new BusinessException("D404", "文档不存在或已删除");
        }

        deleteFileQuietly(document.getSourcePath());
        log.info("Document deleted. userId={}, documentId={}", userId, documentId);
    }

    /**
     * 上传前校验：
     * - 文件必须存在且非空
     * - 文件名必须存在
     * - 仅允许配置中的后缀（当前默认 .docx）
     */
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

    /**
     * 将上传文件写入本地目录，返回最终绝对路径。
     *
     * 注意：
     * - 使用 UUID 生成目标文件名，防止同名覆盖；
     * - 使用 REPLACE_EXISTING 保证同 UUID 时仍可覆盖（概率极低，仅作为防御）。
     */
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

    private void deleteFileQuietly(String sourcePath) {
        if (!StringUtils.hasText(sourcePath)) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(sourcePath));
        } catch (IOException ex) {
            log.warn("Failed to delete old uploaded file. path={}", sourcePath, ex);
        }
    }
}
