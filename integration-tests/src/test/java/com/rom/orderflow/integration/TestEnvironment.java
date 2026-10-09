package com.rom.orderflow.integration;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

final class TestEnvironment implements AutoCloseable {
    static final String BROKER = "localhost:19092";
    static final String ORDER_DB = "orderflow_it";
    static final String INVENTORY_DB = "inventory_it";
    final Path root = Path.of(System.getProperty("orderflow.projectRoot")).toAbsolutePath().normalize();
    final Path logs = root.resolve("integration-tests/target/service-logs").resolve(
            "run-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, Process> services = new HashMap<>();
    private final Map<String, Map<String, String>> serviceOverrides = new HashMap<>();
    private int launchNumber;

    void prepare() throws Exception {
        Files.createDirectories(logs);
        System.out.println("Integration service logs: " + logs);
        for (String database : List.of(ORDER_DB, INVENTORY_DB, "orderflow_upgrade_it", "inventory_upgrade_it")) {
            sql(database, "DROP SCHEMA public CASCADE");
            sql(database, "CREATE SCHEMA public");
        }
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic("order-events", 3, (short) 1),
                    new NewTopic("inventory-events", 3, (short) 1),
                    new NewTopic("order-events-dlt", 3, (short) 1),
                    new NewTopic("inventory-events-dlt", 3, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
        start("inventory", Map.of());
        start("order", Map.of());
    }

    void start(String service, Map<String, String> overrides) throws Exception {
        Process current = services.get(service);
        if (current != null && current.isAlive() && overrides.equals(serviceOverrides.get(service))) return;
        stop(service);
        Path jar = root.resolve(service + "-service/target/" + service + "-service-0.0.1-SNAPSHOT.jar");
        assertTrue(Files.isRegularFile(jar), "Build service jars first: " + jar);
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("server.port", service.equals("order") ? "18081" : "18082");
        properties.put("spring.datasource.url", jdbcUrl(service.equals("order") ? ORDER_DB : INVENTORY_DB));
        properties.put("spring.kafka.bootstrap-servers", BROKER);
        properties.put("spring.kafka.consumer.properties.session.timeout.ms", "6000");
        properties.put("spring.kafka.consumer.properties.heartbeat.interval.ms", "2000");
        properties.put("eureka.client.enabled", "false");
        properties.put("spring.cloud.discovery.enabled", "false");
        properties.put("spring.cloud.inetutils.timeout-seconds", "1");
        properties.put("orderflow.outbox.publish-delay-ms", "200");
        properties.put("orderflow.outbox.processing-timeout-seconds", "3");
        properties.put("orderflow.outbox.max-attempts", "5");
        properties.put("orderflow.outbox.failure-scan-delay-ms", "1000");
        properties.put("orderflow.saga.recovery-delay-ms", "200");
        properties.put("orderflow.saga.step-timeout-seconds", "600");
        properties.put("orderflow.saga.max-retries", "2");
        properties.put("spring.task.scheduling.pool.size", "3");
        properties.put("spring.kafka.producer.properties.max.block.ms", "1000");
        properties.put("spring.kafka.producer.properties.request.timeout.ms", "2000");
        properties.put("spring.kafka.producer.properties.delivery.timeout.ms", "3000");
        properties.put("logging.level.org.apache.kafka", "ERROR");
        properties.put("logging.level.org.springframework.kafka", "WARN");
        properties.putAll(overrides);
        List<String> command = new ArrayList<>();
        String javaExecutable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        command.add(Path.of(System.getProperty("java.home"), "bin", javaExecutable).toString());
        command.add("-Xms64m");
        command.add("-Xmx256m");
        command.add("-XX:TieredStopAtLevel=1");
        command.add("-jar");
        command.add(jar.toString());
        properties.forEach((key, value) -> command.add("--" + key + "=" + value));
        Path log = logs.resolve(service + "-" + (++launchNumber) + ".log");
        Process process = new ProcessBuilder(command).directory(root.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        services.put(service, process);
        try {
            await().atMost(Duration.ofSeconds(300)).pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        if (!process.isAlive()) throw new AssertionError("Service exited: " + log);
                        try {
                            return request(service, "GET", "/actuator/health", null).statusCode() == 200;
                        } catch (Exception ignored) {
                            return false;
                        }
                    });
        } catch (Throwable ex) {
            System.err.println("Service startup log: " + log);
            System.err.println(Files.readString(log));
            throw ex;
        }
        serviceOverrides.put(service, Map.copyOf(overrides));
    }

    void stop(String service) throws Exception {
        Process process = services.remove(service);
        serviceOverrides.remove(service);
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Service did not stop");
            }
        }
    }

    void crash(String service) throws Exception {
        Process process = services.remove(service);
        serviceOverrides.remove(service);
        assertNotNull(process);
        process.destroyForcibly();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }

    HttpResponse<String> request(String service, String method, String path, Object body) throws Exception {
        int port = service.equals("order") ? 18081 : 18082;
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode call(String service, String method, String path, Object body, int expectedStatus) throws Exception {
        HttpResponse<String> response = request(service, method, path, body);
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }

    UUID createOrder(List<Map<String, Object>> items) throws Exception {
        return UUID.fromString(call("order", "POST", "/api/orders", Map.of(
                "customerId", UUID.randomUUID().toString(), "totalAmount", 10,
                "currency", "USD", "items", items), 201).get("id").asText());
    }

    void stock(String sku, int quantity) throws Exception {
        call("inventory", "POST", "/api/inventory",
                Map.of("sku", sku, "productName", "Integration test", "quantity", quantity), 201);
    }

    void waitOrder(UUID orderId, String status) {
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertEquals(status,
                        text(ORDER_DB, "SELECT status FROM orders WHERE id = ?", orderId)));
    }

    static Map<String, Object> item(String sku, int quantity) {
        return Map.of("sku", sku, "quantity", quantity);
    }

    Map<String, Object> event(UUID orderId, String type, List<Map<String, Object>> items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", UUID.randomUUID().toString());
        result.put("orderId", orderId.toString());
        result.put("eventType", type);
        result.put("schemaVersion", 1);
        result.put("occurredAt", java.time.Instant.now().toString());
        if (items != null) result.put("items", items);
        return result;
    }

    RecordMetadata send(String topic, String key, Object event) throws Exception {
        return sendRaw(topic, key, json.writeValueAsString(event));
    }

    RecordMetadata sendRaw(String topic, String key, String payload) throws Exception {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(
                properties, new StringSerializer(), new StringSerializer())) {
            return producer.send(new ProducerRecord<>(topic, key, payload)).get(10, TimeUnit.SECONDS);
        }
    }

    void waitCommitted(String group, RecordMetadata metadata) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    try (Admin admin = admin()) {
                        var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                                .get(5, TimeUnit.SECONDS)
                                .get(new TopicPartition(metadata.topic(), metadata.partition()));
                        assertNotNull(offset);
                        assertTrue(offset.offset() > metadata.offset());
                    }
                });
    }

    KafkaConsumer<String, String> observer(String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "it-observer-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                properties, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    void waitRecord(KafkaConsumer<String, String> consumer, String key, String expectedValue) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                        if (key.equals(record.key()) && (expectedValue == null || expectedValue.equals(record.value()))) {
                            return true;
                        }
                    }
                    return false;
                });
    }

    Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000));
    }

    void broker(String action) throws Exception {
        Process process = new ProcessBuilder("docker", "compose", "-f",
                root.resolve("docker-compose.integration.yml").toString(), "-p", "orderflow-it", action, "kafka")
                .redirectErrorStream(true).redirectOutput(logs.resolve("broker-" + action + ".log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Docker command timed out");
        assertEquals(0, process.exitValue());
        if (action.equals("start")) {
            await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1))
                    .until(() -> {
                        try (Admin admin = admin()) {
                            return !admin.describeCluster().nodes().get(5, TimeUnit.SECONDS).isEmpty();
                        } catch (Exception ignored) {
                            return false;
                        }
                    });
        }
    }

    String payload(String database, UUID orderId, String eventType) throws Exception {
        return text(database, "SELECT payload FROM outbox_events WHERE aggregate_id = ? AND event_type = ? ORDER BY created_at LIMIT 1",
                orderId, eventType);
    }

    String text(String database, String statement, Object... args) throws Exception {
        Object value = scalar(database, statement, args);
        return value == null ? null : value.toString();
    }

    long number(String database, String statement, Object... args) throws Exception {
        return ((Number) scalar(database, statement, args)).longValue();
    }

    private Object scalar(String database, String statement, Object... args) throws Exception {
        try (Connection connection = connection(database);
             PreparedStatement query = connection.prepareStatement(statement)) {
            bind(query, args);
            try (ResultSet results = query.executeQuery()) {
                return results.next() ? results.getObject(1) : null;
            }
        }
    }

    void sql(String database, String statement, Object... args) throws Exception {
        try (Connection connection = connection(database);
             PreparedStatement query = connection.prepareStatement(statement)) {
            bind(query, args);
            query.execute();
        }
    }

    Connection connection(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), "orderflow", "orderflow");
    }

    private String jdbcUrl(String database) {
        if (!Set.of(ORDER_DB, INVENTORY_DB, "orderflow_upgrade_it", "inventory_upgrade_it").contains(database)) {
            throw new IllegalArgumentException("Only dedicated integration test databases are allowed");
        }
        return "jdbc:postgresql://localhost:15432/" + database;
    }

    private static void bind(PreparedStatement query, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) query.setObject(i + 1, args[i]);
    }

    @Override
    public void close() throws Exception {
        for (String service : List.copyOf(services.keySet())) stop(service);
    }
}
