package com.nexus.hub.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.hub.kafka.consumer.AgentTaskTelemetryConsumer;
import com.nexus.hub.kafka.event.TaskCompletedEventDto;
import com.nexus.hub.kafka.event.TaskProgressEventDto;
import com.nexus.hub.modules.task.entity.AgentTask;
import com.nexus.hub.modules.task.entity.TaskAuditEvent;
import com.nexus.hub.modules.task.entity.TaskStatus;
import com.nexus.hub.modules.task.entity.TaskType;
import com.nexus.hub.modules.task.repository.AgentTaskRepository;
import com.nexus.hub.modules.task.repository.TaskAuditEventRepository;
import com.nexus.hub.modules.task.service.TaskStreamHub;
import com.nexus.hub.modules.tenant.entity.Tenant;
import com.nexus.hub.modules.user.entity.Role;
import com.nexus.hub.modules.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskTelemetryIntegrationTest {

    @Mock
    private AgentTaskRepository taskRepository;

    @Mock
    private TaskAuditEventRepository auditEventRepository;

    @Mock
    private TaskStreamHub taskStreamHub;

    private ObjectMapper objectMapper;
    private AgentTaskTelemetryConsumer telemetryConsumer;

    private UUID tenantId;
    private UUID userId;
    private UUID taskId;
    private AgentTask testTask;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        telemetryConsumer = new AgentTaskTelemetryConsumer(
                taskRepository,
                auditEventRepository,
                taskStreamHub,
                objectMapper
        );

        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        taskId = UUID.randomUUID();

        Tenant tenant = Tenant.builder()
                .id(tenantId)
                .name("Acme Corp")
                .slug("acme")
                .status("ACTIVE")
                .build();

        User user = User.builder()
                .id(userId)
                .tenant(tenant)
                .email("alice@acme.com")
                .fullName("Alice Analyst")
                .role(Role.ANALYST)
                .enabled(true)
                .build();

        testTask = AgentTask.builder()
                .id(taskId)
                .tenant(tenant)
                .user(user)
                .prompt("Analyze quarterly compliance")
                .taskType(TaskType.DEEP_RESEARCH)
                .status(TaskStatus.PENDING)
                .totalTokensUsed(0L)
                .build();
    }

    @Test
    @DisplayName("TaskStreamHub: register client, broadcast progress, and complete stream")
    void testTaskStreamHubLifecycle() {
        TaskStreamHub hub = new TaskStreamHub();
        SseEmitter emitter = hub.registerClient(taskId, 5000L);

        assertThat(emitter).isNotNull();
        assertThat(hub.getActiveSubscriberCount(taskId)).isEqualTo(1);

        // Broadcast progress
        hub.broadcastEvent(taskId, "TASK_PROGRESS", Map.of("node", "PLANNER", "status", "COMPLETED"));
        assertThat(hub.getActiveSubscriberCount(taskId)).isEqualTo(1);

        // Complete stream
        hub.completeStream(taskId);
        assertThat(hub.getActiveSubscriberCount(taskId)).isEqualTo(0);
    }

    @Test
    @DisplayName("TelemetryConsumer: Process progress event updates task state and records audit log")
    void testProcessProgressEvent() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(testTask));

        TaskProgressEventDto progressEvent = TaskProgressEventDto.builder()
                .taskId(taskId)
                .tenantId(tenantId)
                .nodeName("RESEARCHER")
                .status("COMPLETED")
                .durationMs(1420L)
                .message("Researcher gathered 3 citations")
                .payload(Map.of("citationsCount", 3))
                .timestamp(Instant.now())
                .traceId("trace-12345")
                .build();

        telemetryConsumer.onTaskProgress(progressEvent);

        // 1. Verify task update
        assertThat(testTask.getStatus()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(testTask.getLastActiveNode()).isEqualTo("RESEARCHER");
        verify(taskRepository, times(1)).save(testTask);

        // 2. Verify audit event persistence
        ArgumentCaptor<TaskAuditEvent> auditCaptor = ArgumentCaptor.forClass(TaskAuditEvent.class);
        verify(auditEventRepository, times(1)).save(auditCaptor.capture());
        TaskAuditEvent savedAudit = auditCaptor.getValue();
        assertThat(savedAudit.getTask()).isEqualTo(testTask);
        assertThat(savedAudit.getEventType()).isEqualTo("TASK_PROGRESS");
        assertThat(savedAudit.getNodeType()).isEqualTo("RESEARCHER");
        assertThat(savedAudit.getDurationMs()).isEqualTo(1420L);

        // 3. Verify SSE broadcast
        verify(taskStreamHub, times(1)).broadcastEvent(eq(taskId), eq("TASK_PROGRESS"), eq(progressEvent));
    }

    @Test
    @DisplayName("TelemetryConsumer: Process completed event sets report, closes stream, and audits")
    void testProcessCompletedEvent() {
        testTask.setStatus(TaskStatus.PROCESSING);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(testTask));

        String mockReport = "# Compliance Report\nAll policies verified with zero violations.";
        List<Map<String, Object>> mockCitations = List.of(
                Map.of("citationId", "cit-1", "documentId", UUID.randomUUID().toString())
        );

        TaskCompletedEventDto completedEvent = TaskCompletedEventDto.builder()
                .taskId(taskId)
                .tenantId(tenantId)
                .status("COMPLETED")
                .report(mockReport)
                .citations(mockCitations)
                .totalDurationMs(3850L)
                .traceId("trace-12345")
                .timestamp(Instant.now())
                .build();

        telemetryConsumer.onTaskCompleted(completedEvent);

        // 1. Verify task status & completion
        assertThat(testTask.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(testTask.getCompletedAt()).isNotNull();
        assertThat(testTask.getResultSummary()).contains("# Compliance Report");
        assertThat(testTask.getResultPayload()).contains("citations");
        verify(taskRepository, times(1)).save(testTask);

        // 2. Verify audit event
        ArgumentCaptor<TaskAuditEvent> auditCaptor = ArgumentCaptor.forClass(TaskAuditEvent.class);
        verify(auditEventRepository, times(1)).save(auditCaptor.capture());
        TaskAuditEvent savedAudit = auditCaptor.getValue();
        assertThat(savedAudit.getEventType()).isEqualTo("TASK_COMPLETED");
        assertThat(savedAudit.getNodeType()).isEqualTo("WORKFLOW");
        assertThat(savedAudit.getDurationMs()).isEqualTo(3850L);

        // 3. Verify SSE broadcast and stream completion
        verify(taskStreamHub, times(1)).broadcastEvent(eq(taskId), eq("TASK_COMPLETED"), eq(completedEvent));
        verify(taskStreamHub, times(1)).completeStream(eq(taskId));
    }
}

