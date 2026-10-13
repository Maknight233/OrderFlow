package com.rom.orderflow.inventory.cache;

import com.rom.orderflow.inventory.dto.InventoryResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@EnabledIfSystemProperty(named = "orderflow.day11.redis", matches = "true")
class InventoryRedisIntegrationTest {
    @Test void realRedisSupportsHitExpirationAndEviction() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 16379);
        factory.afterPropertiesSet();
        factory.start();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        String sku = "DAY11-" + UUID.randomUUID();
        try {
            InventoryReadCache cache = new InventoryReadCache(redis, JsonMapper.builder().build(),
                    new SimpleMeterRegistry(), true, Duration.ofSeconds(2));
            InventoryResponse response = new InventoryResponse(UUID.randomUUID(), sku, "Day11", 10, 1L,
                    OffsetDateTime.now(), OffsetDateTime.now());
            AtomicInteger reads = new AtomicInteger();
            var database = (java.util.function.Supplier<InventoryResponse>) () -> {
                reads.incrementAndGet();
                return response;
            };
            assertThat(cache.get(sku, database)).isEqualTo(response);
            assertThat(redis.getExpire(InventoryReadCache.key(sku))).isBetween(0L, 2L);
            assertThat(cache.get(sku, database)).isEqualTo(response);
            assertThat(reads.get()).isEqualTo(1);
            await().atMost(Duration.ofSeconds(5)).until(() -> !Boolean.TRUE.equals(redis.hasKey(InventoryReadCache.key(sku))));
            cache.get(sku, database);
            assertThat(reads.get()).isEqualTo(2);
            cache.evictAfterCommit(sku);
            assertThat(redis.hasKey(InventoryReadCache.key(sku))).isFalse();
            cache.get(sku, database);
            assertThat(reads.get()).isEqualTo(3);
        } finally {
            redis.delete(InventoryReadCache.key(sku));
            factory.destroy();
        }
    }
}
