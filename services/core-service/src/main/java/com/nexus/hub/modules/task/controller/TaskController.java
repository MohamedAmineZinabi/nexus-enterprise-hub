package com.nexus.hub.modules.task.controller;

import com.nexus.hub.common.dto.ApiResponse;
import com.nexus.hub.modules.task.dto.CreateTaskRequest;
import com.nexus.hub.modules.task.dto.TaskPageResponse;
import com.nexus.hub.modules.task.dto.TaskResponse;
import com.nexus.hub.modules.task.service.TaskService;
import com.nexus.hub.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

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
        TaskResponse response = taskService.getTaskById(principal, taskId);
        return ResponseEntity.ok(ApiResponse.success(response, "Task retrieved successfully"));
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
}
