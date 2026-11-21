package com.nexus.hub.modules.task.controller;

import com.nexus.hub.common.dto.ApiResponse;
import com.nexus.hub.common.exception.ResourceNotFoundException;
import com.nexus.hub.modules.task.dto.CreateTaskRequest;
import com.nexus.hub.modules.task.dto.TaskPageResponse;
import com.nexus.hub.modules.task.dto.TaskResponse;
import com.nexus.hub.modules.task.entity.TaskStatus;
import com.nexus.hub.modules.task.service.TaskService;
import com.nexus.hub.modules.task.service.TaskStreamHub;
import com.nexus.hub.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskStreamHub taskStreamHub;

    @PostMapping
    public ResponseEntity<ApiResponse<TaskResponse>> submitTask(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateTaskRequest request
    ) {
        TaskResponse response = taskService.createAndDispatchTask(principal, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(response, "Task accepted and queued for autonomous agent execution"));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<TaskResponse>> getTask(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID taskId
    ) {
        TaskResponse response = loadOwnedTask(principal, taskId);
        return ResponseEntity.ok(ApiResponse.success(response, "Task retrieved successfully"));
    }

    @GetMapping(value = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTask(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID taskId
    ) {
        // Enforce strict multi-tenant validation BEFORE registering the SSE emitter
        TaskResponse task = loadOwnedTask(principal, taskId);
        SseEmitter emitter = taskStreamHub.registerClient(taskId, 300_000L);
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.FAILED) {
            taskStreamHub.broadcastEvent(taskId, "TASK_COMPLETED", task);
            taskStreamHub.completeStream(taskId);
        }
        return emitter;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<TaskPageResponse>> listTasks(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction
    ) {
        Sort sort = direction.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        PageRequest pageRequest = PageRequest.of(page, size, sort);
        TaskPageResponse response = taskService.listTasks(principal, pageRequest);
        return ResponseEntity.ok(ApiResponse.success(response, "Tasks retrieved successfully"));
    }

    /**
     * Ownership check that runs on every request, independent of any caching inside TaskService:
     * the returned DTO must belong to the caller's tenant, otherwise respond 404 (no existence leak).
     */
    private TaskResponse loadOwnedTask(UserPrincipal principal, UUID taskId) {
        if (principal == null || principal.getTenantId() == null) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required");
        }
        TaskResponse task = taskService.getTaskById(principal, taskId);
        if (task == null || !principal.getTenantId().equals(task.getTenantId())) {
            log.warn("Blocked cross-tenant task access: task={}, callerTenant={}", taskId, principal.getTenantId());
            throw new ResourceNotFoundException("Task not found with ID: " + taskId);
        }
        return task;
    }
}
