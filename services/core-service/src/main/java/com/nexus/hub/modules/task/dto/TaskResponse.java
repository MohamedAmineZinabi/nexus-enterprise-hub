package com.nexus.hub.modules.task.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexus.hub.modules.task.entity.AgentTask;
import com.nexus.hub.modules.task.entity.TaskStatus;
import com.nexus.hub.modules.task.entity.TaskType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskResponse {

    private UUID taskId;
    private UUID tenantId;
    private UUID userId;
    private TaskType taskType;
    private TaskStatus status;
    private String prompt;
    private String resultSummary;
    private Long totalTokensUsed;
    private String traceId;
    private Instant createdAt;
    private Instant updatedAt;

    public static TaskResponse fromEntity(AgentTask entity, String traceId) {
        return TaskResponse.builder()
                .taskId(entity.getId())
                .tenantId(entity.getTenant().getId())
                .userId(entity.getUser().getId())
                .taskType(entity.getTaskType())
                .status(entity.getStatus())
                .prompt(entity.getPrompt())
                .resultSummary(entity.getResultSummary())
                .totalTokensUsed(entity.getTotalTokensUsed())
                .traceId(traceId)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static TaskResponse fromEntity(AgentTask entity) {
        return fromEntity(entity, null);
    }
}
