package com.rom.orderflow.inventory.cache;

import com.rom.orderflow.inventory.dto.InventoryResponse;
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
import java.util.function.Supplier;

/** Optional, eventually consistent HTTP read cache. Never used for stock decisions. */
@Component
public class InventoryReadCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ObjectReader reader;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final Duration ttl;

    public InventoryReadCache(StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry metrics,
            @Value("${orderflow.cache.inventory.enabled:true}") boolean enabled,
            @Value("${orderflow.cache.inventory.ttl:5s}") Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Inventory cache TTL must be positive");
        this.redis = redis;
        this.mapper = mapper;
        this.reader = mapper.readerFor(InventoryResponse.class)
                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        this.metrics = metrics;
        this.enabled = enabled;
        this.ttl = ttl;
    }

    public InventoryResponse get(String sku, Supplier<InventoryResponse> database) {
        if (!enabled) return database.get();
        try {
            String json = redis.opsForValue().get(key(sku));
            if (json != null) {
                InventoryResponse response = reader.readValue(json);
                if (response != null && sku.equals(response.sku())) {
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
        InventoryResponse response = database.get(); // Exceptions/404s are never cached.
        try {
            redis.opsForValue().set(key(sku), mapper.writeValueAsString(response), ttl);
        } catch (RuntimeException ex) {
            count("write_error");
        }
        return response;
    }

    public void evictAfterCommit(String sku) {
        if (!enabled) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evict(sku); }
            });
        } else {
            evict(sku);
        }
    }

    private void evict(String sku) {
        try {
            redis.delete(key(sku));
            count("eviction");
        } catch (RuntimeException ex) {
            // The database has already committed. Expiration bounds stale cache lifetime.
            count("eviction_error");
        }
    }

    private void count(String outcome) {
        metrics.counter("orderflow.inventory.cache.requests", "outcome", outcome).increment();
    }

    static String key(String sku) { return "orderflow:inventory:v1:" + sku; }
}
