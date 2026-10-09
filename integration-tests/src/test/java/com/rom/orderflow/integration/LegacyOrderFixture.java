package com.rom.orderflow.integration;

import java.sql.*;
import java.util.UUID;
import static com.rom.orderflow.integration.TestEnvironment.ORDER_DB;

final class LegacyOrderFixture {
    static void insert(TestEnvironment env, UUID orderId, String sku, int quantity, String payload) throws Exception {
        try (Connection connection = env.connection(ORDER_DB)) {
            connection.setAutoCommit(false);
            execute(connection, "INSERT INTO orders(id,customer_id,status,total_amount,currency) VALUES (?,?,'PENDING',10,'USD')",
                    orderId, UUID.randomUUID());
            execute(connection, "INSERT INTO order_items(id,order_id,sku,quantity) VALUES (?,?,?,?)",
                    UUID.randomUUID(), orderId, sku, quantity);
            execute(connection, """
                    INSERT INTO order_sagas(order_id,state,deadline)
                    VALUES (?,'WAITING_RESERVATION',NOW()+INTERVAL '120 seconds')
                    """, orderId);
            if (payload != null) {
                UUID eventId = UUID.fromString(env.json.readTree(payload).get("eventId").asText());
                execute(connection, """
                        INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,status,created_at)
                        VALUES (?,'ORDER',?,'ORDER_CREATED',?,'PENDING',NOW())
                        """, eventId, orderId, payload);
            }
            connection.commit();
        }
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }
}
