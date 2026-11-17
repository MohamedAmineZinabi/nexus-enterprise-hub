package com.nexus.hub.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:nexus-core-service-group}")
    private String groupId;

    @Value("${nexus.kafka.topics.agent-task-dlq:agent.task.dlq}")
    private String dlqTopic;

    @Value("${nexus.kafka.dlq.backoff-initial-interval-ms:1000}")
    private long backoffInitialIntervalMs;

    @Value("${nexus.kafka.dlq.backoff-multiplier:2.0}")
    private double backoffMultiplier;

    @Value("${nexus.kafka.dlq.backoff-max-attempts:3}")
    private int backoffMaxAttempts;

    @Bean
    public ConsumerFactory<String, Object> consumerFactory(ObjectMapper objectMapper) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // ErrorHandlingDeserializer wrapping StringDeserializer for keys and JsonDeserializer for values
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);

        // Enterprise JsonDeserializer configuration
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, Map.class);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> {
                    log.error("[Kafka DLQ] Routing failed record from topic='{}' (partition={}, offset={}) to DLQ topic='{}': {}",
                            record.topic(), record.partition(), record.offset(), dlqTopic,
                            ex != null ? ex.getMessage() : "unknown error");
                    // Negative partition delegates partition assignment cleanly regardless of topic partition count differences
                    return new TopicPartition(dlqTopic, -1);
                }
        );
        recoverer.setFailIfSendResultIsError(true);
        recoverer.addHeadersFunction((record, ex) -> {
            RecordHeaders extraHeaders = new RecordHeaders();
            extraHeaders.add("x-dlq-source-service", "core-service".getBytes(StandardCharsets.UTF_8));
            extraHeaders.add("x-dlq-routed-at", Instant.now().toString().getBytes(StandardCharsets.UTF_8));
            return extraHeaders;
        });
        return recoverer;
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer deadLetterPublishingRecoverer) {
        // Exponential backoff before DLQ routing (default: 1s initial, 2.0x multiplier, max 3 retry attempts)
        ExponentialBackOff backOff = new ExponentialBackOff(backoffInitialIntervalMs, backoffMultiplier);
        backOff.setMaxAttempts(backoffMaxAttempts);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterPublishingRecoverer, backOff);
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("[Kafka Retry] Delivery attempt #{} failed for topic='{}', partition={}, offset={}, cause='{}'",
                        deliveryAttempt,
                        record != null ? record.topic() : "unknown",
                        record != null ? record.partition() : -1,
                        record != null ? record.offset() : -1L,
                        ex != null ? ex.getMessage() : "unknown")
        );
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
