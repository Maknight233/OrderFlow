package com.rom.orderflow.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.rom.orderflow.integration.TestEnvironment.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResilienceSagaIntegrationTest {
    private TestEnvironment env;
    private HttpServer orderProxy, inventoryProxy;
    private ExecutorService workers;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final AtomicReference<String> fault = new AtomicReference<>("none");
    private final AtomicReference<UUID> committed = new AtomicReference<>();
    private final AtomicInteger postCalls = new AtomicInteger(), inventoryCalls = new AtomicInteger(), getCalls = new AtomicInteger();

    @BeforeAll void start() throws Exception {
        env = new TestEnvironment();
        env.prepare();
        // Warm first-use JPA/JSON/Kafka initialization without the Gateway deadline.
        // Fault assertions then isolate committed-response loss instead of cold startup latency.
        env.stock("RESILIENCE-WARMUP", 1);
        UUID warmup = env.createOrder(List.of(item("RESILIENCE-WARMUP", 1)));
        env.waitOrder(warmup, "COMPLETED");
        workers = Executors.newCachedThreadPool();
        orderProxy = proxy(18084, "order");
        inventoryProxy = proxy(18085, "inventory");
        env.start("gateway", Map.of("spring.cloud.gateway.server.webflux.routes[0].uri", "http://localhost:18084",
                "spring.cloud.gateway.server.webflux.routes[1].uri", "http://localhost:18085"));
    }
    @AfterAll void stop() throws Exception {
        try { if (env != null) env.close(); }
        finally {
            if (orderProxy != null) orderProxy.stop(0);
            if (inventoryProxy != null) inventoryProxy.stop(0);
            if (workers != null) workers.shutdownNow();
        }
    }
    @BeforeEach void report(TestInfo test) { System.out.println("Day12 Saga scenario: " + test.getDisplayName()); }

    @Test @Order(1) void lostCreateResponseDoesNotReplayPostOrDuplicateStockDeduction() throws Exception {
        for (String mode : List.of("post-503", "post-reset")) {
            String sku = "RESILIENCE-" + mode;
            env.stock(sku, 5);
            long rows = env.number(ORDER_DB, "SELECT count(*) FROM orders");
            int calls = postCalls.get();
            fault.set(mode);
            try {
                var response = env.request("gateway", "POST", "/api/orders", Map.of("customerId", UUID.randomUUID(),
                        "totalAmount", 10, "currency", "USD", "items", List.of(item(sku, 1))));
                assertEquals(503, response.statusCode(), response.body());
                assertFalse(env.json.readTree(response.body()).has("id"));
            } finally { fault.set("none"); }
            assertEquals(calls + 1, postCalls.get(), "Only one HTTP POST may reach Order");
            UUID id = committed.get();
            assertNotNull(id);
            env.waitOrder(id, "COMPLETED");
            assertEquals(rows + 1, env.number(ORDER_DB, "SELECT count(*) FROM orders"));
            assertEquals(4, env.number(INVENTORY_DB, "SELECT quantity FROM inventory WHERE sku=?", sku));
            assertEquals(1, env.number(INVENTORY_DB, "SELECT count(*) FROM inventory_reservations WHERE order_id=?", id));
        }
    }

    @Test @Order(2) void transientGetAndDuplicateKafkaResultCannotChangeCompletedWorkflow() throws Exception {
        UUID id = committed.get();
        long version = env.number(ORDER_DB, "SELECT version FROM orders WHERE id=?", id);
        long rows = env.number(ORDER_DB, "SELECT count(*) FROM orders");
        getCalls.set(0);
        fault.set("get-once");
        try {
            var response = env.call("gateway", "GET", "/api/orders/" + id, null, 200);
            assertEquals("COMPLETED", response.get("status").asText());
            assertEquals(2, getCalls.get());
        } finally { fault.set("none"); }
        String result = env.payload(INVENTORY_DB, id, "INVENTORY_CONFIRMED");
        env.waitCommitted("order-service", env.sendRaw("inventory-events", id.toString(), result));
        assertEquals(version, env.number(ORDER_DB, "SELECT version FROM orders WHERE id=?", id));
        assertEquals(rows, env.number(ORDER_DB, "SELECT count(*) FROM orders"));
        assertEquals("COMPLETED", env.text(ORDER_DB, "SELECT status FROM orders WHERE id=?", id));
    }

    @Test @Order(3) void inventoryHttpCircuitDoesNotMutateStockOrStopIndependentKafkaSaga() throws Exception {
        env.stock("RESILIENCE-INVENTORY", 7);
        fault.set("inventory-503");
        int calls = inventoryCalls.get();
        try {
            for (int i = 0; i < 10; i++) {
                env.call("gateway", "POST", "/api/inventory/reserve", Map.of("orderId", UUID.randomUUID(),
                        "sku", "RESILIENCE-INVENTORY", "quantity", 1), 503);
            }
            assertEquals(calls + 10, inventoryCalls.get());
            var rejected = env.call("gateway", "POST", "/api/inventory/reserve", Map.of("orderId", UUID.randomUUID(),
                    "sku", "RESILIENCE-INVENTORY", "quantity", 1), 503);
            assertEquals("CIRCUIT_OPEN", rejected.get("code").asText());
            assertEquals(calls + 10, inventoryCalls.get());
            assertEquals(7, env.number(INVENTORY_DB, "SELECT quantity FROM inventory WHERE sku=?", "RESILIENCE-INVENTORY"));
            var response = env.call("gateway", "POST", "/api/orders", Map.of("customerId", UUID.randomUUID(),
                    "totalAmount", 10, "currency", "USD", "items", List.of(item("RESILIENCE-INVENTORY", 2))), 201);
            UUID id = UUID.fromString(response.get("id").asText());
            env.waitOrder(id, "COMPLETED");
            assertEquals(5, env.number(INVENTORY_DB, "SELECT quantity FROM inventory WHERE sku=?", "RESILIENCE-INVENTORY"));
        } finally { fault.set("none"); }
    }

    @Test @Order(4) void stoppedOrderHttpOpensCircuitWithoutCreatingOrdersThenRecovers() throws Exception {
        long rows = env.number(ORDER_DB, "SELECT count(*) FROM orders");
        UUID id = committed.get();
        env.stop("order");
        try {
            for (int i = 0; i < 10; i++) env.call("gateway", "GET", "/api/orders/" + id, null, 503);
            assertEquals("CIRCUIT_OPEN", env.call("gateway", "POST", "/api/orders", Map.of(), 503).get("code").asText());
            assertEquals(rows, env.number(ORDER_DB, "SELECT count(*) FROM orders"));
            assertEquals("COMPLETED", env.text(ORDER_DB, "SELECT status FROM orders WHERE id=?", id));
        } finally { env.start("order", Map.of()); }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertEquals("COMPLETED", env.call("gateway", "GET", "/api/orders/" + id, null, 200).get("status").asText()));
        for (int i = 0; i < 3; i++) env.call("gateway", "GET", "/api/orders/" + id, null, 200);
        assertEquals(rows, env.number(ORDER_DB, "SELECT count(*) FROM orders"));
    }

    private HttpServer proxy(int port, String service) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod(), path = exchange.getRequestURI().toString();
            byte[] body = exchange.getRequestBody().readAllBytes();
            String mode = fault.get();
            try {
                int status;
                byte[] reply;
                if (service.equals("inventory") && mode.equals("inventory-503")) {
                    inventoryCalls.incrementAndGet(); status = 503; reply = "{}".getBytes();
                } else if (service.equals("order") && method.equals("GET") && mode.equals("get-once") && getCalls.incrementAndGet() == 1) {
                    status = 503; reply = "{}".getBytes();
                } else {
                    if (service.equals("order") && method.equals("POST") && path.equals("/api/orders")) postCalls.incrementAndGet();
                    int upstreamPort = service.equals("order") ? 18081 : 18082;
                    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + upstreamPort + path)).timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build();
                    var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    status = response.statusCode(); reply = response.body();
                    if (service.equals("order") && method.equals("POST") && path.equals("/api/orders") && status == 201) {
                        committed.set(UUID.fromString(env.json.readTree(reply).get("id").asText()));
                        if (mode.equals("post-reset")) { exchange.close(); return; }
                        if (mode.equals("post-503")) { status = 503; reply = "{}".getBytes(); }
                    }
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, reply.length);
                exchange.getResponseBody().write(reply);
            } catch (Exception ex) {
                try { exchange.sendResponseHeaders(503, 2); exchange.getResponseBody().write("{}".getBytes()); } catch (Exception ignored) { }
            } finally { exchange.close(); }
        });
        server.start();
        return server;
    }
}
