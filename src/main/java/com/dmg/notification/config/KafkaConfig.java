package com.dmg.notification.config;

import com.dmg.notification.kafka.NotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class KafkaConfig {

    private final AppProperties appProperties;

    @Bean
    public NewTopic dispatchTopic() {
        return TopicBuilder.name(appProperties.getKafka().getTopicDispatch())
                .partitions(appProperties.getKafka().getDispatchPartitions())
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic retryTopic() {
        return TopicBuilder.name(appProperties.getKafka().getTopicRetry())
                .partitions(appProperties.getKafka().getRetryPartitions())
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic dlqTopic() {
        return TopicBuilder.name(appProperties.getKafka().getTopicDlq())
                .partitions(appProperties.getKafka().getDlqPartitions())
                .replicas(1)
                .build();
    }

    /**
     * Error handler for transient Kafka-level failures (not business failures).
     * Business failures (channel dispatch failures) are handled in DispatchService itself
     * and never surface as exceptions to the Kafka listener layer.
     *
     * This handler covers infrastructure issues: DB unavailable, deserialization errors, etc.
     * After 3 retries with exponential back-off, messages go to the DLQ topic.
     */
    /**
     * Inbound topic: upstream services publish notification requests here.
     * Partitioned by correlationId (e.g. orderId) — 10 partitions ensures
     * per-order ordering while allowing 10-way parallelism across different orders.
     */
    @Bean
    public NewTopic requestsTopic() {
        return TopicBuilder.name(appProperties.getKafka().getTopicRequests())
                .partitions(appProperties.getKafka().getRequestsPartitions())
                .replicas(1)
                .build();
    }

    /**
     * Outbound topic: downstream services subscribe to delivery outcomes here.
     * Partitioned by correlationId — guarantees per-order ordering of status events.
     */
    @Bean
    public NewTopic statusTopic() {
        return TopicBuilder.name(appProperties.getKafka().getTopicStatus())
                .partitions(appProperties.getKafka().getStatusPartitions())
                .replicas(1)
                .build();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, NotificationEvent> kafkaTemplate) {
        String dlqTopic = appProperties.getKafka().getTopicDlq();

        var recoverer = new org.springframework.kafka.listener.DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> {
                    log.error("Kafka message sent to DLQ after max retries. topic={} partition={} offset={}",
                            record.topic(), record.partition(), record.offset(), ex);
                    return new TopicPartition(dlqTopic, -1);
                });

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(4.0);
        backOff.setMaxInterval(30_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Don't retry on these — they indicate permanent failures
        handler.addNotRetryableExceptions(
                org.springframework.kafka.support.serializer.DeserializationException.class
        );
        return handler;
    }
}
