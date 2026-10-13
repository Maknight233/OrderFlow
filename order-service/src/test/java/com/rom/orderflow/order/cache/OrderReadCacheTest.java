package com.rom.orderflow.order.cache;

import com.rom.orderflow.order.dto.OrderResponse;
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
import java.time.Instant;
import java.math.BigDecimal;
import com.rom.orderflow.order.domain.OrderStatus;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderReadCacheTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private OrderReadCache cache;
    private final UUID id = UUID.randomUUID();
    private final OrderResponse response = new OrderResponse(id, UUID.randomUUID(), OrderStatus.PENDING,
            new BigDecimal("10.00"), "USD", 1L, Instant.now(), Instant.now());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        cache = new OrderReadCache(redis, mapper, new SimpleMeterRegistry(), true, Duration.ofSeconds(45));
    }

    @Test void hitSkipsDatabaseAndRoundTripsDates() {
        when(values.get(OrderReadCache.key(id))).thenReturn(mapper.writeValueAsString(response));
        assertThat(cache.get(id, () -> { throw new AssertionError("Database called on cache hit"); }))
                .isEqualTo(response);
    }

    @Test void missLoadsDatabaseAndUsesFiniteTtl() {
        assertThat(cache.get(id, () -> response)).isEqualTo(response);
        verify(values).set(OrderReadCache.key(id), mapper.writeValueAsString(response), Duration.ofSeconds(45));
    }

    @Test void redisOutageFallsBackWithoutAttemptingAnotherRedisOperation() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        assertThat(cache.get(id, () -> response)).isEqualTo(response);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test void corruptJsonFallsBack() {
        when(values.get(anyString())).thenReturn("broken-json");
        assertThat(cache.get(id, () -> response)).isEqualTo(response);
    }

    @Test void cacheWriteFailureDoesNotFailDatabaseRead() {
        doThrow(new RedisConnectionFailureException("offline")).when(values)
                .set(anyString(), anyString(), any(Duration.class));
        assertThat(cache.get(id, () -> response)).isEqualTo(response);
    }

    @Test void databaseFailureIsNotCached() {
        assertThatThrownBy(() -> cache.get(id, () -> { throw new IllegalStateException("not found"); }))
                .isInstanceOf(IllegalStateException.class);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test void disabledCacheNeverAccessesRedis() {
        cache = new OrderReadCache(redis, mapper, new SimpleMeterRegistry(), false, Duration.ofSeconds(45));
        assertThat(cache.get(id, () -> response)).isEqualTo(response);
        cache.evictAfterCommit(id);
        verify(redis, never()).delete(anyString());
        verify(values, never()).get(anyString());
    }

    @Test void evictionWaitsForCommitAndIgnoresRedisFailure() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            cache.evictAfterCommit(id);
            verify(redis, never()).delete(anyString());
            doThrow(new RedisConnectionFailureException("offline")).when(redis).delete(anyString());
            TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();
            assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();
            verify(redis).delete(OrderReadCache.key(id));
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test void rollbackDoesNotEvict() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            cache.evictAfterCommit(id);
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
                        "--orderflow.cache.order.enabled=true", "--orderflow.cache.order.ttl=7s")) {
            OrderReadCache configured = context.getBean(OrderReadCache.class);
            StringRedisTemplate template = context.getBean(StringRedisTemplate.class);
            configured.get(id, () -> response);
            verify(template.opsForValue()).set(OrderReadCache.key(id),
                    mapper.writeValueAsString(response), Duration.ofSeconds(7));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(OrderReadCache.class)
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
