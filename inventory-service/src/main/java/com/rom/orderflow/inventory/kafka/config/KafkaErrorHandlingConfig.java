package com.rom.orderflow.inventory.kafka.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, Object> kafkaTemplate) {

        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(
                        kafkaTemplate,
                        (record, exception) ->
                                new TopicPartition(
                                        record.topic() + "-dlt",
                                        record.partition()
                                )
                );

        // 第一次失败后等待 2 秒
        // retry 2 次
        // 总尝试次数 = 3
        FixedBackOff fixedBackOff =
                new FixedBackOff(2000L, 2L);

        return new DefaultErrorHandler(
                recoverer,
                fixedBackOff
        );
    }

    @Bean
    public NewTopic orderEventsDltTopic() {
        return TopicBuilder
                .name("order-events-dlt")
                .partitions(3)
                .replicas(1)
                .build();
    }
}