package com.rom.orderflow.inventory.cache;

import com.rom.orderflow.inventory.dto.InventoryResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryReadCacheTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private InventoryReadCache cache;
    private final InventoryResponse response = new InventoryResponse(UUID.randomUUID(), "SKU", "Product",
            10, 1L, OffsetDateTime.now(), OffsetDateTime.now());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        cache = new InventoryReadCache(redis, mapper, new SimpleMeterRegistry(), true, Duration.ofSeconds(5));
    }

    @Test void hitSkipsDatabaseAndRoundTripsDates() {
        when(values.get(InventoryReadCache.key("SKU"))).thenReturn(mapper.writeValueAsString(response));
        assertThat(cache.get("SKU", () -> { throw new AssertionError("Database called on cache hit"); }))
                .isEqualTo(response);
    }

    @Test void missLoadsDatabaseAndUsesFiniteTtl() {
        assertThat(cache.get("SKU", () -> response)).isEqualTo(response);
        verify(values).set(InventoryReadCache.key("SKU"), mapper.writeValueAsString(response), Duration.ofSeconds(5));
    }

    @Test void redisOutageFallsBackWithoutAttemptingAnotherRedisOperation() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        assertThat(cache.get("SKU", () -> response)).isEqualTo(response);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test void corruptJsonFallsBack() {
        when(values.get(anyString())).thenReturn("broken-json");
        assertThat(cache.get("SKU", () -> response)).isEqualTo(response);
    }

    @Test void cacheWriteFailureDoesNotFailDatabaseRead() {
        doThrow(new RedisConnectionFailureException("offline")).when(values)
                .set(anyString(), anyString(), any(Duration.class));
        assertThat(cache.get("SKU", () -> response)).isEqualTo(response);
    }

    @Test void databaseFailureIsNotCached() {
        assertThatThrownBy(() -> cache.get("SKU", () -> { throw new IllegalStateException("not found"); }))
                .isInstanceOf(IllegalStateException.class);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test void disabledCacheNeverAccessesRedis() {
        cache = new InventoryReadCache(redis, mapper, new SimpleMeterRegistry(), false, Duration.ofSeconds(5));
        assertThat(cache.get("SKU", () -> response)).isEqualTo(response);
        cache.evictAfterCommit("SKU");
        verify(redis, never()).delete(anyString());
        verify(values, never()).get(anyString());
    }

    @Test void evictionWaitsForCommitAndIgnoresRedisFailure() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            cache.evictAfterCommit("SKU");
            verify(redis, never()).delete(anyString());
            doThrow(new RedisConnectionFailureException("offline")).when(redis).delete(anyString());
            TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();
            assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();
            verify(redis).delete(InventoryReadCache.key("SKU"));
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test void rollbackDoesNotEvict() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            cache.evictAfterCommit("SKU");
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(redis, never()).delete(anyString());
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test void springBootBindsConfiguredTtlAndCacheDependencies() {
        try (var context = new SpringApplicationBuilder(CacheConfiguration.class)
                .web(WebApplicationType.NONE).run("--spring.main.banner-mode=off",
                        "--orderflow.cache.inventory.enabled=true", "--orderflow.cache.inventory.ttl=7s")) {
            InventoryReadCache configured = context.getBean(InventoryReadCache.class);
            StringRedisTemplate template = context.getBean(StringRedisTemplate.class);
            configured.get("SKU", () -> response);
            verify(template.opsForValue()).set(InventoryReadCache.key("SKU"),
                    mapper.writeValueAsString(response), Duration.ofSeconds(7));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(InventoryReadCache.class)
    static class CacheConfiguration {
        @Bean StringRedisTemplate redis() {
            StringRedisTemplate template = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> operations = mock(ValueOperations.class);
            when(template.opsForValue()).thenReturn(operations);
            return template;
        }
        @Bean JsonMapper mapper() { return JsonMapper.builder().build(); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
    }
}
