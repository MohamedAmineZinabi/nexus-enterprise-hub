package com.nexus.hub.kafka.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.hub.kafka.event.TaskCompletedEventDto;
import com.nexus.hub.kafka.event.TaskProgressEventDto;
import com.nexus.hub.modules.task.entity.AgentTask;
import com.nexus.hub.modules.task.entity.TaskAuditEvent;
import com.nexus.hub.modules.task.entity.TaskStatus;
import com.nexus.hub.modules.task.repository.AgentTaskRepository;
import com.nexus.hub.modules.task.repository.TaskAuditEventRepository;
import com.nexus.hub.modules.task.service.TaskStreamHub;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTaskTelemetryConsumer {

    private final AgentTaskRepository taskRepository;
    private final TaskAuditEventRepository auditEventRepository;
    private final TaskStreamHub taskStreamHub;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;

    /**
     * Listens to intermediate node transitions from the AI service multi-agent graph.
     */
    @KafkaListener(
            topics = "${nexus.kafka.topics.agent-task-events:agent.task.events}",
            groupId = "${spring.kafka.consumer.group-id:nexus-core-service-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onTaskProgress(@Payload(required = false) Object rawEvent) {
        TaskProgressEventDto event = parseProgressEvent(rawEvent);
        if (event == null || event.getTaskId() == null) {
            log.warn("Received invalid or null progress event: {}", rawEvent);
            return;
        }
        UUID taskId = event.getTaskId();

        log.info("[Telemetry Consumer] Progress event for task='{}', node='{}', status='{}'",
                taskId, event.getNodeName(), event.getStatus());

        if (!taskRepository.existsById(taskId)) {
            log.warn("Task not found in database for progress telemetry: {}", taskId);
            return;
        }

        // 1. Atomically update task progress state in PostgreSQL if not already in terminal status
        taskRepository.updateProgressState(taskId, event.getNodeName());

        // Invalidate task cache to ensure fresh state
        try {
            if (cacheManager != null && cacheManager.getCache("tasks_cache") != null) {
                cacheManager.getCache("tasks_cache").evict(taskId);
            }
        } catch (Exception ignored) {}

        // 2. Persist audit telemetry event
        String payloadJson = null;
        if (event.getPayload() != null && !event.getPayload().isEmpty()) {
            try {
                payloadJson = objectMapper.writeValueAsString(event.getPayload());
            } catch (Exception e) {
                log.warn("Failed to serialize audit payload for task {}: {}", taskId, e.getMessage());
            }
        }

        TaskAuditEvent auditEvent = TaskAuditEvent.builder()
                .task(taskRepository.getReferenceById(taskId))
                .eventType("TASK_PROGRESS")
                .nodeType(event.getNodeName())
                .durationMs(event.getDurationMs())
                .message(event.getMessage() != null
                        ? event.getMessage()
                        : "Node " + event.getNodeName() + " phase " + event.getStatus())
                .payload(payloadJson)
                .build();
        auditEventRepository.save(auditEvent);

        // 3. Broadcast to live SSE subscribers
        taskStreamHub.broadcastEvent(taskId, "TASK_PROGRESS", event);
    }

    /**
     * Listens to final workflow completion events from the AI service.
     */
    @KafkaListener(
            topics = "${nexus.kafka.topics.agent-task-completed:agent.task.completed}",
            groupId = "${spring.kafka.consumer.group-id:nexus-core-service-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onTaskCompleted(@Payload(required = false) Object rawEvent) {
        TaskCompletedEventDto event = parseCompletedEvent(rawEvent);
        if (event == null || event.getTaskId() == null) {
            log.warn("Received invalid or null completion event: {}", rawEvent);
            return;
        }
        UUID taskId = event.getTaskId();

        log.info("[Telemetry Consumer] Completion event for task='{}', status='{}'",
                taskId, event.getStatus());

        AgentTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("Task not found in database for completion telemetry: {}", taskId);
            return;
        }

        // 1. Update task status & completion metadata
        boolean isSuccess = "COMPLETED".equalsIgnoreCase(event.getStatus());
        task.setStatus(isSuccess ? TaskStatus.COMPLETED : TaskStatus.FAILED);
        task.setCompletedAt(Instant.now());
        task.setLastActiveNode(isSuccess ? "COMPLETED" : "FAILED");

        if (event.getReport() != null && !event.getReport().isBlank()) {
            task.setResultSummary(event.getReport().length() > 500
                    ? event.getReport().substring(0, 500) + "..."
                    : event.getReport());
        }

        Map<String, Object> resultPayloadMap = new HashMap<>();
        resultPayloadMap.put("report", event.getReport());
        resultPayloadMap.put("citations", event.getCitations() != null ? event.getCitations() : List.of());
        resultPayloadMap.put("errorDetails", event.getErrorDetails());
        resultPayloadMap.put("totalDurationMs", event.getTotalDurationMs());

        try {
            task.setResultPayload(objectMapper.writeValueAsString(resultPayloadMap));
        } catch (Exception e) {
            log.warn("Failed to serialize resultPayload for task {}: {}", taskId, e.getMessage());
        }

        taskRepository.save(task);

        // Invalidate task cache
        try {
            if (cacheManager != null && cacheManager.getCache("tasks_cache") != null) {
                cacheManager.getCache("tasks_cache").evict(taskId);
            }
        } catch (Exception ignored) {}

        // 2. Persist audit telemetry event
        TaskAuditEvent auditEvent = TaskAuditEvent.builder()
                .task(task)
                .eventType("TASK_COMPLETED")
                .nodeType("WORKFLOW")
                .durationMs(event.getTotalDurationMs())
                .message(isSuccess
                        ? "Autonomous agent workflow completed successfully"
                        : "Task execution failed: " + event.getErrorDetails())
                .payload(task.getResultPayload())
                .build();
        auditEventRepository.save(auditEvent);

        // 3. Broadcast to live SSE streams and complete emitter sessions
        taskStreamHub.broadcastEvent(taskId, "TASK_COMPLETED", event);
        taskStreamHub.completeStream(taskId);
    }

    private TaskProgressEventDto parseProgressEvent(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof ConsumerRecord<?, ?> record) {
            raw = record.value();
            if (raw == null) {
                return null;
            }
        }
        if (raw instanceof TaskProgressEventDto dto) {
            return dto;
        }
        if (raw instanceof String str) {
            try {
                return objectMapper.readValue(str, TaskProgressEventDto.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot deserialize TaskProgressEventDto: " + e.getMessage(), e);
            }
        }
        return objectMapper.convertValue(raw, TaskProgressEventDto.class);
    }

    private TaskCompletedEventDto parseCompletedEvent(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof ConsumerRecord<?, ?> record) {
            raw = record.value();
            if (raw == null) {
                return null;
            }
        }
        if (raw instanceof TaskCompletedEventDto dto) {
            return dto;
        }
        if (raw instanceof String str) {
            try {
                return objectMapper.readValue(str, TaskCompletedEventDto.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot deserialize TaskCompletedEventDto: " + e.getMessage(), e);
            }
        }
        return objectMapper.convertValue(raw, TaskCompletedEventDto.class);
    }
}

