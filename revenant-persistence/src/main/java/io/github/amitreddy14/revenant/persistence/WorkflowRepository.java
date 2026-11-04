package io.github.amitreddy14.revenant.persistence;

import io.github.amitreddy14.revenant.core.HistoryEvent;
import io.github.amitreddy14.revenant.core.NewEvent;
import io.github.amitreddy14.revenant.core.WorkflowExecution;
import io.github.amitreddy14.revenant.core.WorkflowId;
import io.github.amitreddy14.revenant.core.WorkflowStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Workflow rows and their append-only history. Every method takes the caller's
 * {@link Connection}, so it can join a larger transaction (see {@link Transactions}).
 */
public final class WorkflowRepository {

    /** The workflow's state as read under a row lock. */
    public record Locked(long lastSeq, WorkflowStatus status) {}

    private static final String UNIQUE_VIOLATION = "23505";

    static final String INSERT_WORKFLOW =
            "INSERT INTO workflow_execution (workflow_id, workflow_type) VALUES (?, ?)";

    // Optimistic lock: succeeds only if nobody appended since the caller read last_seq.
    static final String BUMP_LAST_SEQ =
            "UPDATE workflow_execution SET last_seq = ?, updated_at = now() "
                    + "WHERE workflow_id = ? AND last_seq = ? AND status = 'RUNNING'";

    static final String INSERT_EVENT =
            "INSERT INTO history_event (workflow_id, seq, event_type, command_id, name, payload) "
                    + "VALUES (?, ?, ?, ?, ?, ?::jsonb)";

    static final String SELECT_HISTORY =
            "SELECT seq, event_type, command_id, name, payload::text, created_at "
                    + "FROM history_event WHERE workflow_id = ? ORDER BY seq";

    static final String SELECT_WORKFLOW =
            "SELECT workflow_type, status, last_seq, created_at FROM workflow_execution WHERE workflow_id = ?";

    // Takes the row lock that serialises everything that changes one workflow.
    static final String LOCK_WORKFLOW =
            "SELECT last_seq, status FROM workflow_execution WHERE workflow_id = ? FOR UPDATE";

    static final String CLOSE_WORKFLOW =
            "UPDATE workflow_execution SET status = ?, closed_at = now(), updated_at = now() "
                    + "WHERE workflow_id = ? AND status = 'RUNNING'";

    /**
     * Creates a workflow with an empty history.
     *
     * @throws WorkflowAlreadyExistsException if the id is already taken
     */
    public void create(Connection connection, WorkflowId id, String type) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_WORKFLOW)) {
            statement.setObject(1, id.value());
            statement.setString(2, type);
            statement.executeUpdate();
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new WorkflowAlreadyExistsException("Workflow " + id + " already exists");
            }
            throw e;
        }
    }

    /**
     * Appends events to the end of the history.
     *
     * @param expectedLastSeq the newest sequence number the caller has seen (0 for an empty history)
     * @return the new newest sequence number
     * @throws AppendConflictException if the history moved on, or the workflow is closed or missing
     */
    public long append(Connection connection, WorkflowId id, long expectedLastSeq, List<NewEvent> events)
            throws SQLException {
        if (events.isEmpty()) {
            throw new IllegalArgumentException("events must not be empty");
        }
        long newLastSeq = expectedLastSeq + events.size();

        try (PreparedStatement statement = connection.prepareStatement(BUMP_LAST_SEQ)) {
            statement.setLong(1, newLastSeq);
            statement.setObject(2, id.value());
            statement.setLong(3, expectedLastSeq);
            if (statement.executeUpdate() != 1) {
                throw new AppendConflictException("Cannot append to workflow " + id + ": last_seq "
                        + expectedLastSeq + " is stale, or the workflow is closed or missing");
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(INSERT_EVENT)) {
            long seq = expectedLastSeq;
            for (NewEvent event : events) {
                seq++;
                statement.setObject(1, id.value());
                statement.setLong(2, seq);
                statement.setString(3, event.type());
                statement.setObject(4, event.commandId(), Types.BIGINT);
                statement.setString(5, event.name());
                statement.setString(6, event.payloadJson());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return newLastSeq;
    }

    /** Returns the whole history in order. Empty if the workflow has no events or does not exist. */
    public List<HistoryEvent> loadHistory(Connection connection, WorkflowId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_HISTORY)) {
            statement.setObject(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                List<HistoryEvent> history = new ArrayList<>();
                while (rows.next()) {
                    long commandId = rows.getLong(3);
                    Long command = rows.wasNull() ? null : commandId;
                    history.add(new HistoryEvent(
                            rows.getLong(1),
                            rows.getString(2),
                            command,
                            rows.getString(4),
                            rows.getString(5),
                            rows.getObject(6, OffsetDateTime.class).toInstant()));
                }
                return history;
            }
        }
    }

    public Optional<WorkflowExecution> find(Connection connection, WorkflowId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_WORKFLOW)) {
            statement.setObject(1, id.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new WorkflowExecution(
                        id,
                        row.getString(1),
                        WorkflowStatus.valueOf(row.getString(2)),
                        row.getLong(3),
                        row.getObject(4, OffsetDateTime.class).toInstant()));
            }
        }
    }

    /**
     * Locks the workflow row until the transaction ends and returns its state. Anyone else who
     * tries to change this workflow waits. Use it before a read-then-write that must not interleave.
     */
    public Optional<Locked> lock(Connection connection, WorkflowId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_WORKFLOW)) {
            statement.setObject(1, id.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Locked(row.getLong(1), WorkflowStatus.valueOf(row.getString(2))));
            }
        }
    }

    /**
     * Moves a running workflow to a closed status.
     *
     * @throws IllegalArgumentException if {@code status} is {@link WorkflowStatus#RUNNING}
     * @throws IllegalStateException    if the workflow is not running
     */
    public void close(Connection connection, WorkflowId id, WorkflowStatus status) throws SQLException {
        if (!status.isClosed()) {
            throw new IllegalArgumentException("close() needs a closed status, got " + status);
        }
        try (PreparedStatement statement = connection.prepareStatement(CLOSE_WORKFLOW)) {
            statement.setString(1, status.name());
            statement.setObject(2, id.value());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Workflow " + id + " is not running, cannot close it as " + status);
            }
        }
    }
}
