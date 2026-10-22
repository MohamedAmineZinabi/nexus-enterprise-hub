package com.nexus.hub.modules.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.hub.common.exception.ResourceNotFoundException;
import com.nexus.hub.kafka.event.AgentTaskRequestedEvent;
import com.nexus.hub.kafka.producer.AgentTaskProducer;
import com.nexus.hub.modules.task.dto.CreateTaskRequest;
import com.nexus.hub.modules.task.dto.TaskPageResponse;
import com.nexus.hub.modules.task.dto.TaskResponse;
import com.nexus.hub.modules.task.entity.AgentTask;
import com.nexus.hub.modules.task.entity.TaskAuditEvent;
import com.nexus.hub.modules.task.entity.TaskStatus;
import com.nexus.hub.modules.task.repository.AgentTaskRepository;
import com.nexus.hub.modules.task.repository.TaskAuditEventRepository;
import com.nexus.hub.modules.tenant.entity.Tenant;
import com.nexus.hub.modules.tenant.repository.TenantRepository;
import com.nexus.hub.modules.user.entity.User;
import com.nexus.hub.modules.user.repository.UserRepository;
import com.nexus.hub.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final AgentTaskRepository taskRepository;
    private final TaskAuditEventRepository auditEventRepository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final AgentTaskProducer agentTaskProducer;
    private final ObjectMapper objectMapper;

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

        // 3. Construct and emit Kafka event
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

        agentTaskProducer.publishTaskRequested(event);

        log.info("Task created and dispatched: taskId='{}', tenantId='{}', type='{}'",
                task.getId(), tenant.getId(), task.getTaskType());

        return TaskResponse.fromEntity(task, traceId);
    }

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
}
