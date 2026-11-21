package com.nexus.hub.kafka.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.hub.config.RedisConfig;
import com.nexus.hub.grpc.client.RagGrpcClient;
import com.nexus.hub.kafka.event.DocumentIngestCompletedEventDto;
import com.nexus.hub.modules.document.service.DocumentService;
import com.nexus.hub.modules.task.entity.Document;
import com.nexus.hub.modules.task.entity.DocumentStatus;
import com.nexus.hub.modules.task.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies ai-service ingestion outcomes (topic document.ingest.completed) to core.documents.
 * <ul>
 *   <li>The row is only changed if it exists, belongs to the event's tenant AND is still INDEXING
 *       (single conditional UPDATE), so duplicate / out-of-order / foreign-tenant events are no-ops.</li>
 *   <li>If the document was deleted while it was being indexed and ai-service reports INDEXED, its
 *       vectors are purged (orphan cleanup) with a system TenantContext for the event's tenant.</li>
 * </ul>
 * Deliberately NOT @Transactional: the status update commits in its own short transaction and the
 * gRPC cleanup call runs without holding a DB transaction. Malformed events are retried and then
 * routed to the DLQ by the shared kafkaListenerContainerFactory error handler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentIngestCompletedConsumer {

    static final String DEFAULT_FAILURE_MESSAGE = "Ingestion failed in ai-service";

    private final DocumentRepository documentRepository;
    private final RagGrpcClient ragGrpcClient;
    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${nexus.kafka.topics.document-ingest-completed:document.ingest.completed}",
            groupId = "${nexus.kafka.consumer-groups.document-ingest:nexus-core-document-ingest}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onIngestCompleted(@Payload(required = false) Object rawEvent) {
        if (rawEvent == null) {
            log.debug("Ignoring tombstone null document ingestion event");
            return;
        }
        DocumentIngestCompletedEventDto event = parseEvent(rawEvent);
        if (event == null || event.getDocumentId() == null || event.getTenantId() == null || event.getStatus() == null) {
            throw new IllegalArgumentException("Malformed document ingestion event: documentId, tenantId and status are required");
        }

        String status = event.getStatus().trim().toUpperCase();
        boolean indexed;
        if (DocumentIngestCompletedEventDto.STATUS_INDEXED.equals(status)) {
            indexed = true;
        } else if (DocumentIngestCompletedEventDto.STATUS_FAILED.equals(status)) {
            indexed = false;
        } else {
            throw new IllegalArgumentException("Malformed document ingestion event: unknown status '" + event.getStatus() + "'");
        }

        UUID documentId = event.getDocumentId();
        UUID tenantId = event.getTenantId();

        int chunks = indexed && event.getChunksCreated() != null ? Math.max(0, event.getChunksCreated()) : 0;
        String errorDetails = indexed ? null : truncate(
                event.getErrorDetails() != null && !event.getErrorDetails().isBlank()
                        ? event.getErrorDetails()
                        : DEFAULT_FAILURE_MESSAGE);

        int updated = documentRepository.transitionStatus(documentId, tenantId, DocumentStatus.INDEXING,
                indexed ? DocumentStatus.INDEXED : DocumentStatus.FAILED, chunks, errorDetails, Instant.now());

        if (updated > 0) {
            evictDocumentsCache(tenantId);
            log.info("[Ingest Consumer] Document {} (tenant {}) -> {} (chunks={}, durationMs={}, traceId={})",
                    documentId, tenantId, status, chunks, event.getDurationMs(), event.getTraceId());
            return;
        }

        // Nothing updated: duplicate/out-of-order, foreign tenant, or the document was deleted.
        Optional<Document> sameTenant = documentRepository.findByIdAndTenantId(documentId, tenantId);
        if (sameTenant.isPresent()) {
            log.info("[Ingest Consumer] Ignoring {} event for document {}: current status is {} (duplicate or out-of-order)",
                    status, documentId, sameTenant.get().getStatus());
            return;
        }
        if (documentRepository.existsById(documentId)) {
            log.warn("[Ingest Consumer] Ignoring {} event for document {}: it does not belong to tenant {}",
                    status, documentId, tenantId);
            return;
        }

        if (!indexed) {
            log.info("[Ingest Consumer] Document {} no longer exists; FAILED outcome needs no cleanup", documentId);
            return;
        }

        // Deleted while indexing -> purge vectors that ai-service may have written after our delete.
        String collection = event.getVectorCollection() != null && !event.getVectorCollection().isBlank()
                ? event.getVectorCollection()
                : DocumentService.DEFAULT_COLLECTION;
        log.warn("[Ingest Consumer] Document {} (tenant {}) was deleted during indexing; purging orphan vectors from '{}'",
                documentId, tenantId, collection);
        ragGrpcClient.deleteOrphanDocumentVectors(tenantId, documentId, collection, event.getTraceId());
    }

    /** Same cache and key as DocumentService (@Cacheable(value = "documents_cache", key = "#principal.tenantId")). */
    private void evictDocumentsCache(UUID tenantId) {
        try {
            Cache cache = cacheManager.getCache(RedisConfig.DOCUMENTS_CACHE);
            if (cache != null) {
                cache.evict(tenantId);
            }
        } catch (Exception e) {
            log.warn("Failed to evict documents_cache entry for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    private static String truncate(String value) {
        int max = 2000;
        return value.length() > max ? value.substring(0, max) : value;
    }

    private DocumentIngestCompletedEventDto parseEvent(Object raw) {
        if (raw instanceof ConsumerRecord<?, ?> record) {
            raw = record.value();
            if (raw == null) {
                return null;
            }
        }
        if (raw instanceof DocumentIngestCompletedEventDto dto) {
            return dto;
        }
        try {
            if (raw instanceof String str) {
                return objectMapper.readValue(str, DocumentIngestCompletedEventDto.class);
            }
            return objectMapper.convertValue(raw, DocumentIngestCompletedEventDto.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot deserialize DocumentIngestCompletedEventDto: " + e.getMessage(), e);
        }
    }
}
