package com.rom.orderflow.integration;

import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import static com.rom.orderflow.integration.TestEnvironment.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObservabilityIntegrationTest {
    private TestEnvironment env;

    @BeforeAll
    void start() throws Exception {
        env = new TestEnvironment();
        env.prepare();
        env.start("gateway", Map.of());
    }

    @AfterAll
    void stop() throws Exception { if (env != null) env.close(); }

    @BeforeEach
    void report(TestInfo test) { System.out.println("Day10 scenario: " + test.getDisplayName()); }

    @Test
    @Order(1)
    void probesAndPrometheusEndpointsExposeOnlyIntendedData() throws Exception {
        for (String service : List.of("order", "inventory", "gateway")) {
            for (String endpoint : List.of("health", "health/liveness", "health/readiness")) {
                JsonNode health = env.call(service, "GET", "/actuator/" + endpoint, null, 200);
                assertEquals("UP", health.get("status").asText());
                assertFalse(health.has("components"), "Health details must remain hidden");
            }
            String scrape = metrics(service);
            assertTrue(scrape.contains("http_server_requests_seconds_count"), scrape);
            assertTrue(scrape.contains("application=\"" + service + "-service\""));
            assertEquals(404, env.request(service, "GET", "/actuator/env", null).statusCode());
        }
        awaitMetric("order", "orderflow_metrics_snapshot_available", Map.of(), 1);
        awaitMetric("inventory", "orderflow_metrics_snapshot_available", Map.of(), 1);
    }

    @Test
    @Order(2)
    void gatewayOrderKafkaResultsShareOneTraceAndCommittedMetrics() throws Exception {
        env.stock("OBS-NORMAL", 5);
        String traceId = traceId();
        double completedBefore = metric("order", "orderflow_saga_duration_seconds_count", Map.of("outcome", "COMPLETED"));
        UUID order = createTracedOrder("OBS-NORMAL", traceId);
        env.waitOrder(order, "COMPLETED");
        awaitMetric("order", "orderflow_saga_duration_seconds_count", Map.of("outcome", "COMPLETED"), completedBefore + 1);
        assertTrace(order, traceId);
        assertStructuredLogs(order, traceId);
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            JsonNode query = monitor("http://localhost:19090/api/v1/query?query=" +
                    java.net.URLEncoder.encode("orderflow_saga_orders{state=\"COMPLETED\"}", java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("success", query.get("status").asText());
            assertFalse(query.get("data").get("result").isEmpty());
            assertTrue(query.get("data").get("result").get(0).get("value").get(1).asDouble() >= 1);
        });
    }

    @Test
    @Order(3)
    void brokerOutageKeepsReadinessAndTraceWhileMetricsShowFailure() throws Exception {
        env.stock("OBS-BROKER", 3);
        String traceId = traceId();
        double failures = metric("order", "orderflow_outbox_attempts_total", Map.of("outcome", "failure"));
        UUID order;
        env.broker("stop");
        try {
            order = createTracedOrder("OBS-BROKER", traceId);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertEquals(503, env.request("order", "GET", "/actuator/health", null).statusCode()));
            for (String service : List.of("order", "inventory")) {
                env.call(service, "GET", "/actuator/health/liveness", null, 200);
                env.call(service, "GET", "/actuator/health/readiness", null, 200);
            }
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertTrue(metric("order", "orderflow_outbox_attempts_total", Map.of("outcome", "failure")) > failures));
            assertEquals("PENDING", env.text(ORDER_DB, "SELECT status FROM orders WHERE id=?", order));
            double age = metric("order", "orderflow_outbox_oldest_age_seconds", Map.of());
            assertTrue(age >= 0 && age < 60, "Outbox age must interpret local timestamps correctly: " + age);
            assertTrue(metric("order", "orderflow_outbox_events", Map.of("status", "PENDING")) +
                    metric("order", "orderflow_outbox_events", Map.of("status", "PROCESSING")) >= 1);
        } finally { env.broker("start"); }
        env.waitOrder(order, "COMPLETED");
        assertTrace(order, traceId);
        assertTrue(metric("order", "orderflow_outbox_retries_total", Map.of()) >= 1);
    }

    @Test
    @Order(4)
    void persistedTraceSurvivesOrderProcessCrash() throws Exception {
        env.start("order", Map.of("orderflow.outbox.publish-delay-ms", "3600000"));
        env.stock("OBS-CRASH", 3);
        String traceId = traceId();
        UUID order = createTracedOrder("OBS-CRASH", traceId);
        assertTrue(env.text(ORDER_DB, "SELECT trace_parent FROM outbox_events WHERE aggregate_id=?", order).contains(traceId));
        env.sql(ORDER_DB, """
                UPDATE outbox_events SET status='PROCESSING',locked_by='test-crash',
                    locked_at=NOW()-INTERVAL '10 seconds' WHERE aggregate_id=?
                """, order);
        env.crash("order");
        env.start("order", Map.of());
        env.waitOrder(order, "COMPLETED");
        assertTrace(order, traceId);
    }

    @Test
    @Order(5)
    void poisonMessagePublishesDltMetricAndRealPrometheusAlert() throws Exception {
        // Ensure Prometheus observes the counter before its increase.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode query = monitor("http://localhost:19090/api/v1/query?query=orderflow_kafka_dlt_published_total");
            assertFalse(query.get("data").get("result").isEmpty());
        });
        for (String topic : List.of("order-events", "inventory-events")) {
            String service = topic.equals("order-events") ? "inventory" : "order";
            double before = metric(service, "orderflow_kafka_dlt_published_total", Map.of("topic", topic + "-dlt"));
            String key = UUID.randomUUID().toString();
            try (var observer = env.observer(topic + "-dlt")) {
                env.sendRaw(topic, key, "{invalid-json");
                env.waitRecord(observer, key, "{invalid-json");
            }
            awaitMetric(service, "orderflow_kafka_dlt_published_total", Map.of("topic", topic + "-dlt"), before + 1);
        }
        awaitAlert("DltMessages");
    }

    @Test
    @Order(6)
    void failedOutboxAndManualSagaGaugesReachAlertmanagerAndRecover() throws Exception {
        env.start("order", Map.of("orderflow.outbox.publish-delay-ms", "3600000"));
        env.stock("OBS-MANUAL", 3);
        UUID order = createTracedOrder("OBS-MANUAL", traceId());
        UUID eventId = UUID.fromString(env.text(ORDER_DB, "SELECT id FROM outbox_events WHERE aggregate_id=?", order));
        env.sql(ORDER_DB, "UPDATE outbox_events SET status='FAILED',retry_count=5,last_error='Day10 injected exhaustion' WHERE id=?", eventId);
        env.sql(ORDER_DB, "UPDATE order_sagas SET state='MANUAL_RECOVERY',recovery_state='WAITING_RESERVATION' WHERE order_id=?", order);
        awaitMetric("order", "orderflow_outbox_events", Map.of("status", "FAILED"), 1);
        awaitMetric("order", "orderflow_saga_orders", Map.of("state", "MANUAL_RECOVERY"), 1);
        awaitAlert("OutboxFailed");
        awaitAlert("SagaManualRecovery");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode alerts = monitor("http://localhost:19093/api/v2/alerts");
            Set<String> names = new HashSet<>();
            alerts.forEach(a -> names.add(a.get("labels").get("alertname").asText()));
            assertTrue(names.containsAll(Set.of("OutboxFailed", "SagaManualRecovery")), alerts.toString());
        });
        env.call("order", "POST", "/api/orders/" + order + "/saga/retry", null, 200);
        env.call("order", "POST", "/api/orders/outbox/" + eventId + "/retry", null, 202);
        env.start("order", Map.of());
        env.waitOrder(order, "COMPLETED");
        awaitMetric("order", "orderflow_outbox_events", Map.of("status", "FAILED"), 0);
        awaitMetric("order", "orderflow_saga_orders", Map.of("state", "MANUAL_RECOVERY"), 0);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode alerts = monitor("http://localhost:19090/api/v1/alerts").get("data").get("alerts");
            alerts.forEach(a -> assertFalse(Set.of("OutboxFailed", "SagaManualRecovery")
                    .contains(a.get("labels").get("alertname").asText()), alerts.toString()));
        });
    }

    @Test
    @Order(7)
    void databaseOutageFailsReadinessButPreservesLivenessAndMetricScrapes() throws Exception {
        Process stop = new ProcessBuilder("docker", "compose", "-f", env.root.resolve("docker-compose.integration.yml").toString(),
                "-p", "orderflow-it", "stop", "postgres").start();
        assertTrue(stop.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, stop.exitValue());
        try {
            for (String service : List.of("order", "inventory")) {
                await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                        assertEquals(503, env.request(service, "GET", "/actuator/health/readiness", null).statusCode()));
                env.call(service, "GET", "/actuator/health/liveness", null, 200);
                assertTrue(metrics(service).contains("orderflow_metrics_snapshot_available"));
                awaitMetric(service, "orderflow_metrics_snapshot_available", Map.of(), 0);
            }
        } finally {
            Process start = new ProcessBuilder("docker", "compose", "-f", env.root.resolve("docker-compose.integration.yml").toString(),
                    "-p", "orderflow-it", "start", "postgres").start();
            assertTrue(start.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, start.exitValue());
        }
        for (String service : List.of("order", "inventory")) {
            await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                    env.call(service, "GET", "/actuator/health/readiness", null, 200));
            awaitMetric(service, "orderflow_metrics_snapshot_available", Map.of(), 1);
        }
        env.stock("OBS-DB-RECOVERED", 2);
        UUID order = createTracedOrder("OBS-DB-RECOVERED", traceId());
        env.waitOrder(order, "COMPLETED");
    }

    @Test
    @Order(8)
    void rolledBackSagaTransitionAndDuplicateResultDoNotInflateCounters() throws Exception {
        env.stock("OBS-ROLLBACK", 4);
        env.sql(ORDER_DB, """
                CREATE FUNCTION obs_fail_confirmation() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.event_type = 'INVENTORY_CONFIRM_REQUESTED' THEN
                    RAISE EXCEPTION 'Day10 injected order transaction failure';
                  END IF;
                  RETURN NEW;
                END; $$
                """);
        env.sql(ORDER_DB, """
                CREATE TRIGGER obs_fail_confirmation BEFORE INSERT ON outbox_events
                FOR EACH ROW EXECUTE FUNCTION obs_fail_confirmation()
                """);
        double completed = metric("order", "orderflow_saga_duration_seconds_count", Map.of("outcome", "COMPLETED"));
        double transitions = metric("order", "orderflow_saga_transitions_total", Map.of("from", "WAITING_RESERVATION", "to", "WAITING_CONFIRMATION"));
        UUID order;
        try (var observer = env.observer("inventory-events-dlt")) {
            order = createTracedOrder("OBS-ROLLBACK", traceId());
            env.waitRecord(observer, order.toString(), null);
            assertEquals("WAITING_RESERVATION", env.text(ORDER_DB, "SELECT state FROM order_sagas WHERE order_id=?", order));
            assertEquals(0, env.number(ORDER_DB, "SELECT count(*) FROM processed_inventory_events WHERE order_id=?", order));
            assertEquals(transitions, metric("order", "orderflow_saga_transitions_total", Map.of("from", "WAITING_RESERVATION", "to", "WAITING_CONFIRMATION")));
            assertEquals(completed, metric("order", "orderflow_saga_duration_seconds_count", Map.of("outcome", "COMPLETED")));
        } finally {
            env.sql(ORDER_DB, "DROP TRIGGER obs_fail_confirmation ON outbox_events");
            env.sql(ORDER_DB, "DROP FUNCTION obs_fail_confirmation()");
        }
        String result = env.payload(INVENTORY_DB, order, "INVENTORY_RESERVED");
        env.sendRaw("inventory-events", order.toString(), result);
        env.waitOrder(order, "COMPLETED");
        awaitMetric("order", "orderflow_saga_duration_seconds_count", Map.of("outcome", "COMPLETED"), completed + 1);
        var duplicate = env.sendRaw("inventory-events", order.toString(), result);
        env.waitCommitted("order-service", duplicate);
        assertEquals(transitions + 1, metric("order", "orderflow_saga_transitions_total", Map.of("from", "WAITING_RESERVATION", "to", "WAITING_CONFIRMATION")));
        assertEquals(completed + 1, metric("order", "orderflow_saga_duration_seconds_count", Map.of("outcome", "COMPLETED")));
    }

    @Test
    @Order(9)
    void grafanaProvisionsDashboardAndBothDatasources() throws Exception {
        String auth = "Basic " + Base64.getEncoder().encodeToString("admin:orderflow-local".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode dashboard = monitor("http://localhost:13000/api/dashboards/uid/orderflow-overview", Map.of("Authorization", auth));
        assertEquals(12, dashboard.get("dashboard").get("panels").size());
        JsonNode resilience = monitor("http://localhost:13000/api/dashboards/uid/orderflow-resilience", Map.of("Authorization", auth));
        assertEquals(6, resilience.get("dashboard").get("panels").size());
        Set<String> queries = new HashSet<>();
        resilience.get("dashboard").get("panels").forEach(panel -> {
            assertEquals("orderflow-prometheus", panel.get("datasource").get("uid").asText());
            queries.add(panel.get("targets").get(0).get("expr").asText());
        });
        assertTrue(queries.stream().anyMatch(query -> query.contains("resilience4j_circuitbreaker_state")));
        assertTrue(queries.stream().anyMatch(query -> query.contains("orderflow_gateway_retries_total")));
        assertTrue(queries.stream().anyMatch(query -> query.contains("orderflow_gateway_timeouts_total")));
        assertTrue(queries.stream().anyMatch(query -> query.contains("orderflow_gateway_rejected_total")));
        JsonNode sources = monitor("http://localhost:13000/api/datasources", Map.of("Authorization", auth));
        Set<String> ids = new HashSet<>();
        sources.forEach(s -> ids.add(s.get("uid").asText()));
        assertTrue(ids.containsAll(Set.of("orderflow-prometheus", "orderflow-zipkin")));
    }

    private UUID createTracedOrder(String sku, String traceId) throws Exception {
        var response = env.request("gateway", "POST", "/api/orders", Map.of(
                "customerId", UUID.randomUUID().toString(), "totalAmount", 10, "currency", "USD",
                "items", List.of(item(sku, 1))), Map.of("traceparent", "00-" + traceId + "-0123456789abcdef-01"));
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(env.json.readTree(response.body()).get("id").asText());
    }

    private String traceId() { return UUID.randomUUID().toString().replace("-", ""); }

    private void assertTrace(UUID order, String traceId) {
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            JsonNode spans = monitor("http://localhost:19411/api/v2/trace/" + traceId);
            Set<String> services = new HashSet<>();
            boolean outbox = false;
            for (JsonNode span : spans) {
                assertEquals(traceId, span.get("traceId").asText());
                if (span.has("localEndpoint")) services.add(span.get("localEndpoint").get("serviceName").asText());
                if (span.has("tags") && span.get("tags").has("orderId")) {
                    outbox |= order.toString().equals(span.get("tags").get("orderId").asText());
                }
            }
            assertTrue(services.containsAll(Set.of("gateway-service", "order-service", "inventory-service")), spans.toString());
            assertTrue(outbox, spans.toString());
            Files.writeString(env.logs.resolve("trace-" + traceId + ".json"), spans.toString());
            for (String db : List.of(ORDER_DB, INVENTORY_DB)) {
                assertEquals(0, env.number(db, "SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND (trace_parent IS NULL OR trace_parent NOT LIKE ?)",
                        order, "%" + traceId + "%"));
            }
        });
    }

    private void assertStructuredLogs(UUID order, String traceId) throws Exception {
        boolean orderLog = false, inventoryLog = false;
        try (var files = Files.list(env.logs)) {
            for (var file : files.filter(p -> p.toString().endsWith(".log")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (!line.startsWith("{")) continue;
                    JsonNode log = env.json.readTree(line);
                    if (!log.has("orderId") || !order.toString().equals(log.get("orderId").asText())) continue;
                    assertEquals(traceId, log.get("traceId").asText(), line);
                    if (log.has("eventId") && log.has("eventType")) {
                        orderLog |= file.getFileName().toString().startsWith("order-");
                        inventoryLog |= file.getFileName().toString().startsWith("inventory-");
                    }
                }
            }
        }
        assertTrue(orderLog && inventoryLog, "Both services must log correlated event fields");
    }

    private String metrics(String service) throws Exception {
        var response = env.request(service, "GET", "/actuator/prometheus", null);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private double metric(String service, String name, Map<String, String> labels) throws Exception {
        for (String line : metrics(service).split("\n")) {
            if (!line.startsWith(name + "{") && !line.startsWith(name + " ")) continue;
            if (labels.entrySet().stream().allMatch(e -> line.contains(e.getKey() + "=\"" + e.getValue() + "\""))) {
                return Double.parseDouble(line.substring(line.indexOf(' ') + 1).split(" ")[0]);
            }
        }
        fail("Metric missing: " + name + labels);
        return 0;
    }

    private void awaitMetric(String service, String name, Map<String, String> labels, double expected) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(expected, metric(service, name, labels)));
    }

    private JsonNode monitor(String url) throws Exception { return monitor(url, Map.of()); }

    private JsonNode monitor(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        headers.forEach(request::header);
        var response = env.http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return env.json.readTree(response.body());
    }

    private void awaitAlert(String name) {
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            JsonNode alerts = monitor("http://localhost:19090/api/v1/alerts").get("data").get("alerts");
            boolean firing = false;
            for (JsonNode alert : alerts) {
                firing |= name.equals(alert.get("labels").get("alertname").asText()) && "firing".equals(alert.get("state").asText());
            }
            assertTrue(firing, name + ": " + alerts);
            Files.writeString(env.logs.resolve("alert-" + name + ".json"), alerts.toString());
        });
    }
}
