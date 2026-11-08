package com.nexus.hub.kafka.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskProgressEventDto {

    private UUID taskId;
    private UUID tenantId;

    @JsonAlias({"nodeName", "nodeType"})
    private String nodeName;

    @JsonAlias({"status", "phase"})
    private String status;

    @JsonAlias({"durationMs", "timestampMs"})
    private Long durationMs;

    private Map<String, Object> payload;

    private String message;
    private String traceId;

    @Builder.Default
    private Instant timestamp = Instant.now();
}

