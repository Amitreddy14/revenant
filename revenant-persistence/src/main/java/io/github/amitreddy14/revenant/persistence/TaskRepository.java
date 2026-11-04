package io.github.amitreddy14.revenant.persistence;

import io.github.amitreddy14.revenant.core.ActivityOptions;
import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.RetryPolicy;
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

    static final String INSERT_ACTIVITY_TASK =
            "INSERT INTO task (workflow_id, kind, command_id, name, payload, max_attempts, "
                    + "initial_interval_ms, backoff_coefficient, max_interval_ms, start_to_close_ms) "
                    + "VALUES (?, 'ACTIVITY_TASK', ?, ?, ?::jsonb, ?, ?, ?, ?, ?) "
                    + "RETURNING task_id";

    // Pick the oldest claimable task and lease it, atomically. Claimable means: never claimed, or
    // its lease expired. A task with a start-to-close timeout is the exception: once its deadline
    // passes it is not handed out again but failed by the timeout sweep, so the failure is counted.
    // The lease of such a task is its timeout.
    static final String CLAIM_TASK =
            "WITH next AS ( "
                    + "SELECT task_id FROM task "
                    + "WHERE kind = ? AND visible_at <= now() "
                    + "AND (locked_until IS NULL OR (locked_until <= now() AND start_to_close_ms IS NULL)) "
                    + "ORDER BY visible_at, task_id "
                    + "LIMIT 1 "
                    + "FOR UPDATE SKIP LOCKED) "
                    + "UPDATE task t "
                    + "SET locked_by = ?, "
                    + "locked_until = now() + (COALESCE(t.start_to_close_ms, ?::bigint) * interval '1 millisecond'), "
                    + "attempt = t.attempt + 1 "
                    + "FROM next WHERE t.task_id = next.task_id "
                    + "RETURNING t.task_id, t.workflow_id, t.kind, t.command_id, t.name, t.payload::text, t.attempt";

    // The attempt number is a fencing token: a worker whose task was handed to someone else cannot
    // complete it. A task that is not leased (it was put back for a retry) cannot be completed either.
    static final String COMPLETE_TASK =
            "DELETE FROM task WHERE task_id = ? AND attempt = ? AND locked_by IS NOT NULL";

    // Reads the retry settings of a leased task and locks its row, fenced like COMPLETE_TASK.
    static final String LOCK_FOR_FAILURE =
            "SELECT failures, max_attempts, initial_interval_ms, backoff_coefficient, max_interval_ms "
                    + "FROM task WHERE task_id = ? AND attempt = ? AND locked_by IS NOT NULL FOR UPDATE";

    // Puts a failed task back on the queue after the backoff. It keeps its attempt number; the next
    // claim increments it, which fences out the worker that just failed.
    static final String SCHEDULE_RETRY =
            "UPDATE task SET failures = failures + 1, last_error = ?::jsonb, "
                    + "visible_at = now() + (?::bigint * interval '1 millisecond'), "
                    + "locked_by = NULL, locked_until = NULL "
                    + "WHERE task_id = ?";

    // The oldest leased activity whose start-to-close deadline has passed.
    static final String LOCK_TIMED_OUT =
            "SELECT task_id, workflow_id, command_id, name, payload::text, attempt FROM task "
                    + "WHERE kind = 'ACTIVITY_TASK' AND start_to_close_ms IS NOT NULL "
                    + "AND locked_by IS NOT NULL AND locked_until <= now() "
                    + "ORDER BY locked_until, task_id LIMIT 1 FOR UPDATE SKIP LOCKED";

    /** The retry settings and failure count of an activity task, read under a row lock. */
    public record FailureState(int failures, RetryPolicy retryPolicy) {}

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
        return enqueueActivity(connection, workflowId, commandId, name, inputJson, ActivityOptions.defaults());
    }

    /**
     * Adds an activity task with a retry policy and an optional start-to-close timeout.
     *
     * @return the new task id
     */
    public long enqueueActivity(Connection connection, WorkflowId workflowId, long commandId, String name,
            String inputJson, ActivityOptions options) throws SQLException {
        RetryPolicy policy = options.retryPolicy();
        try (PreparedStatement statement = connection.prepareStatement(INSERT_ACTIVITY_TASK)) {
            statement.setObject(1, workflowId.value());
            statement.setLong(2, commandId);
            statement.setString(3, name);
            statement.setString(4, inputJson);
            statement.setInt(5, policy.maxAttempts());
            statement.setLong(6, policy.initialInterval().toMillis());
            statement.setDouble(7, policy.backoffCoefficient());
            statement.setLong(8, policy.maxInterval().toMillis());
            Duration timeout = options.startToCloseTimeout();
            statement.setObject(9, timeout == null ? null : timeout.toMillis(), Types.BIGINT);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
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

    /**
     * Reads the retry settings of a leased activity task and locks its row until the transaction
     * ends.
     *
     * @return empty if the task is gone, was re-claimed since ({@code attempt} no longer matches),
     *         or is not currently leased
     */
    public Optional<FailureState> lockForFailure(Connection connection, long taskId, int attempt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_FOR_FAILURE)) {
            statement.setLong(1, taskId);
            statement.setInt(2, attempt);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                RetryPolicy policy = new RetryPolicy(
                        row.getInt(2),
                        Duration.ofMillis(row.getLong(3)),
                        row.getDouble(4),
                        Duration.ofMillis(row.getLong(5)));
                return Optional.of(new FailureState(row.getInt(1), policy));
            }
        }
    }

    /** Counts a failed attempt and puts the task back on the queue, claimable after {@code delay}. */
    public void scheduleRetry(Connection connection, long taskId, String errorJson, Duration delay)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SCHEDULE_RETRY)) {
            statement.setString(1, errorJson);
            statement.setLong(2, delay.toMillis());
            statement.setLong(3, taskId);
            statement.executeUpdate();
        }
    }

    /**
     * Finds one activity whose start-to-close timeout has passed and locks it. Rows locked by
     * another transaction are skipped, so several sweepers can run side by side.
     */
    public Optional<ClaimedTask> lockTimedOut(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_TIMED_OUT);
                ResultSet row = statement.executeQuery()) {
            if (!row.next()) {
                return Optional.empty();
            }
            return Optional.of(new ClaimedTask(
                    row.getLong(1),
                    new WorkflowId(row.getObject(2, UUID.class)),
                    TaskKind.ACTIVITY_TASK,
                    row.getLong(3),
                    row.getString(4),
                    row.getString(5),
                    row.getInt(6)));
        }
    }
}
