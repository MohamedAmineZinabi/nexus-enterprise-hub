package com.nexus.hub.modules.document.service;

import com.nexus.hub.common.exception.ApiException;
import com.nexus.hub.common.exception.AiServiceUnavailableException;
import com.nexus.hub.config.RedisConfig;
import com.nexus.hub.grpc.ai.v1.HybridSearchResponse;
import com.nexus.hub.grpc.client.RagGrpcClient;
import com.nexus.hub.kafka.event.DocumentIngestRequestedEvent;
import com.nexus.hub.modules.document.dto.DocumentResponse;
import com.nexus.hub.modules.document.dto.DocumentUploadResponse;
import com.nexus.hub.modules.document.dto.HybridSearchRequestDto;
import com.nexus.hub.modules.document.dto.HybridSearchResponseDto;
import com.nexus.hub.modules.document.event.DocumentUploadedEvent;
import com.nexus.hub.modules.task.entity.Document;
import com.nexus.hub.modules.task.entity.DocumentStatus;
import com.nexus.hub.modules.task.repository.DocumentRepository;
import com.nexus.hub.modules.user.entity.User;
import com.nexus.hub.modules.user.repository.UserRepository;
import com.nexus.hub.security.UserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class DocumentService {

    public static final String DEFAULT_COLLECTION = "enterprise_docs";

    /** Upper bound for persisted error_details text. */
    static final int MAX_ERROR_DETAILS_LENGTH = 2000;

    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final RagGrpcClient ragGrpcClient;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final String sharedUploadsDir;
    private final Set<String> allowedCollections;

    public DocumentService(
            DocumentRepository documentRepository,
            UserRepository userRepository,
            RagGrpcClient ragGrpcClient,
            ApplicationEventPublisher eventPublisher,
            TransactionOperations transactionOperations,
            @Value("${nexus.storage.shared-uploads-dir:${nexus.storage.upload-dir:./data/shared-uploads}}") String sharedUploadsDir,
            @Value("${nexus.rag.allowed-collections:" + DEFAULT_COLLECTION + "}") String allowedCollections) {
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.ragGrpcClient = ragGrpcClient;
        this.eventPublisher = eventPublisher;
        this.transactionOperations = transactionOperations;
        this.sharedUploadsDir = sharedUploadsDir;
        this.allowedCollections = Arrays.stream(Objects.requireNonNullElse(allowedCollections, DEFAULT_COLLECTION).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Resolves the vector collection for a request: blank -> enterprise_docs, otherwise it must be
     * in the nexus.rag.allowed-collections allowlist (400 otherwise).
     */
    String resolveCollection(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_COLLECTION;
        }
        String trimmed = requested.trim();
        if (!allowedCollections.contains(trimmed)) {
            throw new ApiException("Requested vector collection is not allowed", HttpStatus.BAD_REQUEST);
        }
        return trimmed;
    }

    /**
     * Asynchronous ingestion: validates and stores the file, persists the row as INDEXING (short DB
     * transaction, no network I/O inside it) and publishes a {@link DocumentUploadedEvent}. The Kafka
     * message document.ingest.requested is sent by {@link DocumentIngestDispatchListener} only after
     * the commit; ai-service reports the outcome on document.ingest.completed.
     */
    @CacheEvict(value = RedisConfig.DOCUMENTS_CACHE, key = "#principal.tenantId")
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
        validateFileType(originalFilename, file);
        String collection = resolveCollection(vectorCollection);

        // Single id used for: disk filename, DB primary key, Kafka message key/payload, API response, and vector deletion.
        UUID documentId = UUID.randomUUID();
        UUID tenantId = principal.getTenantId();
        String sanitizedName = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
        String storedFileName = documentId + "_" + sanitizedName;

        Path tenantDirectory = Paths.get(sharedUploadsDir, tenantId.toString());
        Path targetFilePath = tenantDirectory.resolve(storedFileName);

        try {
            Files.createDirectories(tenantDirectory);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, targetFilePath, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Persisted uploaded file for tenant {} at {}", tenantId, targetFilePath);
        } catch (IOException e) {
            log.error("Failed to store file on shared disk: {}", e.getMessage(), e);
            throw new ApiException("Failed to save uploaded file to storage", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // Absolute path as seen inside the containers (both mount the volume at nexus.storage.shared-uploads-dir)
        String normalizedFilePath = targetFilePath.toAbsolutePath().toString().replace('\\', '/');
        String mimeType = resolveMimeType(originalFilename, file.getContentType());

        Map<String, String> metadata = new HashMap<>();
        metadata.put("tenant_id", tenantId.toString());
        metadata.put("filename", originalFilename);
        metadata.put("user_id", principal.getId().toString());

        DocumentIngestRequestedEvent payload = DocumentIngestRequestedEvent.builder()
                .documentId(documentId.toString())
                .tenantId(tenantId.toString())
                .userId(principal.getId().toString())
                .traceId(resolveTraceId())
                .filePath(normalizedFilePath)
                .fileName(sanitizedName)
                .mimeType(mimeType)
                .chunkSize(chunkSize != null && chunkSize > 0 ? chunkSize : null)
                .chunkOverlap(chunkOverlap != null && chunkOverlap >= 0 ? chunkOverlap : null)
                .vectorCollection(collection)
                .metadata(metadata)
                .requestedAt(Instant.now().toString())
                .build();

        Document document;
        try {
            // Short transaction: insert + event registration only. The AFTER_COMMIT listener sends to Kafka.
            document = transactionOperations.execute(status -> {
                User user = userRepository.findById(principal.getId())
                        .orElseThrow(() -> new ApiException("User context not found", HttpStatus.UNAUTHORIZED));

                Document toSave = Document.builder()
                        .id(documentId)
                        .tenant(user.getTenant())
                        .user(user)
                        .filename(originalFilename)
                        .filePath(normalizedFilePath)
                        .fileSize(file.getSize())
                        .mimeType(mimeType)
                        .vectorCollection(collection)
                        .status(DocumentStatus.INDEXING)
                        .chunksCount(0)
                        .build();

                // Document implements Persistable (isNew=true) so this is a persist() with the assigned id, not a merge()
                Document saved = documentRepository.save(toSave);
                if (!documentId.equals(saved.getId())) {
                    throw new IllegalStateException("Persisted document id does not match the assigned id");
                }

                eventPublisher.publishEvent(new DocumentUploadedEvent(documentId, tenantId, payload));
                return saved;
            });
        } catch (RuntimeException e) {
            // Row was not committed: do not leave an orphan file on the shared volume
            deleteFileQuietly(targetFilePath);
            throw e;
        }

        log.info("Document {} accepted for asynchronous ingestion into '{}' (tenant {})", documentId, collection, tenantId);

        return DocumentUploadResponse.builder()
                .documentId(documentId)
                .fileName(document.getFilename())
                .status(DocumentStatus.INDEXING)
                .chunksCreated(0)
                .vectorCollection(document.getVectorCollection())
                .createdAt(document.getCreatedAt())
                .message("Document uploaded. Indexing is in progress; poll the document list for its status.")
                .build();
    }

    /**
     * Marks an INDEXING document FAILED because document.ingest.requested could not be delivered.
     * Runs in its own transaction because it is invoked from an AFTER_COMMIT listener, where the
     * original transaction has already completed. Only an INDEXING row of the same tenant is changed.
     */
    @CacheEvict(value = RedisConfig.DOCUMENTS_CACHE, key = "#tenantId")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIngestDispatchFailed(UUID documentId, UUID tenantId, String errorDetails) {
        int updated = documentRepository.transitionStatus(documentId, tenantId,
                DocumentStatus.INDEXING, DocumentStatus.FAILED, 0, truncateErrorDetails(errorDetails), Instant.now());
        if (updated == 0) {
            log.warn("Not marking document {} FAILED after dispatch error: row missing or no longer INDEXING", documentId);
        } else {
            log.error("Document {} (tenant {}) marked FAILED: {}", documentId, tenantId, errorDetails);
        }
    }

    static String truncateErrorDetails(String errorDetails) {
        if (errorDetails == null) {
            return null;
        }
        return errorDetails.length() > MAX_ERROR_DETAILS_LENGTH
                ? errorDetails.substring(0, MAX_ERROR_DETAILS_LENGTH)
                : errorDetails;
    }

    @Transactional(readOnly = true)
    public HybridSearchResponseDto searchDocuments(UserPrincipal principal, HybridSearchRequestDto request) {
        log.info("Executing hybrid search for tenant: {}, query: '{}'", principal.getTenantId(), request.getQuery());
        String collection = resolveCollection(request.getCollectionName());

        HybridSearchResponse response = ragGrpcClient.hybridSearch(
                request.getQuery(),
                request.getTopK() != null ? request.getTopK() : 5,
                request.getAlpha() != null ? request.getAlpha() : 0.7f,
                request.getRerank() == null || request.getRerank(),
                collection,
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

    /**
     * Tenant document list. Never cached while any document is still INDEXING: a list read that
     * starts before the ingest-completed update commits could otherwise re-populate the cache with
     * the stale INDEXING status right after DocumentIngestCompletedConsumer evicted it (the UI polls
     * this endpoint during indexing, so the read is cheap and must be fresh).
     */
    @Cacheable(
            value = RedisConfig.DOCUMENTS_CACHE,
            key = "#principal.tenantId",
            unless = "#result.?[status == T(com.nexus.hub.modules.task.entity.DocumentStatus).INDEXING].size() > 0"
    )
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

    /**
     * Deletes vectors, file and row regardless of status. For an INDEXING document this races with
     * ai-service: it re-checks the file after upsert (and purges), and core-service's
     * DocumentIngestCompletedConsumer purges vectors of documents that no longer exist.
     */
    @CacheEvict(value = RedisConfig.DOCUMENTS_CACHE, key = "#principal.tenantId")
    @Transactional
    public void deleteDocument(UserPrincipal principal, UUID documentId) {
        Document document = documentRepository.findByIdAndTenantId(documentId, principal.getTenantId())
                .orElseThrow(() -> new ApiException("Document not found or does not belong to current tenant", HttpStatus.NOT_FOUND));

        if (document.getStatus() == DocumentStatus.INDEXING) {
            log.info("Deleting document {} while ingestion is still in progress", documentId);
        }

        // Purge vectors from Qdrant via gRPC, using the persisted id (identical to the ingested id)
        UUID persistedId = document.getId();
        try {
            ragGrpcClient.deleteDocumentVectors(persistedId, document.getVectorCollection());
            log.info("Vectors deleted for document {}", persistedId);
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

    /**
     * Requires an allowed extension AND content (magic bytes / UTF-8) consistent with it.
     * See {@link DocumentFileValidator}.
     */
    private void validateFileType(String filename, MultipartFile file) {
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(DocumentFileValidator.HEAD_BYTES);
        } catch (IOException e) {
            log.warn("Unable to read uploaded file for validation: {}", e.getMessage());
            throw new ApiException("Unable to read uploaded file", HttpStatus.BAD_REQUEST);
        }
        boolean completeFile = file.getSize() <= head.length;
        DocumentFileValidator.validate(filename, file.getContentType(), head, completeFile);
    }

    private void deleteFileQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to remove uploaded file {} after persistence failure: {}", path, e.getMessage());
        }
    }

    private static String resolveTraceId() {
        String traceId = MDC.get("traceId");
        return (traceId != null && !traceId.isBlank()) ? traceId : "trace-" + UUID.randomUUID();
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
