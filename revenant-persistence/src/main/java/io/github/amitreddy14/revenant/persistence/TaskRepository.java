package io.github.amitreddy14.revenant.persistence;

import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WorkflowId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The work queue. Claiming uses {@code FOR UPDATE SKIP LOCKED}, so many workers can poll at
 * once without blocking each other and without two of them receiving the same task.
 */
public final class TaskRepository {

    static final String INSERT_TASK =
            "INSERT INTO task (workflow_id, kind, command_id, name, payload, visible_at) "
                    + "VALUES (?, ?, ?, ?, ?::jsonb, now() + (?::bigint * interval '1 millisecond')) "
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
                    + "RETURNING t.task_id, t.workflow_id, t.kind, t.command_id, t.name, t.payload::text, t.attempt";

    // The attempt number is a fencing token: a worker whose lease expired cannot complete the task.
    static final String COMPLETE_TASK = "DELETE FROM task WHERE task_id = ? AND attempt = ?";

    /**
     * Adds a workflow task to the queue ("your workflow has new events, decide what to do next").
     *
     * @param delay how long to wait before the task becomes claimable ({@link Duration#ZERO} for now)
     * @return the new task id
     * @throws IllegalArgumentException if {@code kind} is {@link TaskKind#ACTIVITY_TASK}; use
     *                                  {@link #enqueueActivity} for those
     */
    public long enqueue(Connection connection, WorkflowId workflowId, TaskKind kind, String payloadJson,
            Duration delay) throws SQLException {
        if (kind == TaskKind.ACTIVITY_TASK) {
            throw new IllegalArgumentException("activity tasks need a command id and name: use enqueueActivity");
        }
        return insert(connection, workflowId, kind, null, null, payloadJson, delay);
    }

    /**
     * Adds an activity task to the queue.
     *
     * @param commandId the workflow command this task answers
     * @param name      the activity to run
     * @param inputJson the activity input
     * @return the new task id
     */
    public long enqueueActivity(Connection connection, WorkflowId workflowId, long commandId, String name,
            String inputJson) throws SQLException {
        return insert(connection, workflowId, TaskKind.ACTIVITY_TASK, commandId, name, inputJson, Duration.ZERO);
    }

    private long insert(Connection connection, WorkflowId workflowId, TaskKind kind, Long commandId, String name,
            String payloadJson, Duration delay) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_TASK)) {
            statement.setObject(1, workflowId.value());
            statement.setString(2, kind.name());
            statement.setObject(3, commandId, Types.BIGINT);
            statement.setString(4, name);
            statement.setString(5, payloadJson);
            statement.setLong(6, delay.toMillis());
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
                long command = row.getLong(4);
                Long commandId = row.wasNull() ? null : command;
                return Optional.of(new ClaimedTask(
                        row.getLong(1),
                        new WorkflowId(row.getObject(2, UUID.class)),
                        TaskKind.valueOf(row.getString(3)),
                        commandId,
                        row.getString(5),
                        row.getString(6),
                        row.getInt(7)));
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
