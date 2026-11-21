package com.nexus.hub.modules.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.hub.common.exception.ResourceNotFoundException;
import com.nexus.hub.config.RedisConfig;
import com.nexus.hub.kafka.event.AgentTaskRequestedEvent;
import com.nexus.hub.modules.task.dto.CreateTaskRequest;
import com.nexus.hub.modules.task.dto.TaskPageResponse;
import com.nexus.hub.modules.task.dto.TaskResponse;
import com.nexus.hub.modules.task.entity.AgentTask;
import com.nexus.hub.modules.task.entity.TaskAuditEvent;
import com.nexus.hub.modules.task.entity.TaskStatus;
import com.nexus.hub.modules.task.event.TaskCreatedEvent;
import com.nexus.hub.modules.task.repository.AgentTaskRepository;
import com.nexus.hub.modules.task.repository.TaskAuditEventRepository;
import com.nexus.hub.modules.tenant.entity.Tenant;
import com.nexus.hub.modules.tenant.repository.TenantRepository;
import com.nexus.hub.modules.user.entity.User;
import com.nexus.hub.modules.user.repository.UserRepository;
import com.nexus.hub.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final AgentTaskRepository taskRepository;
    private final TaskAuditEventRepository auditEventRepository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * Single source of truth for tasks_cache keys: "{tenantId}:{taskId}".
     * The tenant component always comes from the authenticated principal (reads) or the
     * persisted task entity (evictions) — never from request input.
     */
    public static String cacheKey(UUID tenantId, UUID taskId) {
        return tenantId + ":" + taskId;
    }

    @Transactional
    public TaskResponse createAndDispatchTask(UserPrincipal principal, CreateTaskRequest request) {
        Tenant tenant = tenantRepository.findById(principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + principal.getTenantId()));

        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + principal.getId()));

        String metadataJson = null;
        if (request.getMetadata() != null && !request.getMetadata().isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(request.getMetadata());
            } catch (JsonProcessingException e) {
                log.warn("Could not serialize task metadata to JSON: {}", e.getMessage());
            }
        }

        // 1. Persist initial task in state PENDING
        AgentTask task = AgentTask.builder()
                .tenant(tenant)
                .user(user)
                .taskType(request.getTaskType())
                .prompt(request.getPrompt())
                .status(TaskStatus.PENDING)
                .metadata(metadataJson)
                .totalTokensUsed(0L)
                .build();

        task = taskRepository.save(task);

        String traceId = "trace-" + UUID.randomUUID();

        // 2. Persist audit telemetry event
        TaskAuditEvent auditEvent = TaskAuditEvent.builder()
                .task(task)
                .eventType("TASK_REQUESTED")
                .nodeType("INGRESS")
                .message("Task initialized and queued for multi-agent execution")
                .payload(metadataJson)
                .build();
        auditEventRepository.save(auditEvent);

        // 3. Build the (unchanged) Kafka payload and hand it to the AFTER_COMMIT dispatcher.
        //    Kafka is NOT called inside this transaction (see TaskDispatchListener).
        AgentTaskRequestedEvent event = AgentTaskRequestedEvent.builder()
                .taskId(task.getId())
                .tenantId(tenant.getId())
                .userId(user.getId())
                .taskType(task.getTaskType())
                .prompt(task.getPrompt())
                .documentIds(request.getDocumentIds())
                .metadata(request.getMetadata())
                .timestamp(Instant.now())
                .traceId(traceId)
                .build();

        eventPublisher.publishEvent(new TaskCreatedEvent(task.getId(), tenant.getId(), event));

        log.info("Task created (dispatch scheduled after commit): taskId='{}', tenantId='{}', type='{}'",
                task.getId(), tenant.getId(), task.getTaskType());

        return TaskResponse.fromEntity(task, traceId);
    }

    /**
     * Tenant-scoped task lookup. The cache key includes the caller's tenant id (taken from the
     * authenticated principal), so a cache entry populated for tenant A can never be served to
     * tenant B; on a miss the DB ownership check below always runs. Callers must additionally
     * verify {@link TaskResponse#getTenantId()} (see TaskController) as defence in depth.
     */
    @Cacheable(
            value = RedisConfig.TASKS_CACHE,
            key = "T(com.nexus.hub.modules.task.service.TaskService).cacheKey(#principal.tenantId, #taskId)",
            unless = "#result == null || #result.status != T(com.nexus.hub.modules.task.entity.TaskStatus).COMPLETED"
    )
    @Transactional(readOnly = true)
    public TaskResponse getTaskById(UserPrincipal principal, UUID taskId) {
        AgentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found with ID: " + taskId));

        // Strict multi-tenant isolation
        if (!task.getTenant().getId().equals(principal.getTenantId())) {
            log.warn("Unauthorized access attempt to task {} by tenant {}", taskId, principal.getTenantId());
            throw new ResourceNotFoundException("Task not found with ID: " + taskId);
        }

        return TaskResponse.fromEntity(task);
    }

    @Transactional(readOnly = true)
    public TaskPageResponse listTasks(UserPrincipal principal, Pageable pageable) {
        Page<AgentTask> page = taskRepository.findByTenantId(principal.getTenantId(), pageable);
        Page<TaskResponse> responsePage = page.map(TaskResponse::fromEntity);
        return TaskPageResponse.fromPage(responsePage);
    }

    /**
     * Marks a task FAILED because its agent.task.requested message could not be delivered.
     * Runs in its own transaction because it is invoked from an AFTER_COMMIT listener, where the
     * original transaction has already completed.
     */
    @CacheEvict(
            value = RedisConfig.TASKS_CACHE,
            key = "T(com.nexus.hub.modules.task.service.TaskService).cacheKey(#tenantId, #taskId)"
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDispatchFailed(UUID taskId, UUID tenantId, String errorDetails) {
        AgentTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("Cannot mark dispatch failure: task {} not found", taskId);
            return;
        }
        if (task.getStatus() != TaskStatus.PENDING) {
            // The worker already picked it up (e.g. the ack timed out but delivery succeeded).
            log.warn("Not marking task {} FAILED after dispatch error: current status is {}", taskId, task.getStatus());
            return;
        }

        task.setStatus(TaskStatus.FAILED);
        task.setLastActiveNode("FAILED");
        task.setCompletedAt(Instant.now());
        task.setResultSummary("Task dispatch failed: " + errorDetails);

        Map<String, Object> resultPayload = new HashMap<>();
        resultPayload.put("errorDetails", errorDetails);
        try {
            task.setResultPayload(objectMapper.writeValueAsString(resultPayload));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize dispatch failure payload for task {}: {}", taskId, e.getMessage());
        }
        taskRepository.save(task);

        auditEventRepository.save(TaskAuditEvent.builder()
                .task(task)
                .eventType("TASK_DISPATCH_FAILED")
                .nodeType("INGRESS")
                .message("Failed to publish task to agent.task.requested: " + errorDetails)
                .payload(task.getResultPayload())
                .build());

        log.error("Task {} (tenant {}) marked FAILED: {}", taskId, tenantId, errorDetails);
    }
}
