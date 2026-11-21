package com.nexus.hub.kafka.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.hub.config.RedisConfig;
import com.nexus.hub.grpc.client.RagGrpcClient;
import com.nexus.hub.kafka.event.DocumentIngestCompletedEventDto;
import com.nexus.hub.modules.task.entity.Document;
import com.nexus.hub.modules.task.entity.DocumentStatus;
import com.nexus.hub.modules.task.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.annotation.KafkaListener;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentIngestCompletedConsumerTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private RagGrpcClient ragGrpcClient;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private Cache documentsCache;

    private DocumentIngestCompletedConsumer consumer;
    private final UUID documentId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        consumer = new DocumentIngestCompletedConsumer(documentRepository, ragGrpcClient, cacheManager, new ObjectMapper());
    }

    private DocumentIngestCompletedEventDto event(String status, Integer chunks, String error) {
        return DocumentIngestCompletedEventDto.builder()
                .documentId(documentId).tenantId(tenantId).traceId("trace-9").status(status)
                .chunksCreated(chunks).vectorCollection("enterprise_docs").errorDetails(error)
                .durationMs(1234L).timestampMs(System.currentTimeMillis()).build();
    }

    @Test
    @DisplayName("Listener subscribes to document.ingest.completed with its own consumer group and the DLQ factory")
    void listenerConfiguration() throws Exception {
        KafkaListener listener = DocumentIngestCompletedConsumer.class
                .getMethod("onIngestCompleted", Object.class).getAnnotation(KafkaListener.class);
        assertThat(listener.topics()).containsExactly("${nexus.kafka.topics.document-ingest-completed:document.ingest.completed}");
        assertThat(listener.groupId()).isEqualTo("${nexus.kafka.consumer-groups.document-ingest:nexus-core-document-ingest}");
        assertThat(listener.containerFactory()).isEqualTo("kafkaListenerContainerFactory");
    }

    @Test
    @DisplayName("INDEXED: INDEXING row of the same tenant -> INDEXED + chunks, documents_cache evicted for the tenant")
    void indexedUpdatesAndEvicts() {
        when(documentRepository.transitionStatus(eq(documentId), eq(tenantId), eq(DocumentStatus.INDEXING),
                eq(DocumentStatus.INDEXED), eq(42), isNull(), any(Instant.class))).thenReturn(1);
        when(cacheManager.getCache(RedisConfig.DOCUMENTS_CACHE)).thenReturn(documentsCache);

        consumer.onIngestCompleted(event("INDEXED", 42, null));

        verify(documentsCache).evict(tenantId);
        verify(documentRepository, never()).findByIdAndTenantId(any(), any());
        verifyNoInteractions(ragGrpcClient);
    }

    @Test
    @DisplayName("FAILED: INDEXING -> FAILED with errorDetails and 0 chunks, cache evicted")
    void failedUpdatesWithErrorDetails() {
        when(documentRepository.transitionStatus(eq(documentId), eq(tenantId), eq(DocumentStatus.INDEXING),
                eq(DocumentStatus.FAILED), eq(0), eq("PDF parse error"), any(Instant.class))).thenReturn(1);
        when(cacheManager.getCache(RedisConfig.DOCUMENTS_CACHE)).thenReturn(documentsCache);

        consumer.onIngestCompleted(event("FAILED", 0, "PDF parse error"));

        verify(documentsCache).evict(tenantId);
        verifyNoInteractions(ragGrpcClient);
    }

    @Test
    @DisplayName("Raw JSON (Map from JsonDeserializer) with unknown extra fields is accepted")
    void unknownFieldsIgnored() {
        when(documentRepository.transitionStatus(eq(documentId), eq(tenantId), eq(DocumentStatus.INDEXING),
                eq(DocumentStatus.INDEXED), eq(3), isNull(), any(Instant.class))).thenReturn(1);
        when(cacheManager.getCache(RedisConfig.DOCUMENTS_CACHE)).thenReturn(documentsCache);

        Map<String, Object> raw = new java.util.HashMap<>();
        raw.put("documentId", documentId.toString());
        raw.put("tenantId", tenantId.toString());
        raw.put("traceId", "t");
        raw.put("status", "INDEXED");
        raw.put("chunksCreated", 3);
        raw.put("vectorCollection", "enterprise_docs");
        raw.put("durationMs", 10);
        raw.put("timestampMs", 1_763_600_000_000L);
        raw.put("someFutureField", Map.of("x", 1));

        consumer.onIngestCompleted(raw);

        verify(documentsCache).evict(tenantId);
    }

    @Test
    @DisplayName("Wrong tenant: row exists for another tenant -> nothing updated, no cache eviction, no vector deletion")
    void wrongTenantIgnored() {
        when(documentRepository.transitionStatus(any(), any(), any(), any(), anyInt(), any(), any())).thenReturn(0);
        when(documentRepository.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.empty());
        when(documentRepository.existsById(documentId)).thenReturn(true);

        consumer.onIngestCompleted(event("INDEXED", 5, null));

        verifyNoInteractions(ragGrpcClient, cacheManager);
    }

    @Test
    @DisplayName("Document deleted while INDEXING + INDEXED event -> orphan vectors purged with the event's tenant")
    void missingDocumentTriggersOrphanCleanup() {
        when(documentRepository.transitionStatus(any(), any(), any(), any(), anyInt(), any(), any())).thenReturn(0);
        when(documentRepository.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.empty());
        when(documentRepository.existsById(documentId)).thenReturn(false);

        consumer.onIngestCompleted(event("INDEXED", 5, null));

        verify(ragGrpcClient).deleteOrphanDocumentVectors(tenantId, documentId, "enterprise_docs", "trace-9");
        verifyNoInteractions(cacheManager);
    }

    @Test
    @DisplayName("Document deleted + FAILED event -> no orphan cleanup call")
    void missingDocumentFailedNoCleanup() {
        when(documentRepository.transitionStatus(any(), any(), any(), any(), anyInt(), any(), any())).thenReturn(0);
        when(documentRepository.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.empty());
        when(documentRepository.existsById(documentId)).thenReturn(false);

        consumer.onIngestCompleted(event("FAILED", 0, "boom"));

        verifyNoInteractions(ragGrpcClient, cacheManager);
    }

    @Test
    @DisplayName("Duplicate / out-of-order event after a terminal state is a no-op (idempotent)")
    void duplicateEventIsIdempotent() {
        Document indexed = Document.builder().id(documentId).status(DocumentStatus.INDEXED).chunksCount(42).build();
        when(documentRepository.transitionStatus(eq(documentId), eq(tenantId), eq(DocumentStatus.INDEXING),
                any(), anyInt(), any(), any(Instant.class)))
                .thenReturn(1)   // first delivery wins
                .thenReturn(0);  // redelivery / late FAILED finds the row no longer INDEXING
        when(cacheManager.getCache(RedisConfig.DOCUMENTS_CACHE)).thenReturn(documentsCache);
        when(documentRepository.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(indexed));

        consumer.onIngestCompleted(event("INDEXED", 42, null));
        consumer.onIngestCompleted(event("INDEXED", 42, null));
        consumer.onIngestCompleted(event("FAILED", 0, "late failure"));

        // Only the first delivery changed state; the row was never moved out of INDEXED.
        verify(documentsCache, times(1)).evict(tenantId);
        assertThat(indexed.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        verify(documentRepository, never()).save(any());
        verifyNoInteractions(ragGrpcClient);
    }

    @Test
    @DisplayName("Malformed events (missing ids / unknown status) are rejected for retry + DLQ")
    void malformedEventsRejected() {
        assertThatThrownBy(() -> consumer.onIngestCompleted(Map.of("status", "INDEXED")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.onIngestCompleted(event("DONE", 1, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.onIngestCompleted("{\"documentId\":\"not-a-uuid\",\"tenantId\":\"x\",\"status\":\"INDEXED\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(documentRepository, never()).transitionStatus(any(), any(), any(), any(), anyInt(), anyString(), any());
        verify(documentRepository, never()).transitionStatus(any(), any(), any(), any(), anyInt(), isNull(), any());
    }

    @Test
    @DisplayName("Null (tombstone) payload is ignored")
    void tombstoneIgnored() {
        consumer.onIngestCompleted(null);
        verifyNoInteractions(documentRepository, ragGrpcClient, cacheManager);
    }
}
