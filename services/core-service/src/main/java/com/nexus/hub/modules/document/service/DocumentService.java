package com.nexus.hub.modules.document.service;

import com.nexus.hub.common.exception.ApiException;
import com.nexus.hub.common.exception.AiServiceUnavailableException;
import com.nexus.hub.grpc.ai.v1.DocumentChunk;
import com.nexus.hub.grpc.ai.v1.HybridSearchResponse;
import com.nexus.hub.grpc.ai.v1.IngestDocumentResponse;
import com.nexus.hub.grpc.client.RagGrpcClient;
import com.nexus.hub.modules.document.dto.DocumentResponse;
import com.nexus.hub.modules.document.dto.DocumentUploadResponse;
import com.nexus.hub.modules.document.dto.HybridSearchRequestDto;
import com.nexus.hub.modules.document.dto.HybridSearchResponseDto;
import com.nexus.hub.modules.task.entity.Document;
import com.nexus.hub.modules.task.entity.DocumentStatus;
import com.nexus.hub.modules.task.repository.DocumentRepository;
import com.nexus.hub.modules.user.entity.User;
import com.nexus.hub.modules.user.repository.UserRepository;
import com.nexus.hub.security.UserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

@Slf4j
@Service
public class DocumentService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".pdf", ".txt", ".md", ".docx");
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "text/plain",
            "text/markdown",
            "text/x-markdown",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/octet-stream"
    );

    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final RagGrpcClient ragGrpcClient;
    private final String sharedUploadsDir;

    public DocumentService(
            DocumentRepository documentRepository,
            UserRepository userRepository,
            RagGrpcClient ragGrpcClient,
            @Value("${nexus.storage.shared-uploads-dir:${nexus.storage.upload-dir:./data/shared-uploads}}") String sharedUploadsDir) {
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.ragGrpcClient = ragGrpcClient;
        this.sharedUploadsDir = sharedUploadsDir;
    }

    @Transactional
    public DocumentUploadResponse uploadAndIngest(
            UserPrincipal principal,
            MultipartFile file,
            Integer chunkSize,
            Integer chunkOverlap,
            String vectorCollection) {

        if (file == null || file.isEmpty()) {
            throw new ApiException("Uploaded file cannot be empty", HttpStatus.BAD_REQUEST);
        }

        String originalFilename = StringUtils.cleanPath(Objects.requireNonNullElse(file.getOriginalFilename(), "unnamed"));
        validateFileType(originalFilename, file.getContentType());

        UUID documentId = UUID.randomUUID();
        String sanitizedName = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
        String storedFileName = documentId + "_" + sanitizedName;

        Path tenantDirectory = Paths.get(sharedUploadsDir, principal.getTenantId().toString());
        Path targetFilePath = tenantDirectory.resolve(storedFileName);

        try {
            Files.createDirectories(tenantDirectory);
            Files.copy(file.getInputStream(), targetFilePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Persisted uploaded file for tenant {} at {}", principal.getTenantId(), targetFilePath);
        } catch (IOException e) {
            log.error("Failed to store file on shared disk: {}", e.getMessage(), e);
            throw new ApiException("Failed to save uploaded file to storage: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }

        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ApiException("User context not found", HttpStatus.UNAUTHORIZED));

        String collection = (vectorCollection != null && !vectorCollection.isBlank())
                ? vectorCollection
                : "enterprise_docs";

        String normalizedFilePath = targetFilePath.toAbsolutePath().toString().replace('\\', '/');

        Document document = Document.builder()
                .id(documentId)
                .tenant(user.getTenant())
                .user(user)
                .filename(originalFilename)
                .filePath(normalizedFilePath)
                .fileSize(file.getSize())
                .mimeType(resolveMimeType(originalFilename, file.getContentType()))
                .vectorCollection(collection)
                .status(DocumentStatus.UPLOADED)
                .chunksCount(0)
                .build();

        document = documentRepository.save(document);

        // Invoke AI service for RAG ingestion
        try {
            int finalChunkSize = (chunkSize != null && chunkSize > 0) ? chunkSize : 500;
            int finalChunkOverlap = (chunkOverlap != null && chunkOverlap >= 0) ? chunkOverlap : 50;

            Map<String, String> metadata = new HashMap<>();
            metadata.put("tenant_id", principal.getTenantId().toString());
            metadata.put("filename", originalFilename);
            metadata.put("user_id", principal.getId().toString());

            IngestDocumentResponse response = ragGrpcClient.ingestDocument(
                    documentId,
                    normalizedFilePath,
                    document.getMimeType(),
                    finalChunkSize,
                    finalChunkOverlap,
                    collection,
                    metadata
            );

            document.setStatus(DocumentStatus.INDEXED);
            document.setChunksCount(response.getChunksCreated());
            documentRepository.save(document);

            log.info("Successfully ingested document {} into Qdrant collection '{}' with {} chunks",
                    documentId, collection, response.getChunksCreated());

            return DocumentUploadResponse.builder()
                    .documentId(document.getId())
                    .fileName(document.getFilename())
                    .status(DocumentStatus.INDEXED)
                    .chunksCreated(response.getChunksCreated())
                    .vectorCollection(document.getVectorCollection())
                    .createdAt(document.getCreatedAt())
                    .message("Document uploaded and indexed successfully into vector database.")
                    .build();

        } catch (AiServiceUnavailableException e) {
            log.warn("AI Service offline during ingestion of document {}. Gracefully setting status to UPLOADED_PENDING_INDEX: {}",
                    documentId, e.getMessage());

            document.setStatus(DocumentStatus.UPLOADED_PENDING_INDEX);
            documentRepository.save(document);

            return DocumentUploadResponse.builder()
                    .documentId(document.getId())
                    .fileName(document.getFilename())
                    .status(DocumentStatus.UPLOADED_PENDING_INDEX)
                    .chunksCreated(0)
                    .vectorCollection(document.getVectorCollection())
                    .createdAt(document.getCreatedAt())
                    .message("Document uploaded successfully. AI indexing service is currently offline; status marked as UPLOADED_PENDING_INDEX.")
                    .build();

        } catch (Exception e) {
            log.error("Failed to index document {}: {}", documentId, e.getMessage(), e);
            document.setStatus(DocumentStatus.FAILED);
            documentRepository.save(document);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public HybridSearchResponseDto searchDocuments(UserPrincipal principal, HybridSearchRequestDto request) {
        log.info("Executing hybrid search for tenant: {}, query: '{}'", principal.getTenantId(), request.getQuery());

        HybridSearchResponse response = ragGrpcClient.hybridSearch(
                request.getQuery(),
                request.getTopK() != null ? request.getTopK() : 5,
                request.getAlpha() != null ? request.getAlpha() : 0.7f,
                request.getRerank() == null || request.getRerank(),
                request.getCollectionName() != null ? request.getCollectionName() : "enterprise_docs",
                request.getMetadataFilters()
        );

        List<HybridSearchResponseDto.DocumentChunkDto> chunkDtos = response.getResultsList().stream()
                .map(chunk -> HybridSearchResponseDto.DocumentChunkDto.builder()
                        .chunkId(chunk.getChunkId())
                        .documentId(chunk.getDocumentId())
                        .content(chunk.getContent())
                        .score(chunk.getScore())
                        .rerankScore(chunk.getRerankScore())
                        .metadata(chunk.getMetadataMap())
                        .citation(chunk.getCitation())
                        .build())
                .toList();

        return HybridSearchResponseDto.builder()
                .query(request.getQuery())
                .totalFound(response.getTotalFound())
                .results(chunkDtos)
                .build();
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> listDocuments(UserPrincipal principal) {
        return documentRepository.findByTenantIdOrderByCreatedAtDesc(principal.getTenantId())
                .stream()
                .map(DocumentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<DocumentResponse> listDocumentsPaged(UserPrincipal principal, Pageable pageable) {
        return documentRepository.findByTenantId(principal.getTenantId(), pageable)
                .map(DocumentResponse::from);
    }

    @Transactional
    public void deleteDocument(UserPrincipal principal, UUID documentId) {
        Document document = documentRepository.findByIdAndTenantId(documentId, principal.getTenantId())
                .orElseThrow(() -> new ApiException("Document not found or does not belong to current tenant", HttpStatus.NOT_FOUND));

        // Purge vectors from Qdrant via gRPC
        try {
            ragGrpcClient.deleteDocumentVectors(documentId, document.getVectorCollection());
            log.info("Vectors deleted for document {}", documentId);
        } catch (AiServiceUnavailableException e) {
            log.warn("AI Service offline during deleteDocumentVectors for {}. Proceeding with local removal: {}", documentId, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete vectors for document {}: {}", documentId, e.getMessage());
        }

        // Delete physical file from shared volume
        try {
            Path filePath = Paths.get(document.getFilePath());
            Files.deleteIfExists(filePath);
            log.info("Deleted physical file for document {} at {}", documentId, filePath);
        } catch (Exception e) {
            log.warn("Failed to delete physical file {}: {}", document.getFilePath(), e.getMessage());
        }

        // Remove from database
        documentRepository.delete(document);
        log.info("Deleted document entity {} from core.documents", documentId);
    }

    private void validateFileType(String filename, String contentType) {
        String lowerFilename = filename.toLowerCase();
        boolean validExtension = ALLOWED_EXTENSIONS.stream().anyMatch(lowerFilename::endsWith);
        boolean validMime = contentType != null && ALLOWED_MIME_TYPES.contains(contentType.toLowerCase());

        if (!validExtension && !validMime) {
            throw new ApiException("Invalid file type. Supported formats: PDF, TXT, MD, DOCX", HttpStatus.BAD_REQUEST);
        }
    }

    private String resolveMimeType(String filename, String rawMime) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return (rawMime != null && !rawMime.isBlank()) ? rawMime : "application/octet-stream";
    }
}
