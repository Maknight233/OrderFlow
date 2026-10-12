package com.rom.orderflow.inventory.kafka.config;

import com.rom.orderflow.inventory.observability.BusinessMetrics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrorHandlingConfig {
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> template, BusinessMetrics metrics) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + "-dlt", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            recoverer.accept(record, exception);
            metrics.dlt(record.topic() + "-dlt");
        }, new FixedBackOff(2000L, 2L));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }

    @Bean
    public NewTopic inventoryEventsTopic() {
        return TopicBuilder.name("inventory-events").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic orderEventsDltTopic() {
        return TopicBuilder.name("order-events-dlt").partitions(3).replicas(1).build();
    }
}
