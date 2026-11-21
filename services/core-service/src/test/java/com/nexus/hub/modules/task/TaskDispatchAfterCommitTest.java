package com.nexus.hub.modules.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.hub.kafka.event.AgentTaskRequestedEvent;
import com.nexus.hub.kafka.producer.AgentTaskProducer;
import com.nexus.hub.modules.task.dto.CreateTaskRequest;
import com.nexus.hub.modules.task.entity.AgentTask;
import com.nexus.hub.modules.task.entity.TaskAuditEvent;
import com.nexus.hub.modules.task.entity.TaskStatus;
import com.nexus.hub.modules.task.entity.TaskType;
import com.nexus.hub.modules.task.event.TaskCreatedEvent;
import com.nexus.hub.modules.task.repository.AgentTaskRepository;
import com.nexus.hub.modules.task.repository.TaskAuditEventRepository;
import com.nexus.hub.modules.task.service.TaskDispatchListener;
import com.nexus.hub.modules.task.service.TaskService;
import com.nexus.hub.modules.tenant.entity.Tenant;
import com.nexus.hub.modules.tenant.repository.TenantRepository;
import com.nexus.hub.modules.user.entity.Role;
import com.nexus.hub.modules.user.entity.User;
import com.nexus.hub.modules.user.repository.UserRepository;
import com.nexus.hub.security.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskDispatchAfterCommitTest {

    @Mock
    private AgentTaskRepository taskRepository;
    @Mock
    private TaskAuditEventRepository auditEventRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AgentTaskProducer agentTaskProducer;
    @Mock
    private TaskService mockTaskService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @Test
    @DisplayName("createAndDispatchTask publishes a TaskCreatedEvent instead of calling Kafka in the transaction")
    void createPublishesApplicationEvent() {
        TaskService service = new TaskService(taskRepository, auditEventRepository, userRepository,
                tenantRepository, eventPublisher, new ObjectMapper());
        Tenant tenant = Tenant.builder().id(tenantId).name("A").slug("a").status("ACTIVE").build();
        User user = User.builder().id(UUID.randomUUID()).tenant(tenant).email("a@a.io").fullName("A")
                .role(Role.ADMIN).enabled(true).build();
        UserPrincipal principal = UserPrincipal.builder().id(user.getId()).tenantId(tenantId).email("a@a.io")
                .role(Role.ADMIN).authorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))).enabled(true).build();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(taskRepository.save(any(AgentTask.class))).thenAnswer(inv -> {
            AgentTask t = inv.getArgument(0);
            t.setId(taskId);
            return t;
        });

        CreateTaskRequest request = CreateTaskRequest.builder()
                .prompt("Analyze Q3").taskType(TaskType.DEEP_RESEARCH)
                .documentIds(List.of(UUID.randomUUID())).metadata(Map.of("k", "v")).build();

        service.createAndDispatchTask(principal, request);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        TaskCreatedEvent event = (TaskCreatedEvent) captor.getValue();
        assertThat(event.taskId()).isEqualTo(taskId);
        assertThat(event.tenantId()).isEqualTo(tenantId);
        AgentTaskRequestedEvent payload = event.payload();
        assertThat(payload.getTaskId()).isEqualTo(taskId);
        assertThat(payload.getTenantId()).isEqualTo(tenantId);
        assertThat(payload.getPrompt()).isEqualTo("Analyze Q3");
        assertThat(payload.getTraceId()).startsWith("trace-");
        assertThat(payload.getDocumentIds()).isEqualTo(request.getDocumentIds());
        assertThat(payload.getMetadata()).isEqualTo(request.getMetadata());
    }

    @Test
    @DisplayName("Listener runs AFTER_COMMIT and the failure handler uses REQUIRES_NEW")
    void annotationsAreCorrect() throws Exception {
        TransactionalEventListener listener = TaskDispatchListener.class
                .getMethod("onTaskCreated", TaskCreatedEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);

        Transactional tx = TaskService.class
                .getMethod("markDispatchFailed", UUID.class, UUID.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("Successful Kafka ack does not mark the task failed")
    void successfulSendDoesNotFail() {
        TaskDispatchListener listener = new TaskDispatchListener(agentTaskProducer, mockTaskService, 1000);
        AgentTaskRequestedEvent payload = AgentTaskRequestedEvent.builder().taskId(taskId).tenantId(tenantId).build();
        @SuppressWarnings("unchecked")
        SendResult<String, Object> sendResult = mock(SendResult.class);
        when(agentTaskProducer.publishTaskRequested(payload)).thenReturn(CompletableFuture.completedFuture(sendResult));

        listener.onTaskCreated(new TaskCreatedEvent(taskId, tenantId, payload));

        verify(mockTaskService, never()).markDispatchFailed(any(), any(), anyString());
    }

    @Test
    @DisplayName("Failed Kafka send marks the task FAILED via the REQUIRES_NEW handler")
    void failedSendMarksTaskFailed() {
        TaskDispatchListener listener = new TaskDispatchListener(agentTaskProducer, mockTaskService, 1000);
        AgentTaskRequestedEvent payload = AgentTaskRequestedEvent.builder().taskId(taskId).tenantId(tenantId).build();
        when(agentTaskProducer.publishTaskRequested(payload))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        listener.onTaskCreated(new TaskCreatedEvent(taskId, tenantId, payload));

        verify(mockTaskService).markDispatchFailed(eq(taskId), eq(tenantId), contains("Kafka publish failed"));
    }

    @Test
    @DisplayName("Kafka ack timeout marks the task FAILED")
    void timeoutMarksTaskFailed() {
        TaskDispatchListener listener = new TaskDispatchListener(agentTaskProducer, mockTaskService, 50);
        AgentTaskRequestedEvent payload = AgentTaskRequestedEvent.builder().taskId(taskId).tenantId(tenantId).build();
        when(agentTaskProducer.publishTaskRequested(payload)).thenReturn(new CompletableFuture<>());

        listener.onTaskCreated(new TaskCreatedEvent(taskId, tenantId, payload));

        verify(mockTaskService).markDispatchFailed(eq(taskId), eq(tenantId), contains("Timed out"));
    }

    @Test
    @DisplayName("markDispatchFailed sets FAILED with errorDetails and records an audit event")
    void markDispatchFailedUpdatesTask() {
        TaskService service = new TaskService(taskRepository, auditEventRepository, userRepository,
                tenantRepository, eventPublisher, new ObjectMapper());
        Tenant tenant = Tenant.builder().id(tenantId).name("A").slug("a").status("ACTIVE").build();
        AgentTask task = AgentTask.builder().id(taskId).tenant(tenant).prompt("p")
                .status(TaskStatus.PENDING).totalTokensUsed(0L).build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        service.markDispatchFailed(taskId, tenantId, "Kafka publish failed: TimeoutException");

        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getResultPayload()).contains("errorDetails").contains("TimeoutException");
        assertThat(task.getCompletedAt()).isNotNull();
        verify(taskRepository).save(task);
        verify(auditEventRepository).save(any(TaskAuditEvent.class));
    }
}
