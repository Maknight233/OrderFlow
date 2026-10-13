package com.rom.orderflow.order.cache;

import com.rom.orderflow.order.dto.OrderResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.DateTimeFeature;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/** Optional, eventually consistent HTTP read cache. Saga decisions always use PostgreSQL. */
@Component
public class OrderReadCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ObjectReader reader;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final Duration ttl;

    public OrderReadCache(StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry metrics,
            @Value("${orderflow.cache.order.enabled:true}") boolean enabled,
            @Value("${orderflow.cache.order.ttl:45s}") Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Order cache TTL must be positive");
        this.redis = redis;
        this.mapper = mapper;
        this.reader = mapper.readerFor(OrderResponse.class)
                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        this.metrics = metrics;
        this.enabled = enabled;
        this.ttl = ttl;
    }

    public OrderResponse get(UUID id, Supplier<OrderResponse> database) {
        if (!enabled) return database.get();
        try {
            String json = redis.opsForValue().get(key(id));
            if (json != null) {
                OrderResponse response = reader.readValue(json);
                if (response != null && id.equals(response.id())) {
                    count("hit");
                    return response;
                }
            }
        } catch (RuntimeException ex) {
            count("read_error");
            // One failed Redis operation is enough; do not try to populate on this request.
            return database.get();
        }
        count("miss");
        OrderResponse response = database.get(); // Exceptions/404s are never cached.
        try {
            redis.opsForValue().set(key(id), mapper.writeValueAsString(response), ttl);
        } catch (RuntimeException ex) {
            count("write_error");
        }
        return response;
    }

    public void evictAfterCommit(UUID id) {
        if (!enabled) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evict(id); }
            });
        } else {
            evict(id);
        }
    }

    private void evict(UUID id) {
        try {
            redis.delete(key(id));
            count("eviction");
        } catch (RuntimeException ex) {
            // The database has already committed. Expiration bounds stale cache lifetime.
            count("eviction_error");
        }
    }

    private void count(String outcome) {
        metrics.counter("orderflow.order.cache.requests", "outcome", outcome).increment();
    }

    static String key(UUID id) { return "orderflow:order:v1:" + id; }
}
