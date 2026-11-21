package com.nexus.hub.modules.task.service;

import com.nexus.hub.kafka.producer.AgentTaskProducer;
import com.nexus.hub.modules.task.event.TaskCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes agent.task.requested only after the task row has been committed, and waits for the
 * broker acknowledgement. If the send fails or times out, the task is marked FAILED in a new
 * transaction so it never stays PENDING forever.
 */
@Slf4j
@Component
public class TaskDispatchListener {

    private final AgentTaskProducer agentTaskProducer;
    private final TaskService taskService;
    private final long sendTimeoutMs;

    public TaskDispatchListener(
            AgentTaskProducer agentTaskProducer,
            TaskService taskService,
            @Value("${nexus.kafka.producer.send-timeout-ms:15000}") long sendTimeoutMs) {
        this.agentTaskProducer = agentTaskProducer;
        this.taskService = taskService;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskCreated(TaskCreatedEvent event) {
        try {
            agentTaskProducer.publishTaskRequested(event.payload()).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("Task {} dispatched to Kafka after commit", event.taskId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleFailure(event, "Interrupted while publishing task request");
        } catch (TimeoutException e) {
            handleFailure(event, "Timed out after " + sendTimeoutMs + "ms waiting for Kafka acknowledgement");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            handleFailure(event, "Kafka publish failed: " + cause.getClass().getSimpleName());
            log.debug("Kafka publish failure detail for task {}", event.taskId(), cause);
        } catch (RuntimeException e) {
            // e.g. KafkaException thrown synchronously by send() (serialization, metadata timeout)
            handleFailure(event, "Kafka publish failed: " + e.getClass().getSimpleName());
            log.debug("Kafka publish failure detail for task {}", event.taskId(), e);
        }
    }

    private void handleFailure(TaskCreatedEvent event, String errorDetails) {
        log.error("Failed to dispatch task {} (tenant {}): {}", event.taskId(), event.tenantId(), errorDetails);
        try {
            taskService.markDispatchFailed(event.taskId(), event.tenantId(), errorDetails);
        } catch (Exception ex) {
            log.error("Could not mark task {} as FAILED after dispatch error: {}", event.taskId(), ex.getMessage(), ex);
        }
    }
}
