package io.github.amitreddy14.revenant.persistence;

import io.github.amitreddy14.revenant.core.WorkflowId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable timers. A timer is a row with a fire time in the database's own clock, so it survives
 * restarts and every server agrees when it is due.
 */
public final class TimerRepository {

    /** A timer that is due, locked by the current transaction. */
    public record Due(long timerId, WorkflowId workflowId, long commandId) {}

    static final String INSERT_TIMER =
            "INSERT INTO timer (workflow_id, timer_seq, command_id, fire_at) "
                    + "VALUES (?, ?, ?, now() + (?::bigint * interval '1 millisecond'))";

    // The earliest due timer that no other transaction is working on.
    static final String LOCK_NEXT_DUE =
            "SELECT timer_id, workflow_id, command_id FROM timer "
                    + "WHERE fired_at IS NULL AND fire_at <= now() "
                    + "ORDER BY fire_at, timer_id LIMIT 1 FOR UPDATE SKIP LOCKED";

    static final String MARK_FIRED = "UPDATE timer SET fired_at = now() WHERE timer_id = ?";

    /**
     * Starts a timer.
     *
     * @param timerSeq       history sequence number of the {@code TimerStarted} event
     * @param commandId      the workflow command that asked for the wait
     * @param durationMillis how long from now (database time) until the timer is due
     */
    public void start(Connection connection, WorkflowId workflowId, long timerSeq, long commandId,
            long durationMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_TIMER)) {
            statement.setObject(1, workflowId.value());
            statement.setLong(2, timerSeq);
            statement.setLong(3, commandId);
            statement.setLong(4, durationMillis);
            statement.executeUpdate();
        }
    }

    /** Finds one due timer and locks it until the transaction ends. */
    public Optional<Due> lockNextDue(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_NEXT_DUE);
                ResultSet row = statement.executeQuery()) {
            if (!row.next()) {
                return Optional.empty();
            }
            return Optional.of(new Due(
                    row.getLong(1), new WorkflowId(row.getObject(2, UUID.class)), row.getLong(3)));
        }
    }

    public void markFired(Connection connection, long timerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(MARK_FIRED)) {
            statement.setLong(1, timerId);
            statement.executeUpdate();
        }
    }
}
