package com.nexus.hub.modules.task.event;

import com.nexus.hub.kafka.event.AgentTaskRequestedEvent;

import java.util.UUID;

/**
 * In-process Spring application event published inside the task-creation transaction.
 * It is handled only AFTER the transaction commits, so Kafka never sees a task that
 * was rolled back (and the AI worker never races a not-yet-visible DB row).
 *
 * @param taskId   persisted task id
 * @param tenantId owning tenant id
 * @param payload  the unchanged Kafka message for topic agent.task.requested
 */
public record TaskCreatedEvent(UUID taskId, UUID tenantId, AgentTaskRequestedEvent payload) {
}
