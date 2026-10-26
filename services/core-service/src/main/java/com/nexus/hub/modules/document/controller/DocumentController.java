package com.nexus.hub.modules.document.controller;

import com.nexus.hub.common.dto.ApiResponse;
import com.nexus.hub.modules.document.dto.DocumentResponse;
import com.nexus.hub.modules.document.dto.DocumentUploadResponse;
import com.nexus.hub.modules.document.dto.HybridSearchRequestDto;
import com.nexus.hub.modules.document.dto.HybridSearchResponseDto;
import com.nexus.hub.modules.document.service.DocumentService;
import com.nexus.hub.modules.task.entity.DocumentStatus;
import com.nexus.hub.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentUploadResponse>> uploadDocument(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
            @RequestParam(value = "chunkOverlap", required = false) Integer chunkOverlap,
            @RequestParam(value = "vectorCollection", required = false) String vectorCollection) {

        log.info("Received document upload request: filename='{}', size={} bytes, tenant={}",
                file.getOriginalFilename(), file.getSize(), principal.getTenantId());

        DocumentUploadResponse response = documentService.uploadAndIngest(
                principal, file, chunkSize, chunkOverlap, vectorCollection);

        if (response.getStatus() == DocumentStatus.UPLOADED_PENDING_INDEX) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success(response, response.getMessage()));
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, response.getMessage()));
    }

    @PostMapping("/search")
    public ResponseEntity<ApiResponse<HybridSearchResponseDto>> searchDocuments(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody HybridSearchRequestDto request) {

        HybridSearchResponseDto response = documentService.searchDocuments(principal, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Hybrid search executed successfully"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DocumentResponse>>> listDocuments(
            @AuthenticationPrincipal UserPrincipal principal) {

        List<DocumentResponse> documents = documentService.listDocuments(principal);
        return ResponseEntity.ok(ApiResponse.success(documents, "Retrieved tenant documents successfully"));
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID documentId) {

        documentService.deleteDocument(principal, documentId);
        return ResponseEntity.ok(ApiResponse.success(null, "Document deleted successfully"));
    }
}
