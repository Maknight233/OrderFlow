package com.rom.orderflow.order.kafka.config;

import com.rom.orderflow.order.kafka.event.WorkflowEvent;
import org.apache.kafka.common.serialization.*;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.*;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(producerFactory) {
            @Override
            protected String clusterId() {
                // Producer observations use no synchronous broker metadata lookup.
                // KafkaAdmin.clusterId() otherwise adds a separate wait before send timeouts apply.
                return null;
            }
        };
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    public ProducerFactory<String, Object> producerFactory(KafkaProperties properties) {
        // Preserve raw invalid JSON when ErrorHandlingDeserializer forwards it to the DLT.
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(),
                StringSerializer::new,
                () -> new DelegatingByTypeSerializer(Map.of(
                        byte[].class, new ByteArraySerializer(),
                        WorkflowEvent.class, new JacksonJsonSerializer<WorkflowEvent>())));
    }
}
