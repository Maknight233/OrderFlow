package com.rom.orderflow.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.rom.orderflow.integration.TestEnvironment.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SagaWorkflowIntegrationTest {
    private TestEnvironment env;

    @BeforeAll
    void start() throws Exception {
        env = new TestEnvironment();
        env.prepare();
    }

    @AfterAll
    void stop() throws Exception {
        if (env != null) env.close();
    }

    @BeforeEach
    void report(TestInfo test) {
        System.out.println("Integration scenario: " + test.getDisplayName());
    }

    @Test
    @Order(1)
    void freshAndLegacyFlywayMigrationsPreserveRecoveryState() throws Exception {
        assertEquals(4, env.number(ORDER_DB, "SELECT count(*) FROM flyway_schema_history WHERE success"));
        assertEquals(5, env.number(INVENTORY_DB, "SELECT count(*) FROM flyway_schema_history WHERE success"));

        String database = "orderflow_upgrade_it";
        Flyway.configure().dataSource("jdbc:postgresql://localhost:15432/" + database, "orderflow", "orderflow")
                .locations("filesystem:" + env.root.resolve("order-service/src/main/resources/db/migration"))
                .target("3").load().migrate();
        UUID confirmed = UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();
        UUID missingItems = UUID.randomUUID();
        for (UUID order : List.of(confirmed, cancelled, missingItems)) {
            env.sql(database, "INSERT INTO orders(id,customer_id,status,total_amount,currency) VALUES (?,? ,?,10,'USD')",
                    order, UUID.randomUUID(), order.equals(confirmed) ? "CONFIRMED"
                            : order.equals(cancelled) ? "CANCELLED" : "PENDING");
        }
        for (UUID order : List.of(confirmed, cancelled)) {
            Map<String, Object> event = env.event(order, "ORDER_CREATED", List.of(item("LEGACY", 2)));
            event.remove("eventType");
            event.remove("schemaVersion");
            env.sql(database, """
                    INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,status,created_at)
                    VALUES (?,'ORDER',?,'ORDER_CREATED',?,'PUBLISHED',NOW())
                    """, UUID.fromString(event.get("eventId").toString()), order, env.json.writeValueAsString(event));
        }
        Flyway.configure().dataSource("jdbc:postgresql://localhost:15432/" + database, "orderflow", "orderflow")
                .locations("filesystem:" + env.root.resolve("order-service/src/main/resources/db/migration"))
                .load().migrate();
        assertEquals("WAITING_CONFIRMATION", env.text(database, "SELECT state FROM order_sagas WHERE order_id=?", confirmed));
        assertEquals("COMPENSATING", env.text(database, "SELECT state FROM order_sagas WHERE order_id=?", cancelled));
        assertEquals("MANUAL_RECOVERY", env.text(database, "SELECT state FROM order_sagas WHERE order_id=?", missingItems));
        assertEquals(2, env.number(database, "SELECT quantity FROM order_items WHERE order_id=?", confirmed));

        database = "inventory_upgrade_it";
        Flyway.configure().dataSource("jdbc:postgresql://localhost:15432/" + database, "orderflow", "orderflow")
                .locations("filesystem:" + env.root.resolve("inventory-service/src/main/resources/db/migration"))
                .target("4").load().migrate();
        for (String status : List.of("CONFIRMED", "RELEASED", "RESERVED")) {
            env.sql(database, """
                    INSERT INTO inventory_reservations(id,order_id,sku,quantity,status)
                    VALUES (?,?,'LEGACY',1,?)
                    """, UUID.randomUUID(), UUID.randomUUID(), status);
        }
        Flyway.configure().dataSource("jdbc:postgresql://localhost:15432/" + database, "orderflow", "orderflow")
                .locations("filesystem:" + env.root.resolve("inventory-service/src/main/resources/db/migration"))
                .load().migrate();
        for (String status : List.of("CONFIRMED", "RELEASED", "NEW")) {
            assertEquals(1, env.number(database, "SELECT count(*) FROM inventory_workflows WHERE state=?", status));
        }
    }

    @Test
    @Order(2)
    void multiSkuOrderCompletesAndMergesRepeatedSkus() throws Exception {
        env.stock("SUCCESS-A", 5);
        env.stock("SUCCESS-B", 4);
        UUID order = env.createOrder(List.of(item("SUCCESS-A", 1), item("SUCCESS-A", 2), item("SUCCESS-B", 2)));
        env.waitOrder(order, "COMPLETED");
        assertEquals("COMPLETED", env.text(ORDER_DB, "SELECT state FROM order_sagas WHERE order_id=?", order));
        assertEquals(2, env.number(ORDER_DB, "SELECT count(*) FROM order_items WHERE order_id=?", order));
        assertEquals(3, env.number(ORDER_DB, "SELECT quantity FROM order_items WHERE order_id=? AND sku='SUCCESS-A'", order));
        assertStock("SUCCESS-A", 2, 0);
        assertStock("SUCCESS-B", 2, 0);
        assertEquals(2, env.number(INVENTORY_DB,
                "SELECT count(*) FROM inventory_reservations WHERE order_id=? AND status='CONFIRMED'", order));
        assertEquals(2, env.number(ORDER_DB,
                "SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND status='PUBLISHED'", order));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSUFFICIENT", "MISSING"})
    @Order(3)
    void failedMultiSkuReservationRollsBackAllStockAndCancels(String failure) throws Exception {
        String first = "FAIL-" + failure + "-A";
        String last = "FAIL-" + failure + "-Z";
        env.stock(first, 10);
        if (failure.equals("INSUFFICIENT")) env.stock(last, 0);
        UUID order = env.createOrder(List.of(item(first, 2), item(last, 1)));
        env.waitOrder(order, "CANCELLED");
        assertStock(first, 10, 0);
        assertEquals(0, env.number(INVENTORY_DB, "SELECT count(*) FROM inventory_reservations WHERE order_id=?", order));
        assertEquals("RELEASED", env.text(INVENTORY_DB, "SELECT state FROM inventory_workflows WHERE order_id=?", order));
        assertNotNull(env.text(ORDER_DB, "SELECT failure_reason FROM order_sagas WHERE order_id=?", order));
        assertEquals(1, env.number(INVENTORY_DB,
                "SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND event_type='INVENTORY_REJECTED'", order));
    }

    @Test
    @Order(4)
    void duplicateCommandsAndResultsHaveOneBusinessEffect() throws Exception {
        env.stock("DUPLICATE", 10);
        UUID order = env.createOrder(List.of(item("DUPLICATE", 2)));
        env.waitOrder(order, "COMPLETED");
        String created = env.payload(ORDER_DB, order, "ORDER_CREATED");
        env.sendRaw("order-events", order.toString(), created);
        env.waitCommitted("inventory-service", env.sendRaw("order-events", order.toString(), created));

        String confirmed = env.payload(INVENTORY_DB, order, "INVENTORY_CONFIRMED");
        env.sendRaw("inventory-events", order.toString(), confirmed);
        env.waitCommitted("order-service", env.sendRaw("inventory-events", order.toString(), confirmed));
        String resultId = env.json.readTree(confirmed).get("eventId").asText();
        assertEquals(1, env.number(ORDER_DB,
                "SELECT count(*) FROM processed_inventory_events WHERE event_id=?", UUID.fromString(resultId)));

        Map<String, Object> repeatConfirm = env.event(order, "INVENTORY_CONFIRM_REQUESTED", null);
        env.send("order-events", order.toString(), repeatConfirm);
        env.waitCommitted("inventory-service", env.send("order-events", order.toString(), repeatConfirm));
        assertEquals(1, env.number(INVENTORY_DB,
                "SELECT count(*) FROM processed_events WHERE event_id=?", UUID.fromString(repeatConfirm.get("eventId").toString())));
        assertStock("DUPLICATE", 8, 0);
        env.waitOrder(order, "COMPLETED");
        env.call("order", "POST", "/api/orders/" + order + "/cancel", null, 409);
    }

    @Test
    @Order(5)
    void releaseBeforeReserveAndLateResultsCannotReopenCancelledOrder() throws Exception {
        env.start("order", Map.of("orderflow.outbox.publish-delay-ms", "3600000"));
        try {
            env.stock("LATE-RESERVE", 6);
            UUID order = env.createOrder(List.of(item("LATE-RESERVE", 2)));
            env.call("order", "POST", "/api/orders/" + order + "/cancel", Map.of("reason", "Test cancellation"), 202);
            env.sendRaw("order-events", order.toString(), env.payload(ORDER_DB, order, "INVENTORY_RELEASE_REQUESTED"));
            env.waitOrder(order, "CANCELLED");
            String original = env.payload(ORDER_DB, order, "ORDER_CREATED");
            env.waitCommitted("inventory-service", env.sendRaw("order-events", order.toString(), original));
            env.waitCommitted("inventory-service", env.send("order-events", order.toString(),
                    env.event(order, "INVENTORY_RELEASE_REQUESTED", null)));
            env.waitCommitted("order-service", env.send("inventory-events", order.toString(),
                    env.event(order, "INVENTORY_RESERVED", null)));
            assertStock("LATE-RESERVE", 6, 0);
            assertEquals(0, env.number(INVENTORY_DB,
                    "SELECT count(*) FROM inventory_reservations WHERE order_id=?", order));
            env.waitOrder(order, "CANCELLED");
            assertEquals("RELEASED", env.text(INVENTORY_DB, "SELECT state FROM inventory_workflows WHERE order_id=?", order));
        } finally {
            env.start("order", Map.of());
        }
    }

    @Test
    @Order(6)
    void releaseFirstRestoresReservedStockOnlyOnce() throws Exception {
        env.start("order", Map.of("orderflow.outbox.publish-delay-ms", "3600000"));
        try {
            env.stock("RELEASE-TWICE", 8);
            UUID order = env.createOrder(List.of(item("RELEASE-TWICE", 3)));
            env.sendRaw("order-events", order.toString(), env.payload(ORDER_DB, order, "ORDER_CREATED"));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertStock("RELEASE-TWICE", 5, 3));
            env.call("order", "POST", "/api/orders/" + order + "/cancel", null, 202);
            String release = env.payload(ORDER_DB, order, "INVENTORY_RELEASE_REQUESTED");
            env.waitCommitted("inventory-service", env.sendRaw("order-events", order.toString(), release));
            env.waitOrder(order, "CANCELLED");
            env.waitCommitted("inventory-service", env.sendRaw("order-events", order.toString(), release));
            env.waitCommitted("inventory-service", env.send("order-events", order.toString(),
                    env.event(order, "INVENTORY_RELEASE_REQUESTED", null)));
            assertStock("RELEASE-TWICE", 8, 0);
            assertEquals("RELEASED", env.text(INVENTORY_DB,
                    "SELECT status FROM inventory_reservations WHERE order_id=?", order));
        } finally {
            env.start("order", Map.of());
        }
    }

    @Test
    @Order(7)
    void confirmationFirstWinsRacingCancellation() throws Exception {
        env.start("order", Map.of("orderflow.outbox.publish-delay-ms", "3600000"));
        try {
            env.stock("CONFIRM-RACE", 5);
            UUID order = env.createOrder(List.of(item("CONFIRM-RACE", 2)));
            env.waitCommitted("inventory-service", env.sendRaw("order-events", order.toString(),
                    env.payload(ORDER_DB, order, "ORDER_CREATED")));
            env.waitOrder(order, "CONFIRMED");
            // Keep the real cancellation API available while delaying result consumption.
            env.crash("order");
            env.start("order", Map.of("orderflow.outbox.publish-delay-ms", "3600000",
                    "spring.kafka.listener.auto-startup", "false"));
            env.waitCommitted("inventory-service", env.sendRaw("order-events", order.toString(),
                    env.payload(ORDER_DB, order, "INVENTORY_CONFIRM_REQUESTED")));
            assertStock("CONFIRM-RACE", 3, 0);
            env.call("order", "POST", "/api/orders/" + order + "/cancel",
                    Map.of("reason", "Cancellation race"), 202);
            assertEquals("CANCELLING", env.text(ORDER_DB, "SELECT status FROM orders WHERE id=?", order));
            env.waitCommitted("inventory-service", env.sendRaw("order-events", order.toString(),
                    env.payload(ORDER_DB, order, "INVENTORY_RELEASE_REQUESTED")));
            env.start("order", Map.of());
            env.waitOrder(order, "COMPLETED");
            assertStock("CONFIRM-RACE", 3, 0);
            assertEquals("CONFIRMED", env.text(INVENTORY_DB, "SELECT state FROM inventory_workflows WHERE order_id=?", order));
        } finally {
            // The normal order process was restored above; start also handles assertion failures.
            env.start("order", Map.of());
        }
    }

    @Test
    @Order(8)
    void concurrentOrdersCannotOversellLastItem() throws Exception {
        env.stock("LAST-ITEM", 1);
        List<UUID> orders;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<UUID> request = () -> {
                start.await();
                return env.createOrder(List.of(item("LAST-ITEM", 1)));
            };
            Future<UUID> first = executor.submit(request);
            Future<UUID> second = executor.submit(request);
            start.countDown();
            orders = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            List<String> statuses = new ArrayList<>();
            for (UUID order : orders) statuses.add(env.text(ORDER_DB, "SELECT status FROM orders WHERE id=?", order));
            assertTrue(statuses.contains("COMPLETED"));
            assertTrue(statuses.contains("CANCELLED"));
        });
        assertStock("LAST-ITEM", 0, 0);
        assertEquals(1, env.number(INVENTORY_DB,
                "SELECT count(*) FROM inventory_reservations WHERE sku='LAST-ITEM' AND status='CONFIRMED'"));
    }

    @Test
    @Order(9)
    void inventoryOutboxFailureKeepsAtomicResultAndRecoversAfterRestart() throws Exception {
        try {
            env.start("inventory", Map.of("spring.kafka.producer.bootstrap-servers", "localhost:19099",
                    "orderflow.outbox.max-attempts", "3"));
            env.stock("RESULT-FAILURE", 7);
            UUID order = env.createOrder(List.of(item("RESULT-FAILURE", 2)));
            await().atMost(Duration.ofSeconds(35)).untilAsserted(() -> assertEquals(1,
                    env.number(INVENTORY_DB, "SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND status='FAILED'", order)));
            assertStock("RESULT-FAILURE", 5, 2);
            assertEquals("PENDING", env.text(ORDER_DB, "SELECT status FROM orders WHERE id=?", order));
            UUID commandId = UUID.fromString(env.json.readTree(env.payload(ORDER_DB, order, "ORDER_CREATED")).get("eventId").asText());
            assertEquals(1, env.number(INVENTORY_DB, "SELECT count(*) FROM processed_events WHERE event_id=?", commandId));
            UUID failed = UUID.fromString(env.text(INVENTORY_DB,
                    "SELECT id FROM outbox_events WHERE aggregate_id=? AND status='FAILED'", order));
            env.crash("inventory");
            env.start("inventory", Map.of());
            env.call("inventory", "POST", "/api/inventory/outbox/" + failed + "/retry", null, 202);
            env.waitOrder(order, "COMPLETED");
            assertStock("RESULT-FAILURE", 5, 0);
            assertEquals("PUBLISHED", env.text(INVENTORY_DB, "SELECT status FROM outbox_events WHERE id=?", failed));
            assertEquals(1, env.number(INVENTORY_DB, "SELECT count(*) FROM inventory_reservations WHERE order_id=?", order));
        } finally {
            env.start("inventory", Map.of());
        }
    }

    @Test
    @Order(10)
    void brokerOutageRetainsOrderAndPublishesAutomaticallyAfterRecovery() throws Exception {
        env.stock("BROKER-OUTAGE", 4);
        UUID order;
        env.broker("stop");
        try {
            order = env.createOrder(List.of(item("BROKER-OUTAGE", 1)));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertTrue(env.number(ORDER_DB,
                    "SELECT retry_count FROM outbox_events WHERE aggregate_id=? AND event_type='ORDER_CREATED'", order) > 0));
            assertEquals("PENDING", env.text(ORDER_DB, "SELECT status FROM orders WHERE id=?", order));
            assertStock("BROKER-OUTAGE", 4, 0);
        } finally {
            env.broker("start");
        }
        env.waitOrder(order, "COMPLETED");
        assertStock("BROKER-OUTAGE", 3, 0);
        assertTrue(env.number(ORDER_DB,
                "SELECT retry_count FROM outbox_events WHERE aggregate_id=? AND event_type='ORDER_CREATED'", order) > 0);
    }

    @Test
    @Order(11)
    void databaseFailureRollsBackStockInboxAndOutboxThenReplaysFromDlt() throws Exception {
        env.stock("DATABASE-ROLLBACK", 9);
        env.sql(INVENTORY_DB, """
                CREATE FUNCTION it_fail_reserved_result() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.event_type = 'INVENTORY_RESERVED' THEN
                    RAISE EXCEPTION 'Injected result insert failure';
                  END IF;
                  RETURN NEW;
                END; $$
                """);
        env.sql(INVENTORY_DB, """
                CREATE TRIGGER it_fail_reserved_result BEFORE INSERT ON outbox_events
                FOR EACH ROW EXECUTE FUNCTION it_fail_reserved_result()
                """);
        UUID order;
        try (var observer = env.observer("order-events-dlt")) {
            order = env.createOrder(List.of(item("DATABASE-ROLLBACK", 2)));
            env.waitRecord(observer, order.toString(), null);
            assertStock("DATABASE-ROLLBACK", 9, 0);
            assertEquals(0, env.number(INVENTORY_DB, "SELECT count(*) FROM inventory_reservations WHERE order_id=?", order));
            assertEquals(0, env.number(INVENTORY_DB, "SELECT count(*) FROM inventory_workflows WHERE order_id=?", order));
            UUID commandId = UUID.fromString(env.json.readTree(env.payload(ORDER_DB, order, "ORDER_CREATED")).get("eventId").asText());
            assertEquals(0, env.number(INVENTORY_DB, "SELECT count(*) FROM processed_events WHERE event_id=?", commandId));
            assertEquals(0, env.number(INVENTORY_DB, "SELECT count(*) FROM outbox_events WHERE aggregate_id=?", order));
        } finally {
            env.sql(INVENTORY_DB, "DROP TRIGGER it_fail_reserved_result ON outbox_events");
            env.sql(INVENTORY_DB, "DROP FUNCTION it_fail_reserved_result()");
        }
        env.sendRaw("order-events", order.toString(), env.payload(ORDER_DB, order, "ORDER_CREATED"));
        env.waitOrder(order, "COMPLETED");
        assertStock("DATABASE-ROLLBACK", 7, 0);
    }

    @Test
    @Order(12)
    void poisonMessagesReachBothDltsWithoutBlockingValidOrders() throws Exception {
        for (String topic : List.of("order-events", "inventory-events")) {
            String key = UUID.randomUUID().toString();
            String invalidJson = "{invalid-json";
            try (var observer = env.observer(topic + "-dlt")) {
                env.sendRaw(topic, key, invalidJson);
                env.waitRecord(observer, key, invalidJson);
            }
        }
        env.stock("AFTER-DLT", 2);
        UUID order = env.createOrder(List.of(item("AFTER-DLT", 1)));
        env.waitOrder(order, "COMPLETED");
        assertStock("AFTER-DLT", 1, 0);
    }

    @Test
    @Order(13)
    void reservationTimeoutAndExhaustedCompensationSupportManualRecovery() throws Exception {
        env.stop("inventory");
        UUID order;
        try {
            order = env.createOrder(List.of(item("TIMEOUT-NO-STOCK", 1)));
            env.sql(ORDER_DB, "UPDATE order_sagas SET deadline=NOW()-INTERVAL '1 second' WHERE order_id=?", order);
            env.waitOrder(order, "CANCELLING");
            env.sql(ORDER_DB, "UPDATE order_sagas SET retry_count=2,deadline=NOW()-INTERVAL '1 second' WHERE order_id=?", order);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("MANUAL_RECOVERY",
                    env.text(ORDER_DB, "SELECT state FROM order_sagas WHERE order_id=?", order)));
            JsonNode retry = env.call("order", "POST", "/api/orders/" + order + "/saga/retry", null, 200);
            assertEquals("COMPENSATING", retry.get("state").asText());
            assertEquals(0, retry.get("retryCount").asInt());
            env.call("order", "POST", "/api/orders/" + order + "/saga/retry", null, 409);
        } finally {
            env.start("inventory", Map.of());
        }
        env.waitOrder(order, "CANCELLED");
        assertEquals("RELEASED", env.text(INVENTORY_DB, "SELECT state FROM inventory_workflows WHERE order_id=?", order));
    }

    @Test
    @Order(14)
    void crashAfterOutboxClaimRecoversAndFailedCommandsCanBeRetried() throws Exception {
        env.start("order", Map.of("orderflow.outbox.publish-delay-ms", "3600000"));
        env.stock("STALE-CLAIM", 3);
        UUID order;
        UUID outbox;
        try {
            order = env.createOrder(List.of(item("STALE-CLAIM", 1)));
            outbox = UUID.fromString(env.text(ORDER_DB, "SELECT id FROM outbox_events WHERE aggregate_id=?", order));
            env.sql(ORDER_DB, """
                    UPDATE outbox_events SET status='PROCESSING',locked_by='crashed-worker',
                        locked_at=NOW()-INTERVAL '10 seconds' WHERE id=?
                    """, outbox);
            env.crash("order");
        } finally {
            env.start("order", Map.of());
        }
        env.waitOrder(order, "COMPLETED");
        assertStock("STALE-CLAIM", 2, 0);
        assertEquals("PUBLISHED", env.text(ORDER_DB, "SELECT status FROM outbox_events WHERE id=?", outbox));
        assertNull(env.text(ORDER_DB, "SELECT locked_by FROM outbox_events WHERE id=?", outbox));

        env.start("order", Map.of("orderflow.outbox.publish-delay-ms", "3600000"));
        UUID retryOrder;
        try {
            env.stock("FAILED-COMMAND", 2);
            retryOrder = env.createOrder(List.of(item("FAILED-COMMAND", 1)));
            UUID failed = UUID.fromString(env.text(ORDER_DB, "SELECT id FROM outbox_events WHERE aggregate_id=?", retryOrder));
            env.sql(ORDER_DB, "UPDATE outbox_events SET status='FAILED',retry_count=3,last_error='Injected failure' WHERE id=?", failed);
            env.call("order", "POST", "/api/orders/outbox/" + failed + "/retry", null, 202);
            assertEquals("PENDING", env.text(ORDER_DB, "SELECT status FROM outbox_events WHERE id=?", failed));
            assertEquals(0, env.number(ORDER_DB, "SELECT retry_count FROM outbox_events WHERE id=?", failed));
            env.call("order", "POST", "/api/orders/outbox/" + failed + "/retry", null, 409);
        } finally {
            env.start("order", Map.of());
        }
        env.waitOrder(retryOrder, "COMPLETED");
    }

    @Test
    @Order(15)
    void postgresSkipLockedAllowsTwoWorkersToClaimDifferentEvents() throws Exception {
        env.start("order", Map.of("orderflow.outbox.publish-delay-ms", "3600000"));
        try {
            env.stock("WORKER-LOCK", 4);
            env.createOrder(List.of(item("WORKER-LOCK", 1)));
            env.createOrder(List.of(item("WORKER-LOCK", 1)));
            String claim = """
                    SELECT id FROM outbox_events WHERE status='PENDING'
                    ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """;
            try (Connection first = env.connection(ORDER_DB); Connection second = env.connection(ORDER_DB)) {
                first.setAutoCommit(false);
                second.setAutoCommit(false);
                UUID firstId;
                UUID secondId;
                try (Statement query = first.createStatement(); ResultSet rows = query.executeQuery(claim)) {
                    assertTrue(rows.next());
                    firstId = rows.getObject(1, UUID.class);
                }
                try (Statement query = second.createStatement(); ResultSet rows = query.executeQuery(claim)) {
                    assertTrue(rows.next());
                    secondId = rows.getObject(1, UUID.class);
                }
                assertNotEquals(firstId, secondId);
                first.rollback();
                second.rollback();
            }
        } finally {
            env.start("order", Map.of());
        }
    }

    @Test
    @Order(16)
    void invalidOrdersReturnBadRequestWithoutPersistingBusinessState() throws Exception {
        long orders = env.number(ORDER_DB, "SELECT count(*) FROM orders");
        for (List<Map<String, Object>> items : List.of(List.<Map<String, Object>>of(),
                List.of(item("INVALID", 0)), List.of(item("", 1)))) {
            env.call("order", "POST", "/api/orders", Map.of("customerId", UUID.randomUUID().toString(),
                    "currency", "USD", "totalAmount", 10, "items", items), 400);
        }
        assertEquals(orders, env.number(ORDER_DB, "SELECT count(*) FROM orders"));
    }

    @Test
    @Order(17)
    void legacyOrderPayloadPublishesThroughOutbox() throws Exception {
        env.stock("LEGACY-COMMAND", 5);
        UUID order = UUID.randomUUID();
        Map<String, Object> legacy = env.event(order, "ORDER_CREATED", List.of(item("LEGACY-COMMAND", 2)));
        legacy.remove("eventType");
        legacy.remove("schemaVersion");
        legacy.put("status", "PENDING");
        legacy.put("customerId", UUID.randomUUID().toString());
        legacy.put("totalAmount", 10);
        legacy.put("currency", "USD");
        LegacyOrderFixture.insert(env, order, "LEGACY-COMMAND", 2, env.json.writeValueAsString(legacy));
        env.waitOrder(order, "COMPLETED");
        assertStock("LEGACY-COMMAND", 3, 0);
        assertEquals("PUBLISHED", env.text(ORDER_DB,
                "SELECT status FROM outbox_events WHERE aggregate_id=? AND event_type='ORDER_CREATED'", order));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESERVED", "REJECTED"})
    @Order(18)
    void legacyInventoryResultIsAccepted(String status) throws Exception {
        String sku = "LEGACY-RESULT-" + status;
        env.stock(sku, 5);
        UUID order = UUID.randomUUID();
        LegacyOrderFixture.insert(env, order, sku, 2, null);
        if (status.equals("RESERVED")) {
            env.call("inventory", "POST", "/api/inventory/reserve",
                    Map.of("orderId", order.toString(), "sku", sku, "quantity", 2), 201);
        }
        Map<String, Object> legacy = env.event(order, "INVENTORY_" + status, null);
        legacy.remove("eventType");
        legacy.remove("schemaVersion");
        legacy.put("status", status);
        if (status.equals("REJECTED")) legacy.put("reason", "Legacy inventory rejection");
        env.send("inventory-events", order.toString(), legacy);
        env.waitOrder(order, status.equals("RESERVED") ? "COMPLETED" : "CANCELLED");
        assertStock(sku, status.equals("RESERVED") ? 3 : 5, 0);
    }

    private void assertStock(String sku, int available, int reserved) throws Exception {
        assertEquals(available, env.number(INVENTORY_DB, "SELECT quantity FROM inventory WHERE sku=?", sku));
        assertEquals(reserved, env.number(INVENTORY_DB, "SELECT reserved_quantity FROM inventory WHERE sku=?", sku));
    }
}
