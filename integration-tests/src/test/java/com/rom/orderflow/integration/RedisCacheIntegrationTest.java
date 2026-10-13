package com.rom.orderflow.integration;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.rom.orderflow.integration.TestEnvironment.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisCacheIntegrationTest {
    private TestEnvironment env;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;

    @BeforeEach void report(TestInfo test) {
        System.out.println("Day11 scenario: " + test.getDisplayName());
    }

    @BeforeAll void start() throws Exception {
        assertTrue(Boolean.getBoolean("orderflow.it.redis"), "Run the Day11 runner with its dedicated Redis");
        factory = new LettuceConnectionFactory("localhost", 16379);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        env = new TestEnvironment();
        env.prepare();
        env.start("gateway", Map.of());
    }

    @AfterAll void stop() throws Exception {
        try { if (env != null) env.close(); }
        finally { if (factory != null) factory.destroy(); }
    }

    @Test @Order(1)
    void gatewayReadsCacheWith45SecondTtlAndDoesNotCache404() throws Exception {
        UUID missing = UUID.randomUUID();
        env.call("gateway", "GET", "/api/orders/" + missing, null, 404);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(orderKey(missing))));
        env.call("gateway", "GET", "/api/orders/not-a-uuid", null, 400);
        env.stock("CACHE-HIT", 5);
        UUID id = env.createOrder(List.of(item("CACHE-HIT", 1)));
        env.waitOrder(id, "COMPLETED");
        JsonNode first = order(id);
        Long ttl = redis.getExpire(orderKey(id));
        assertNotNull(ttl);
        assertTrue(ttl >= 40 && ttl <= 45, "Default order TTL must be 45s, got " + ttl);
        String cached = redis.opsForValue().get(orderKey(id));
        assertEquals(first, env.json.readTree(cached));
        double before = cacheMetric("order", "hit");
        assertEquals(first, order(id));
        assertEquals(before + 1, cacheMetric("order", "hit"));
        // Isolate restart persistence from cold JVM startup duration. The production
        // 45s TTL was checked above and is independently exercised by the expiry test.
        redis.expire(orderKey(id), Duration.ofMinutes(5));
        env.stop("order");
        env.start("order", Map.of());
        assertEquals(first, order(id));
        assertEquals(1, cacheMetric("order", "hit"));
        redis.expire(orderKey(id), Duration.ofSeconds(45));
    }

    @Test @Order(2)
    void kafkaCommitInvalidatesPendingOrderAndInventoryCaches() throws Exception {
        env.stock("CACHE-KAFKA", 8);
        inventory("CACHE-KAFKA");
        env.stop("inventory");
        UUID id = env.createOrder(List.of(item("CACHE-KAFKA", 2)));
        assertEquals("PENDING", order(id).get("status").asText());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(orderKey(id))));
        try {
            env.start("inventory", Map.of());
            env.waitOrder(id, "COMPLETED");
            await().atMost(Duration.ofSeconds(10)).until(() -> !Boolean.TRUE.equals(redis.hasKey(orderKey(id))));
            assertEquals("COMPLETED", order(id).get("status").asText());
            assertEquals(6, inventory("CACHE-KAFKA").get("quantity").asInt());
            assertEquals(0, env.number(INVENTORY_DB,
                    "SELECT reserved_quantity FROM inventory WHERE sku=?", "CACHE-KAFKA"));
        } finally { env.start("inventory", Map.of()); }
    }

    @Test @Order(3)
    void httpCancellationAndKafkaReleaseInvalidateEachCommittedStatus() throws Exception {
        env.stock("CACHE-CANCEL", 4);
        env.stop("inventory");
        UUID id = env.createOrder(List.of(item("CACHE-CANCEL", 1)));
        order(id);
        try {
            env.call("gateway", "POST", "/api/orders/" + id + "/cancel", Map.of("reason", "Day11"), 202);
            assertFalse(Boolean.TRUE.equals(redis.hasKey(orderKey(id))));
            assertEquals("CANCELLING", order(id).get("status").asText());
            env.start("inventory", Map.of());
            env.waitOrder(id, "CANCELLED");
            await().atMost(Duration.ofSeconds(10)).until(() -> !Boolean.TRUE.equals(redis.hasKey(orderKey(id))));
            assertEquals("CANCELLED", order(id).get("status").asText());
            assertEquals(4, inventory("CACHE-CANCEL").get("quantity").asInt());
        } finally { env.start("inventory", Map.of()); }
    }

    @Test @Order(4)
    void rolledBackSagaPreservesCacheThenReplayInvalidatesIt() throws Exception {
        env.stock("CACHE-ROLLBACK", 6);
        env.stop("inventory");
        UUID id = env.createOrder(List.of(item("CACHE-ROLLBACK", 1)));
        String cached;
        order(id);
        cached = redis.opsForValue().get(orderKey(id));
        env.sql(ORDER_DB, """
                CREATE FUNCTION it_cache_fail_status() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.status = 'CONFIRMED' THEN RAISE EXCEPTION 'Day11 rollback'; END IF;
                  RETURN NEW;
                END; $$
                """);
        env.sql(ORDER_DB, """
                CREATE TRIGGER it_cache_fail_status BEFORE UPDATE ON orders
                FOR EACH ROW EXECUTE FUNCTION it_cache_fail_status()
                """);
        String event = env.json.writeValueAsString(env.event(id, "INVENTORY_RESERVED", null));
        try (var observer = env.observer("inventory-events-dlt")) {
            env.sendRaw("inventory-events", id.toString(), event);
            // The listener deserializes WorkflowEvent; the DLT reserializes its value.
            env.waitRecord(observer, id.toString(), null);
            assertEquals("PENDING", env.text(ORDER_DB, "SELECT status FROM orders WHERE id=?", id));
            assertEquals(cached, redis.opsForValue().get(orderKey(id)), "Rollback must not evict cache");
            UUID eventId = UUID.fromString(env.json.readTree(event).get("eventId").asText());
            assertEquals(0, env.number(ORDER_DB, "SELECT count(*) FROM processed_inventory_events WHERE event_id=?", eventId));
            assertEquals(0, env.number(ORDER_DB,
                    "SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND event_type='INVENTORY_CONFIRM_REQUESTED'", id));
        } finally {
            env.sql(ORDER_DB, "DROP TRIGGER it_cache_fail_status ON orders");
            env.sql(ORDER_DB, "DROP FUNCTION it_cache_fail_status()");
            env.start("inventory", Map.of());
        }
        env.sendRaw("inventory-events", id.toString(), event);
        // Replay plus the queued original reserve command drive the real workflow to completion.
        env.waitOrder(id, "COMPLETED");
        await().atMost(Duration.ofSeconds(10)).until(() -> !Boolean.TRUE.equals(redis.hasKey(orderKey(id))));
        assertEquals("COMPLETED", order(id).get("status").asText());
    }

    @Test @Order(5)
    void corruptOrWrongOrderPayloadFallsBackToPostgres() throws Exception {
        env.stock("CACHE-CORRUPT", 3);
        UUID id = env.createOrder(List.of(item("CACHE-CORRUPT", 1)));
        env.waitOrder(id, "COMPLETED");
        JsonNode expected = order(id);
        redis.opsForValue().set(orderKey(id), "{broken-json", Duration.ofSeconds(45));
        assertEquals(expected, order(id));
        // Corruption falls back without performing a second Redis operation.
        assertEquals("{broken-json", redis.opsForValue().get(orderKey(id)));
        var wrong = env.json.readTree(expected.toString()).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) wrong).put("id", UUID.randomUUID().toString());
        redis.opsForValue().set(orderKey(id), wrong.toString(), Duration.ofSeconds(45));
        assertEquals(expected, order(id));
        assertEquals(expected, env.json.readTree(redis.opsForValue().get(orderKey(id))));
    }

    @Test @Order(6)
    void actual45SecondExpirationReloadsAndRefreshesTtl() throws Exception {
        env.stock("CACHE-EXPIRY", 3);
        UUID id = env.createOrder(List.of(item("CACHE-EXPIRY", 1)));
        env.waitOrder(id, "COMPLETED");
        JsonNode expected = order(id);
        await().atMost(Duration.ofSeconds(50)).pollInterval(Duration.ofSeconds(1))
                .until(() -> !Boolean.TRUE.equals(redis.hasKey(orderKey(id))));
        double before = cacheMetric("order", "miss");
        assertEquals(expected, order(id));
        assertEquals(before + 1, cacheMetric("order", "miss"));
        assertTrue(redis.getExpire(orderKey(id)) >= 40);
    }

    @Test @Order(7)
    void redisOutageAllowsReadsAndKafkaSagaThenRecovers() throws Exception {
        env.stock("CACHE-OUTAGE", 7);
        UUID completed = env.createOrder(List.of(item("CACHE-OUTAGE", 1)));
        env.waitOrder(completed, "COMPLETED");
        JsonNode expected = order(completed);
        redisContainer("stop");
        UUID duringOutage;
        try {
            assertEquals(expected, order(completed));
            assertEquals(6, inventory("CACHE-OUTAGE").get("quantity").asInt());
            for (String service : List.of("order", "inventory")) {
                env.call(service, "GET", "/actuator/health/liveness", null, 200);
                env.call(service, "GET", "/actuator/health/readiness", null, 200);
                env.call(service, "GET", "/actuator/health", null, 503);
            }
            duringOutage = env.createOrder(List.of(item("CACHE-OUTAGE", 2)));
            env.waitOrder(duringOutage, "COMPLETED");
            assertEquals("COMPLETED", order(duringOutage).get("status").asText());
            assertTrue(cacheMetric("order", "read_error") >= 1);
            assertTrue(cacheMetric("order", "eviction_error") >= 1);
        } finally { redisContainer("start"); }
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                .until(() -> { try (var connection = factory.getConnection()) { return "PONG".equals(connection.ping()); } });
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            assertEquals("COMPLETED", order(duringOutage).get("status").asText());
            assertTrue(Boolean.TRUE.equals(redis.hasKey(orderKey(duringOutage))));
        });
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(4, inventory("CACHE-OUTAGE").get("quantity").asInt()));
        for (String service : List.of("order", "inventory")) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    env.call(service, "GET", "/actuator/health", null, 200));
        }
    }

    @Test @Order(8)
    void inventoryHttpUpdateAndShortTtlRemainUsable() throws Exception {
        env.stock("CACHE-UPDATE", 9);
        inventory("CACHE-UPDATE");
        assertTrue(redis.getExpire(inventoryKey("CACHE-UPDATE")) <= 5);
        env.call("gateway", "PUT", "/api/inventory/CACHE-UPDATE", Map.of("quantity", 11), 200);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(inventoryKey("CACHE-UPDATE"))));
        assertEquals(11, inventory("CACHE-UPDATE").get("quantity").asInt());
    }

    @Test @Order(9)
    void scheduledTimeoutInvalidatesPendingCacheBeforeCompensationCompletes() throws Exception {
        env.stock("CACHE-TIMEOUT", 3);
        env.stop("inventory");
        UUID id = env.createOrder(List.of(item("CACHE-TIMEOUT", 1)));
        order(id);
        try {
            env.sql(ORDER_DB, "UPDATE order_sagas SET deadline=now()-interval '1 second' WHERE order_id=?", id);
            env.waitOrder(id, "CANCELLING");
            await().atMost(Duration.ofSeconds(10)).until(() -> !Boolean.TRUE.equals(redis.hasKey(orderKey(id))));
            assertEquals("CANCELLING", order(id).get("status").asText());
        } finally { env.start("inventory", Map.of()); }
        env.waitOrder(id, "CANCELLED");
        await().atMost(Duration.ofSeconds(10)).until(() -> !Boolean.TRUE.equals(redis.hasKey(orderKey(id))));
        assertEquals("CANCELLED", order(id).get("status").asText());
    }

    private JsonNode order(UUID id) throws Exception {
        return env.call("gateway", "GET", "/api/orders/" + id, null, 200);
    }
    private JsonNode inventory(String sku) throws Exception {
        return env.call("gateway", "GET", "/api/inventory/" + sku, null, 200);
    }
    private static String orderKey(UUID id) { return "orderflow:order:v1:" + id; }
    private static String inventoryKey(String sku) { return "orderflow:inventory:v1:" + sku; }

    private double cacheMetric(String service, String outcome) throws Exception {
        String name = "orderflow_" + service + "_cache_requests_total";
        String text = env.request(service, "GET", "/actuator/prometheus", null).body();
        for (String line : text.split("\n")) {
            if (line.startsWith(name + "{") && line.contains("outcome=\"" + outcome + "\"")) {
                return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return 0;
    }

    private void redisContainer(String action) throws Exception {
        Process process = new ProcessBuilder("docker", "compose", "-f",
                env.root.resolve("docker-compose.day11.yml").toString(), "-p", "orderflow-day11", action, "redis")
                .redirectErrorStream(true).redirectOutput(env.logs.resolve("redis-" + action + ".log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
    }
}
