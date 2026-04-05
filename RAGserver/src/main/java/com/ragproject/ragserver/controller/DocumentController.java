package com.ragproject.ragserver.controller;

import com.ragproject.ragserver.common.ApiResponse;
import com.ragproject.ragserver.dto.response.DocumentResponse;
import com.ragproject.ragserver.dto.response.DocumentUploadResponse;
import com.ragproject.ragserver.service.DocumentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/upload")
    public ApiResponse<DocumentUploadResponse> upload(@RequestParam("file") MultipartFile file,
                                                      HttpServletRequest servletRequest) {
        Long userId = (Long) servletRequest.getAttribute("currentUserId");
        return ApiResponse.ok(documentService.uploadAndIngestAsync(userId, file));
    }

    @GetMapping
    public ApiResponse<List<DocumentResponse>> list(HttpServletRequest servletRequest) {
        Long userId = (Long) servletRequest.getAttribute("currentUserId");
        return ApiResponse.ok(documentService.listDocuments(userId));
    }
}
