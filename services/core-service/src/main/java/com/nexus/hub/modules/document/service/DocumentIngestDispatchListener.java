package com.nexus.hub.modules.document.service;

import com.nexus.hub.kafka.producer.DocumentIngestProducer;
import com.nexus.hub.modules.document.event.DocumentUploadedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes document.ingest.requested only after the document row has been committed, and waits
 * for the broker acknowledgement. If the send fails or times out, the document is marked FAILED in
 * a new transaction so it never stays INDEXING forever.
 */
@Slf4j
@Component
public class DocumentIngestDispatchListener {

    private final DocumentIngestProducer documentIngestProducer;
    private final DocumentService documentService;
    private final long sendTimeoutMs;

    public DocumentIngestDispatchListener(
            DocumentIngestProducer documentIngestProducer,
            DocumentService documentService,
            @Value("${nexus.kafka.producer.send-timeout-ms:15000}") long sendTimeoutMs) {
        this.documentIngestProducer = documentIngestProducer;
        this.documentService = documentService;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        try {
            documentIngestProducer.publishIngestRequested(event.payload()).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("Document {} ingestion request dispatched to Kafka after commit", event.documentId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleFailure(event, "Interrupted while publishing ingestion request");
        } catch (TimeoutException e) {
            handleFailure(event, "Timed out after " + sendTimeoutMs + "ms waiting for Kafka acknowledgement");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            handleFailure(event, "Kafka publish failed: " + cause.getClass().getSimpleName());
            log.debug("Kafka publish failure detail for document {}", event.documentId(), cause);
        } catch (RuntimeException e) {
            // e.g. KafkaException thrown synchronously by send() (serialization, metadata timeout)
            handleFailure(event, "Kafka publish failed: " + e.getClass().getSimpleName());
            log.debug("Kafka publish failure detail for document {}", event.documentId(), e);
        }
    }

    private void handleFailure(DocumentUploadedEvent event, String errorDetails) {
        log.error("Failed to dispatch ingestion of document {} (tenant {}): {}",
                event.documentId(), event.tenantId(), errorDetails);
        try {
            documentService.markIngestDispatchFailed(event.documentId(), event.tenantId(),
                    "Ingestion dispatch failed: " + errorDetails);
        } catch (Exception ex) {
            log.error("Could not mark document {} as FAILED after dispatch error: {}",
                    event.documentId(), ex.getMessage(), ex);
        }
    }
}
