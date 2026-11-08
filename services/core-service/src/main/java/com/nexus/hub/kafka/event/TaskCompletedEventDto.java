package com.nexus.hub.kafka.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskCompletedEventDto {

    private UUID taskId;
    private UUID tenantId;

    @JsonAlias({"status", "execution_status"})
    private String status;

    @JsonAlias({"report", "draft_report", "result"})
    private String report;

    private List<Object> citations;

    @JsonAlias({"totalDurationMs", "durationMs", "timestampMs"})
    private Long totalDurationMs;

    @JsonAlias({"errorDetails", "error", "message"})
    private String errorDetails;

    private String traceId;

    @Builder.Default
    private Instant timestamp = Instant.now();
}

