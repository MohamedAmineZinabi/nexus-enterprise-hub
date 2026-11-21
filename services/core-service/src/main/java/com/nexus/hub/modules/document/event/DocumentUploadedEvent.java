package com.nexus.hub.modules.document.event;

import com.nexus.hub.kafka.event.DocumentIngestRequestedEvent;

import java.util.UUID;

/**
 * In-process Spring application event published inside the document-persist transaction. It is
 * handled only AFTER the transaction commits (see DocumentIngestDispatchListener), so ai-service
 * never receives a document whose row was rolled back or is not yet visible.
 *
 * @param documentId persisted document id
 * @param tenantId   owning tenant id
 * @param payload    the Kafka message for topic document.ingest.requested
 */
public record DocumentUploadedEvent(UUID documentId, UUID tenantId, DocumentIngestRequestedEvent payload) {
}
