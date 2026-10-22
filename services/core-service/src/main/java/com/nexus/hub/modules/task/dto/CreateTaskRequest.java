package com.nexus.hub.modules.task.dto;

import com.nexus.hub.modules.task.entity.TaskType;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTaskRequest {

    @Builder.Default
    private TaskType taskType = TaskType.DEEP_RESEARCH;

    @NotBlank(message = "Prompt cannot be blank")
    private String prompt;

    private List<UUID> documentIds;

    private Map<String, Object> metadata;
}
