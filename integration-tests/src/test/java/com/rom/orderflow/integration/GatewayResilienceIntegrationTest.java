package com.rom.orderflow.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayResilienceIntegrationTest {
    private HttpServer downstream;
    private ExecutorService workers;
    private Process gateway;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final List<Long> retryTimes = new CopyOnWriteArrayList<>();
    private final AtomicBoolean unavailable = new AtomicBoolean();
    private final CountDownLatch blocked = new CountDownLatch(2), release = new CountDownLatch(1);
    private final Path root = Path.of(System.getProperty("orderflow.projectRoot")).toAbsolutePath().normalize();
    private Path log;

    @BeforeAll void start() throws Exception {
        downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 18084), 0);
        workers = Executors.newCachedThreadPool();
        downstream.setExecutor(workers);
        downstream.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int call = counts.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            int status = unavailable.get() ? 503 : 200;
            try {
                if (path.endsWith("/temporary")) { retryTimes.add(System.nanoTime()); status = call == 1 ? 503 : 200; }
                if (path.endsWith("/bad-request")) status = 400;
                if (path.endsWith("/post-failure")) status = 503;
                if (path.endsWith("/post-reset")) { exchange.close(); return; }
                if (path.endsWith("/slow")) Thread.sleep(5000);
                if (path.endsWith("/blocked")) { blocked.countDown(); release.await(10, TimeUnit.SECONDS); }
                byte[] body = "{\"downstream\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { /* Timed-out clients close their connection. */ }
            finally { exchange.close(); }
        });
        downstream.start();
        Path logs = root.resolve("integration-tests/target/service-logs");
        Files.createDirectories(logs);
        log = logs.resolve("day12-gateway-" + System.currentTimeMillis() + ".log");
        String javaName = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", javaName).toString(),
                "-Xmx256m", "-XX:TieredStopAtLevel=1", "-jar", root.resolve("gateway-service/target/gateway-service-0.0.1-SNAPSHOT.jar").toString(),
                "--server.port=19000", "--eureka.client.enabled=false", "--spring.cloud.discovery.enabled=false",
                "--management.tracing.export.zipkin.enabled=false", "--spring.cloud.inetutils.timeout-seconds=1",
                "--orderflow.resilience.max-concurrent-calls=2",
                "--spring.cloud.gateway.server.webflux.routes[0].id=order-service",
                "--spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/api/orders/**",
                "--spring.cloud.gateway.server.webflux.routes[0].filters[0].name=Resilient",
                "--spring.cloud.gateway.server.webflux.routes[0].filters[0].args.name=order-service",
                "--spring.cloud.gateway.server.webflux.routes[0].uri=http://localhost:18084",
                "--spring.cloud.gateway.server.webflux.routes[1].id=inventory-service",
                "--spring.cloud.gateway.server.webflux.routes[1].predicates[0]=Path=/api/inventory/**",
                "--spring.cloud.gateway.server.webflux.routes[1].filters[0].name=Resilient",
                "--spring.cloud.gateway.server.webflux.routes[1].filters[0].args.name=inventory-service",
                "--spring.cloud.gateway.server.webflux.routes[1].uri=http://localhost:18084"));
        gateway = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            await().atMost(Duration.ofSeconds(180)).ignoreExceptions().until(() -> {
                assertTrue(gateway.isAlive(), "Gateway exited: " + log);
                return request("GET", "/actuator/health/readiness").statusCode() == 200;
            });
        } catch (Throwable ex) { System.err.println(Files.readString(log)); throw ex; }
    }

    @AfterAll void stop() throws Exception {
        release.countDown();
        if (gateway != null) { gateway.destroy(); if (!gateway.waitFor(10, TimeUnit.SECONDS)) gateway.destroyForcibly(); }
        if (downstream != null) downstream.stop(0);
        if (workers != null) workers.shutdownNow();
    }
    @BeforeEach void report(TestInfo test) { System.out.println("Day12 HTTP scenario: " + test.getDisplayName()); }

    @Test @Order(1) void normalRoutesAndBusiness400ArePassedThrough() throws Exception {
        assertEquals(200, request("GET", "/api/orders/normal").statusCode());
        assertEquals(200, request("GET", "/api/inventory/normal").statusCode());
        assertEquals(400, request("GET", "/api/orders/bad-request").statusCode());
        assertEquals(1, count("/api/orders/bad-request"));
        assertEquals(404, request("GET", "/internal/resilience/order-service").statusCode());
    }
    @Test @Order(2) void getRetriesOnceWithBackoffThenSucceeds() throws Exception {
        assertEquals(200, request("GET", "/api/orders/temporary").statusCode());
        assertEquals(2, count("/api/orders/temporary"));
        assertTrue(retryTimes.get(1) - retryTimes.get(0) >= TimeUnit.MILLISECONDS.toNanos(150));
    }
    @Test @Order(3) void postFailureAndConnectionResetAreNeverRetried() throws Exception {
        error(request("POST", "/api/orders/post-failure"), 503, "DOWNSTREAM_UNAVAILABLE");
        error(request("POST", "/api/orders/post-reset"), 503, "DOWNSTREAM_UNAVAILABLE");
        assertEquals(1, count("/api/orders/post-failure"));
        assertEquals(1, count("/api/orders/post-reset"));
    }
    @Test @Order(4) void postTimeoutHasOneAttemptAndGetTimeoutHasTwo() throws Exception {
        long before = System.nanoTime();
        error(request("POST", "/api/orders/slow"), 504, "DOWNSTREAM_TIMEOUT");
        assertTrue(Duration.ofNanos(System.nanoTime() - before).toMillis() < 5000);
        assertEquals(1, count("/api/orders/slow"));
        before = System.nanoTime();
        error(request("GET", "/api/orders/slow"), 504, "DOWNSTREAM_TIMEOUT");
        long elapsed = Duration.ofNanos(System.nanoTime() - before).toMillis();
        assertTrue(elapsed >= 5900 && elapsed < 8500, "Two bounded attempts, elapsed=" + elapsed);
        assertEquals(3, count("/api/orders/slow"));
    }
    @Test @Order(5) void concurrencyRejectsImmediatelyAndReleasesPermits() throws Exception {
        var one = async("/api/inventory/blocked");
        var two = async("/api/inventory/blocked");
        assertTrue(blocked.await(2, TimeUnit.SECONDS));
        long before = System.nanoTime();
        error(request("GET", "/api/inventory/blocked"), 503, "CONCURRENCY_LIMIT");
        assertTrue(Duration.ofNanos(System.nanoTime() - before).toMillis() < 1000);
        assertEquals(2, count("/api/inventory/blocked"));
        release.countDown();
        assertEquals(200, one.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(200, two.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(200, request("GET", "/api/inventory/normal").statusCode());
    }
    @Test @Order(0) void circuitsOpenIndependentlyAndRecoverThroughThreeHalfOpenProbes() throws Exception {
        // Exercise circuit transitions first, before the other tests inject transport failures.
        for (int i = 0; i < 20; i++) assertEquals(200, request("GET", "/api/orders/normal").statusCode());
        unavailable.set(true);
        for (int i = 0; i < 10; i++) error(request("GET", "/api/orders/offline"), 503, "DOWNSTREAM_UNAVAILABLE");
        int calls = count("/api/orders/offline");
        long before = System.nanoTime();
        error(request("GET", "/api/orders/offline"), 503, "CIRCUIT_OPEN");
        assertEquals(calls, count("/api/orders/offline"));
        assertTrue(Duration.ofNanos(System.nanoTime() - before).toMillis() < 1000);
        unavailable.set(false);
        assertEquals(200, request("GET", "/api/inventory/normal").statusCode());
        assertTrue(metrics().contains("name=\"order-service\",state=\"open\"} 1.0"), metrics());
        Thread.sleep(10500);
        assertEquals(200, request("GET", "/api/orders/normal").statusCode());
        assertTrue(metrics().contains("name=\"order-service\",state=\"half_open\"} 1.0"), metrics());
        assertEquals(200, request("GET", "/api/orders/normal").statusCode());
        assertEquals(200, request("GET", "/api/orders/normal").statusCode());
        assertTrue(metrics().contains("name=\"order-service\",state=\"closed\"} 1.0"), metrics());
    }
    @Test @Order(7) void prometheusExposesBoundedResilienceMetrics() throws Exception {
        String scrape = metrics();
        for (String name : List.of("resilience4j_circuitbreaker_state", "orderflow_gateway_retries_total",
                "orderflow_gateway_timeouts_total", "orderflow_gateway_rejected_total", "orderflow_gateway_concurrent_available")) {
            assertTrue(scrape.contains(name), name + " missing");
        }
        for (String line : scrape.split("\n")) {
            if (line.startsWith("orderflow_gateway_attempts_total{")) assertTrue(line.contains("method=\"GET\"") || line.contains("method=\"OTHER\""));
        }
        Files.writeString(log.resolveSibling("day12-gateway-metrics.prom"), scrape);
    }

    private HttpResponse<String> request(String method, String path) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:19000" + path)).timeout(Duration.ofSeconds(10));
        return http.send(builder.method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    private CompletableFuture<HttpResponse<String>> async(String path) {
        return http.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:19000" + path)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private int count(String path) { return counts.getOrDefault(path, new AtomicInteger()).get(); }
    private String metrics() throws Exception { return request("GET", "/actuator/prometheus").body(); }
    private void error(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        var body = JsonMapper.builder().build().readTree(response.body());
        assertEquals(status, body.get("status").asInt());
        assertEquals(code, body.get("code").asText());
        assertFalse(body.has("id"));
    }
}
