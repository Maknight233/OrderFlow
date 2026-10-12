package com.rom.orderflow.order.observability;

import io.micrometer.core.instrument.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class BusinessMetrics {
    private static final Logger log = LoggerFactory.getLogger(BusinessMetrics.class);
    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private volatile Map<String, Double> snapshot = Map.of();
    private volatile double lastSuccess;
    private volatile double available;
    private final String timestampZone;

    public BusinessMetrics(MeterRegistry registry, JdbcTemplate jdbc,
            @org.springframework.beans.factory.annotation.Value("${orderflow.observability.outbox-timestamp-zone:}") String timestampZone) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.timestampZone = timestampZone.isBlank() ? java.time.ZoneId.systemDefault().getId() : timestampZone;
        for (String state : List.of("PENDING", "PROCESSING", "PUBLISHED", "FAILED")) {
            Gauge.builder("orderflow.outbox.events", this, m -> m.value("outbox." + state))
                    .tag("status", state).register(registry);
        }
        Gauge.builder("orderflow.outbox.oldest.age", this, m -> m.value("outbox.age"))
                .baseUnit("seconds").register(registry);
        Gauge.builder("orderflow.outbox.retry.attempts", this, m -> m.value("outbox.retries")).register(registry);
        Gauge.builder("orderflow.metrics.snapshot.available", this, m -> m.available).register(registry);
        Gauge.builder("orderflow.metrics.snapshot.age", this,
                m -> m.lastSuccess == 0 ? Double.NaN : System.currentTimeMillis()/1000.0 - m.lastSuccess)
                .baseUnit("seconds").register(registry);
        for (String outcome : List.of("success", "failure")) {
            registry.counter("orderflow.outbox.attempts", "outcome", outcome);
            io.micrometer.core.instrument.Timer.builder("orderflow.outbox.publish").tag("outcome", outcome).register(registry);
        }
        registry.counter("orderflow.outbox.retries");
        registry.counter("orderflow.kafka.dlt.published", "topic", "inventory-events-dlt");
        for (String state : List.of("WAITING_RESERVATION", "WAITING_CONFIRMATION", "COMPENSATING",
                "COMPLETED", "CANCELLED", "MANUAL_RECOVERY")) {
            Gauge.builder("orderflow.saga.orders", this, m -> m.value("saga." + state))
                    .tag("state", state).register(registry);
        }
        Gauge.builder("orderflow.saga.overdue", this, m -> m.value("saga.overdue")).register(registry);
        registry.counter("orderflow.saga.timeouts");
        registry.counter("orderflow.saga.retries");
        for (String outcome : List.of("COMPLETED", "CANCELLED")) {
            io.micrometer.core.instrument.Timer.builder("orderflow.saga.duration").tag("outcome", outcome).register(registry);
        }

    }

    private double value(String key) { return snapshot.getOrDefault(key, 0.0); }

    @Scheduled(fixedDelayString = "${orderflow.observability.snapshot-delay-ms:5000}")
    public void refresh() {
        try {
            Map<String, Double> next = new HashMap<>();
            jdbc.query("SELECT status, count(*) FROM outbox_events GROUP BY status",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> next.put("outbox." + rs.getString(1), rs.getDouble(2)));
            jdbc.query("""
                    SELECT coalesce(max(extract(epoch FROM CURRENT_TIMESTAMP - (created_at AT TIME ZONE ?)))
                        FILTER (WHERE status <> 'PUBLISHED'),0), coalesce(sum(retry_count),0)
                    FROM outbox_events
                    """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                next.put("outbox.age", Math.max(0, rs.getDouble(1)));
                next.put("outbox.retries", rs.getDouble(2));
            }, timestampZone);
            jdbc.query("SELECT state, count(*) FROM order_sagas GROUP BY state",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> next.put("saga." + rs.getString(1), rs.getDouble(2)));
            next.put("saga.overdue", jdbc.queryForObject("""
                    SELECT count(*)::float8 FROM order_sagas
                    WHERE state IN ('WAITING_RESERVATION','WAITING_CONFIRMATION','COMPENSATING')
                      AND deadline < CURRENT_TIMESTAMP
                    """, Double.class));

            snapshot = Map.copyOf(next);
            lastSuccess = System.currentTimeMillis()/1000.0;
            available = 1;
        } catch (Exception ex) {
            available = 0;
            log.warn("Business metric snapshot failed", ex);
        }
    }

    public void publishAttempt(boolean success, boolean retry, long startedNanos) {
        String outcome = success ? "success" : "failure";
        registry.counter("orderflow.outbox.attempts", "outcome", outcome).increment();
        registry.timer("orderflow.outbox.publish", "outcome", outcome)
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
        if (retry) registry.counter("orderflow.outbox.retries").increment();
    }

    public void sagaTransition(String from, String to, java.time.Duration duration) {
        registry.counter("orderflow.saga.transitions", "from", from, "to", to).increment();
        if (duration != null) registry.timer("orderflow.saga.duration", "outcome", to).record(duration);
    }

    public void sagaTimeout() { registry.counter("orderflow.saga.timeouts").increment(); }
    public void sagaRetry() { registry.counter("orderflow.saga.retries").increment(); }

    public void dlt(String topic) {
        registry.counter("orderflow.kafka.dlt.published", "topic", topic).increment();
        log.atError().addKeyValue("topic", topic).log("Message published to DLT");
    }

    public static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { action.run(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            public void afterCommit() { action.run(); }
        });
    }
}
