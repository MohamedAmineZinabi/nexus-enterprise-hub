package com.nexus.hub.kafka.producer;

import com.nexus.hub.kafka.event.AgentTaskRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${nexus.kafka.topics.agent-task-requested:agent.task.requested}")
    private String agentTaskRequestedTopic;

    public CompletableFuture<SendResult<String, Object>> publishTaskRequested(AgentTaskRequestedEvent event) {
        String partitionKey = event.getTenantId().toString();

        log.info("Publishing AgentTaskRequestedEvent to topic '{}' with key '{}', taskId '{}'",
                agentTaskRequestedTopic, partitionKey, event.getTaskId());

        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(
                agentTaskRequestedTopic,
                partitionKey,
                event
        );

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Successfully delivered AgentTaskRequestedEvent [taskId={}] to partition {} with offset {}",
                        event.getTaskId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else {
                log.error("Failed to publish AgentTaskRequestedEvent [taskId={}] to topic '{}': {}",
                        event.getTaskId(), agentTaskRequestedTopic, ex.getMessage(), ex);
            }
        });

        return future;
    }
}
