package io.github.amitreddy14.revenant.persistence;

import io.github.amitreddy14.revenant.core.WorkflowId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * The transactional outbox (ADR-0005). Rows written here in the same transaction as a history
 * append are relayed to Kafka by a separate process (Phase 4).
 */
public final class OutboxRepository {

    static final String INSERT_OUTBOX =
            "INSERT INTO outbox (workflow_id, event_type, payload) VALUES (?, ?, ?::jsonb)";

    public void enqueue(Connection connection, WorkflowId workflowId, String eventType, String payloadJson)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_OUTBOX)) {
            statement.setObject(1, workflowId.value());
            statement.setString(2, eventType);
            statement.setString(3, payloadJson);
            statement.executeUpdate();
        }
    }
}
