package io.github.amitreddy14.revenant.persistence;

import io.github.amitreddy14.revenant.core.WorkflowId;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The transactional outbox (ADR-0005). Rows written here in the same transaction as a history
 * append are relayed to Kafka by {@link OutboxRelay}.
 */
public final class OutboxRepository {

    static final String INSERT_OUTBOX =
            "INSERT INTO outbox (workflow_id, event_type, payload) VALUES (?, ?, ?::jsonb)";

    /** Identifies the "outbox relay" lock among advisory locks; any constant unique to Revenant works. */
    static final long RELAY_LOCK_KEY = 5_201_701L;

    // Held until the transaction ends. Only one relay at a time, so events cannot be reordered
    // by two relays taking different batches.
    static final String TRY_RELAY_LOCK = "SELECT pg_try_advisory_xact_lock(?)";

    static final String SELECT_UNPUBLISHED =
            "SELECT event_id, workflow_id, event_type, payload::text, created_at FROM outbox "
                    + "WHERE published_at IS NULL ORDER BY event_id LIMIT ?";

    static final String MARK_PUBLISHED =
            "UPDATE outbox SET published_at = now() WHERE event_id = ANY(?)";

    public void enqueue(Connection connection, WorkflowId workflowId, String eventType, String payloadJson)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_OUTBOX)) {
            statement.setObject(1, workflowId.value());
            statement.setString(2, eventType);
            statement.setString(3, payloadJson);
            statement.executeUpdate();
        }
    }

    /**
     * Tries to become the relay. The lock is released when the transaction ends.
     *
     * @return false if another transaction is already relaying
     */
    public boolean tryLockRelay(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRY_RELAY_LOCK)) {
            statement.setLong(1, RELAY_LOCK_KEY);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        }
    }

    /** The oldest events that have not been published yet, in the order they were written. */
    public List<OutboxEvent> fetchUnpublished(Connection connection, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_UNPUBLISHED)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<OutboxEvent> events = new ArrayList<>();
                while (rows.next()) {
                    events.add(new OutboxEvent(
                            rows.getLong(1),
                            new WorkflowId(rows.getObject(2, UUID.class)),
                            rows.getString(3),
                            rows.getString(4),
                            rows.getObject(5, OffsetDateTime.class).toInstant()));
                }
                return events;
            }
        }
    }

    /** Marks events as sent. Marking an event that is already marked changes nothing important. */
    public void markPublished(Connection connection, List<Long> eventIds) throws SQLException {
        Array ids = connection.createArrayOf("bigint", eventIds.toArray());
        try (PreparedStatement statement = connection.prepareStatement(MARK_PUBLISHED)) {
            statement.setArray(1, ids);
            statement.executeUpdate();
        } finally {
            ids.free();
        }
    }
}
