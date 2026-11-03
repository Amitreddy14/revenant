package io.github.amitreddy14.revenant.persistence;

import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WorkflowId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The work queue. Claiming uses {@code FOR UPDATE SKIP LOCKED}, so many workers can poll at
 * once without blocking each other and without two of them receiving the same task.
 */
public final class TaskRepository {

    static final String INSERT_TASK =
            "INSERT INTO task (workflow_id, kind, payload, visible_at) "
                    + "VALUES (?, ?, ?::jsonb, now() + (?::bigint * interval '1 millisecond')) "
                    + "RETURNING task_id";

    // Pick the oldest claimable task (never claimed, or its lease expired) and lease it, atomically.
    static final String CLAIM_TASK =
            "WITH next AS ( "
                    + "SELECT task_id FROM task "
                    + "WHERE kind = ? AND visible_at <= now() "
                    + "AND (locked_until IS NULL OR locked_until <= now()) "
                    + "ORDER BY visible_at, task_id "
                    + "LIMIT 1 "
                    + "FOR UPDATE SKIP LOCKED) "
                    + "UPDATE task t "
                    + "SET locked_by = ?, locked_until = now() + (?::bigint * interval '1 millisecond'), "
                    + "attempt = t.attempt + 1 "
                    + "FROM next WHERE t.task_id = next.task_id "
                    + "RETURNING t.task_id, t.workflow_id, t.kind, t.payload::text, t.attempt";

    // The attempt number is a fencing token: a worker whose lease expired cannot complete the task.
    static final String COMPLETE_TASK = "DELETE FROM task WHERE task_id = ? AND attempt = ?";

    /**
     * Adds a task to the queue.
     *
     * @param delay how long to wait before the task becomes claimable ({@link Duration#ZERO} for now)
     * @return the new task id
     */
    public long enqueue(Connection connection, WorkflowId workflowId, TaskKind kind, String payloadJson,
            Duration delay) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_TASK)) {
            statement.setObject(1, workflowId.value());
            statement.setString(2, kind.name());
            statement.setString(3, payloadJson);
            statement.setLong(4, delay.toMillis());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    /**
     * Leases the oldest claimable task of the given kind.
     *
     * @param workerId identifies the claiming worker (for diagnostics)
     * @param lease    how long the worker may hold the task before it becomes claimable again
     * @return the task, or empty if nothing is claimable right now
     */
    public Optional<ClaimedTask> claim(Connection connection, TaskKind kind, String workerId, Duration lease)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CLAIM_TASK)) {
            statement.setString(1, kind.name());
            statement.setString(2, workerId);
            statement.setLong(3, lease.toMillis());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ClaimedTask(
                        row.getLong(1),
                        new WorkflowId(row.getObject(2, UUID.class)),
                        TaskKind.valueOf(row.getString(3)),
                        row.getString(4),
                        row.getInt(5)));
            }
        }
    }

    /**
     * Removes a finished task.
     *
     * @return false if the task is gone or was re-claimed since ({@code attempt} no longer matches)
     */
    public boolean complete(Connection connection, long taskId, int attempt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(COMPLETE_TASK)) {
            statement.setLong(1, taskId);
            statement.setInt(2, attempt);
            return statement.executeUpdate() == 1;
        }
    }
}
