package com.nexus.hub.modules.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.hub.kafka.event.DocumentIngestRequestedEvent;
import com.nexus.hub.kafka.producer.DocumentIngestProducer;
import com.nexus.hub.modules.document.event.DocumentUploadedEvent;
import com.nexus.hub.modules.document.service.DocumentIngestDispatchListener;
import com.nexus.hub.modules.document.service.DocumentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentIngestDispatchAfterCommitTest {

    @Mock
    private DocumentIngestProducer producer;
    @Mock
    private DocumentService documentService;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private final UUID documentId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    private DocumentIngestRequestedEvent payload() {
        return DocumentIngestRequestedEvent.builder()
                .documentId(documentId.toString())
                .tenantId(tenantId.toString())
                .userId(UUID.randomUUID().toString())
                .traceId("trace-1")
                .filePath("/shared-uploads/" + tenantId + "/" + documentId + "_a.pdf")
                .fileName("a.pdf")
                .mimeType("application/pdf")
                .chunkSize(null)
                .chunkOverlap(50)
                .vectorCollection("enterprise_docs")
                .metadata(Map.of("filename", "a.pdf"))
                .requestedAt("2026-11-20T10:15:30Z")
                .build();
    }

    @Test
    @DisplayName("Listener runs AFTER_COMMIT")
    void listenerIsAfterCommit() throws Exception {
        TransactionalEventListener listener = DocumentIngestDispatchListener.class
                .getMethod("onDocumentUploaded", DocumentUploadedEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("Successful Kafka ack does not mark the document failed")
    void successfulSend() {
        DocumentIngestDispatchListener listener = new DocumentIngestDispatchListener(producer, documentService, 1000);
        DocumentIngestRequestedEvent payload = payload();
        @SuppressWarnings("unchecked")
        SendResult<String, Object> result = mock(SendResult.class);
        when(producer.publishIngestRequested(payload)).thenReturn(CompletableFuture.completedFuture(result));

        listener.onDocumentUploaded(new DocumentUploadedEvent(documentId, tenantId, payload));

        verify(documentService, never()).markIngestDispatchFailed(any(), any(), anyString());
    }

    @Test
    @DisplayName("Failed Kafka send marks the document FAILED via the REQUIRES_NEW handler")
    void failedSendMarksFailed() {
        DocumentIngestDispatchListener listener = new DocumentIngestDispatchListener(producer, documentService, 1000);
        DocumentIngestRequestedEvent payload = payload();
        when(producer.publishIngestRequested(payload))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        listener.onDocumentUploaded(new DocumentUploadedEvent(documentId, tenantId, payload));

        verify(documentService).markIngestDispatchFailed(eq(documentId), eq(tenantId), contains("Kafka publish failed"));
    }

    @Test
    @DisplayName("Synchronous send exception marks the document FAILED")
    void synchronousSendExceptionMarksFailed() {
        DocumentIngestDispatchListener listener = new DocumentIngestDispatchListener(producer, documentService, 1000);
        DocumentIngestRequestedEvent payload = payload();
        when(producer.publishIngestRequested(payload)).thenThrow(new IllegalStateException("metadata timeout"));

        listener.onDocumentUploaded(new DocumentUploadedEvent(documentId, tenantId, payload));

        verify(documentService).markIngestDispatchFailed(eq(documentId), eq(tenantId), contains("IllegalStateException"));
    }

    @Test
    @DisplayName("Kafka ack timeout marks the document FAILED")
    void timeoutMarksFailed() {
        DocumentIngestDispatchListener listener = new DocumentIngestDispatchListener(producer, documentService, 50);
        DocumentIngestRequestedEvent payload = payload();
        when(producer.publishIngestRequested(payload)).thenReturn(new CompletableFuture<>());

        listener.onDocumentUploaded(new DocumentUploadedEvent(documentId, tenantId, payload));

        verify(documentService).markIngestDispatchFailed(eq(documentId), eq(tenantId), contains("Timed out"));
    }

    @Test
    @DisplayName("Producer sends to document.ingest.requested keyed by documentId")
    void producerUsesTopicAndDocumentIdKey() {
        DocumentIngestProducer realProducer = new DocumentIngestProducer(kafkaTemplate, "document.ingest.requested");
        DocumentIngestRequestedEvent payload = payload();
        when(kafkaTemplate.send("document.ingest.requested", documentId.toString(), payload))
                .thenReturn(new CompletableFuture<>());

        realProducer.publishIngestRequested(payload);

        verify(kafkaTemplate).send("document.ingest.requested", documentId.toString(), payload);
    }

    @Test
    @DisplayName("Wire format matches the cross-service contract (camelCase, explicit nulls, string ids/time)")
    void wireFormatMatchesContract() throws Exception {
        // Same serializer configuration as KafkaProducerConfig
        try (JsonSerializer<Object> serializer = new JsonSerializer<>()) {
            serializer.configure(Map.of(JsonSerializer.ADD_TYPE_INFO_HEADERS, false), false);
            byte[] bytes = serializer.serialize("document.ingest.requested", payload());
            JsonNode json = new ObjectMapper().readTree(new String(bytes, StandardCharsets.UTF_8));

            List<String> fields = iteratorToList(json.fieldNames());
            assertThat(fields).containsExactly("documentId", "tenantId", "userId", "traceId", "filePath", "fileName",
                    "mimeType", "chunkSize", "chunkOverlap", "vectorCollection", "metadata", "requestedAt");
            assertThat(json.get("documentId").asText()).isEqualTo(documentId.toString());
            assertThat(json.get("chunkSize").isNull()).isTrue();
            assertThat(json.get("chunkOverlap").asInt()).isEqualTo(50);
            assertThat(json.get("metadata").get("filename").asText()).isEqualTo("a.pdf");
            assertThat(json.get("requestedAt").asText()).isEqualTo("2026-11-20T10:15:30Z");
            assertThat(json.get("filePath").asText()).isEqualTo("/shared-uploads/" + tenantId + "/" + documentId + "_a.pdf");
        }
    }

    private static List<String> iteratorToList(java.util.Iterator<String> it) {
        List<String> out = new java.util.ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }
}
