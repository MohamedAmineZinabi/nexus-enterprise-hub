package com.nexus.hub.kafka.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Kafka payload for topic {@code document.ingest.requested} (key = documentId), consumed by ai-service.
 * Contract is camelCase JSON; chunkSize / chunkOverlap are serialized as {@code null} when not
 * provided (ai-service applies its defaults). requestedAt is an ISO-8601 string.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"documentId", "tenantId", "userId", "traceId", "filePath", "fileName", "mimeType",
        "chunkSize", "chunkOverlap", "vectorCollection", "metadata", "requestedAt"})
public class DocumentIngestRequestedEvent {

    private String documentId;
    private String tenantId;
    private String userId;
    private String traceId;
    /** Absolute path inside the containers: /shared-uploads/{tenantId}/{documentId}_{fileName}. */
    private String filePath;
    /** Sanitized original file name (the part after "{documentId}_" in filePath). */
    private String fileName;
    private String mimeType;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private String vectorCollection;
    private Map<String, String> metadata;
    /** ISO-8601 instant, e.g. 2026-11-20T10:15:30.123Z. */
    private String requestedAt;
}
