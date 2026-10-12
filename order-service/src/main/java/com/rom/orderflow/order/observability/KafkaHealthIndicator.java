package com.rom.orderflow.order.observability;

import org.apache.kafka.clients.admin.*;
import org.springframework.boot.health.contributor.*;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component("kafkaHealthIndicator")
public class KafkaHealthIndicator implements HealthIndicator {
    private final Admin admin;

    public KafkaHealthIndicator(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>(properties.buildAdminProperties());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1500);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2000);
        config.put(AdminClientConfig.CLIENT_ID_CONFIG, "order-health");
        admin = Admin.create(config);
    }

    public Health health() {
        try {
            boolean available = !admin.describeCluster().nodes().get(2, TimeUnit.SECONDS).isEmpty();
            return available ? Health.up().build() : Health.down().build();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Health.down().build();
        } catch (Exception ex) {
            return Health.down().build();
        }
    }

    @PreDestroy
    void close() { admin.close(Duration.ofSeconds(1)); }
}
