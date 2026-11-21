package com.nexus.hub.kafka.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Kafka payload of topic {@code document.ingest.completed} (key = documentId), produced by ai-service.
 * Unknown extra fields are ignored.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentIngestCompletedEventDto {

    public static final String STATUS_INDEXED = "INDEXED";
    public static final String STATUS_FAILED = "FAILED";

    private UUID documentId;
    private UUID tenantId;
    private String traceId;
    /** "INDEXED" or "FAILED". */
    private String status;
    private Integer chunksCreated;
    private String vectorCollection;
    /** Present only when status is FAILED. */
    private String errorDetails;
    /** Elapsed ingestion time in milliseconds. */
    private Long durationMs;
    /** Epoch-millis emission time. */
    private Long timestampMs;
}
