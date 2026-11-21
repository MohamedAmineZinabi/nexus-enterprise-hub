package com.nexus.hub.kafka.producer;

import com.nexus.hub.kafka.event.DocumentIngestRequestedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class DocumentIngestProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String documentIngestRequestedTopic;

    public DocumentIngestProducer(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${nexus.kafka.topics.document-ingest-requested:document.ingest.requested}") String documentIngestRequestedTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.documentIngestRequestedTopic = documentIngestRequestedTopic;
    }

    /**
     * Publishes document.ingest.requested keyed by documentId (all events of one document stay ordered).
     */
    public CompletableFuture<SendResult<String, Object>> publishIngestRequested(DocumentIngestRequestedEvent event) {
        String key = event.getDocumentId();
        log.info("Publishing DocumentIngestRequestedEvent to topic '{}' with key '{}' (tenant {})",
                documentIngestRequestedTopic, key, event.getTenantId());

        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(documentIngestRequestedTopic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Delivered DocumentIngestRequestedEvent [documentId={}] to partition {} offset {}",
                        key, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            } else {
                log.error("Failed to publish DocumentIngestRequestedEvent [documentId={}] to topic '{}': {}",
                        key, documentIngestRequestedTopic, ex.getMessage());
            }
        });
        return future;
    }
}
