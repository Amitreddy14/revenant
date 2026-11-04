package io.github.amitreddy14.revenant.persistence;

import io.github.amitreddy14.revenant.core.ActivityResult;
import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.Command;
import io.github.amitreddy14.revenant.core.Decision;
import io.github.amitreddy14.revenant.core.Events;
import io.github.amitreddy14.revenant.core.HistoryEvent;
import io.github.amitreddy14.revenant.core.NewEvent;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WorkflowExecution;
import io.github.amitreddy14.revenant.core.WorkflowId;
import io.github.amitreddy14.revenant.core.WorkflowStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Entry point to storage. Each method is one atomic transaction.
 *
 * <p>The two {@code complete...} methods are the heart of crash safety. Each one does everything
 * a step needs (remove the finished task, append history, queue the follow-up work, write the
 * outbox rows) in a single transaction, so a crash leaves either the whole step or none of it.
 */
public final class WorkflowStore {

    /** The error recorded when an attempt outlives its start-to-close timeout. */
    static final String TIMEOUT_ERROR =
            "{\"type\":\"ActivityTimeout\",\"message\":\"the attempt exceeded its start-to-close timeout\"}";

    private final Transactions transactions;
    private final WorkflowRepository workflows = new WorkflowRepository();
    private final TaskRepository tasks = new TaskRepository();
    private final TimerRepository timers = new TimerRepository();
    private final OutboxRepository outbox = new OutboxRepository();

    private final TaskNotifier notifier;

    public WorkflowStore(DataSource dataSource) {
        this(dataSource, TaskNotifier.none());
    }

    /**
     * @param notifier told after a commit that new tasks may be ready; a missing or failing
     *                 notifier never affects the data, only how fast workers notice work
     */
    public WorkflowStore(DataSource dataSource, TaskNotifier notifier) {
        this.transactions = new Transactions(dataSource);
        this.notifier = notifier;
    }

    /** Best effort, after commit. A failure here must never undo or fail committed work. */
    private void wake(TaskKind kind) {
        try {
            notifier.tasksMayBeReady(kind);
        } catch (RuntimeException ignored) {
            // The task is already in the database; polling will find it.
        }
    }

    /**
     * Starts a workflow. In ONE transaction this creates the workflow row, appends the
     * {@code WorkflowStarted} event (sequence 1), writes the outbox row and enqueues the first
     * workflow task. Either all four happen or none do.
     *
     * @param inputJson the workflow input; must be a valid JSON document
     * @throws WorkflowAlreadyExistsException if the id is already taken
     */
    public void start(WorkflowId id, String type, String inputJson) {
        transactions.executeVoid(connection -> {
            workflows.create(connection, id, type);
            NewEvent started = Events.workflowStarted(inputJson);
            workflows.append(connection, id, 0, List.of(started));
            outbox.enqueue(connection, id, started.type(), started.payloadJson());
            tasks.enqueue(connection, id, TaskKind.WORKFLOW_TASK, "{}", Duration.ZERO);
        });
        wake(TaskKind.WORKFLOW_TASK);
    }

    public Optional<WorkflowExecution> find(WorkflowId id) {
        return transactions.execute(connection -> workflows.find(connection, id));
    }

    public List<HistoryEvent> history(WorkflowId id) {
        return transactions.execute(connection -> workflows.loadHistory(connection, id));
    }

    /** Leases the next task of the given kind, if any. See {@link TaskRepository#claim}. */
    public Optional<ClaimedTask> claimTask(TaskKind kind, String workerId, Duration lease) {
        return transactions.execute(connection -> tasks.claim(connection, kind, workerId, lease));
    }

    /**
     * Finds a task by the id and attempt a worker was given when it claimed it. Empty means the
     * lease is gone (expired and re-claimed, or already completed). See {@link TaskRepository#findLeased}.
     */
    public Optional<ClaimedTask> findLeasedTask(long taskId, int attempt) {
        return transactions.execute(connection -> tasks.findLeased(connection, taskId, attempt));
    }

    /** Marks a claimed task done. Returns false if the lease was lost. See {@link TaskRepository#complete}. */
    public boolean completeTask(long taskId, int attempt) {
        return transactions.execute(connection -> tasks.complete(connection, taskId, attempt));
    }

    /**
     * Commits the result of a decision (see {@code Decider}) in one transaction: removes the
     * workflow task, appends the decision's events, writes their outbox rows, queues the new
     * activity tasks, and closes the workflow if the decision ends it.
     *
     * @param task         the claimed workflow task
     * @param decidedAtSeq the newest history sequence number the decision was computed from
     * @param decision     what the replay decided
     */
    public CompletionOutcome completeWorkflowTask(ClaimedTask task, long decidedAtSeq, Decision decision) {
        requireKind(task, TaskKind.WORKFLOW_TASK);
        CompletionOutcome outcome = transactions.execute(connection -> {
            if (!tasks.complete(connection, task.taskId(), task.attempt())) {
                return CompletionOutcome.LEASE_LOST;
            }
            WorkflowRepository.Locked workflow = workflows.lock(connection, task.workflowId())
                    .orElseThrow(() -> new IllegalStateException("Workflow " + task.workflowId() + " does not exist"));

            // New events arrived (or the workflow closed) since this decision was computed. Every
            // event that needs a decision also queues a workflow task in its own transaction, so a
            // fresher task exists and this stale decision can be dropped safely.
            if (workflow.status() != WorkflowStatus.RUNNING || workflow.lastSeq() != decidedAtSeq) {
                return CompletionOutcome.STALE_DISCARDED;
            }
            if (decision.isEmpty()) {
                return CompletionOutcome.APPLIED;
            }

            List<NewEvent> events = decision.events();
            workflows.append(connection, task.workflowId(), decidedAtSeq, events);
            for (NewEvent event : events) {
                outbox.enqueue(connection, task.workflowId(), event.type(), event.payloadJson());
            }
            for (Command.ScheduleActivity activity : decision.activitiesToSchedule()) {
                tasks.enqueueActivity(connection, task.workflowId(), activity.commandId(), activity.name(),
                        activity.inputJson(), activity.options());
            }
            // Each command became exactly one event, in order, so a command's sequence number
            // is the last sequence number the decision was based on plus its position.
            long seq = decidedAtSeq;
            for (Command command : decision.commands()) {
                seq++;
                if (command instanceof Command.StartTimer timer) {
                    timers.start(connection, task.workflowId(), seq, timer.commandId(), timer.durationMillis());
                }
            }
            Optional<WorkflowStatus> closing = decision.closesWorkflowAs();
            if (closing.isPresent()) {
                workflows.close(connection, task.workflowId(), closing.get());
            }
            return CompletionOutcome.APPLIED;
        });
        if (outcome == CompletionOutcome.APPLIED && !decision.activitiesToSchedule().isEmpty()) {
            wake(TaskKind.ACTIVITY_TASK);
        }
        return outcome;
    }

    /**
     * Commits an activity attempt's outcome in one transaction.
     *
     * <ul>
     *   <li>success: removes the task, appends {@code ActivityCompleted}, queues a workflow task;
     *   <li>failure that the retry policy allows to be retried: counts the failure and puts the
     *       task back on the queue after the backoff; the history is untouched
     *       ({@link CompletionOutcome#RETRY_SCHEDULED});
     *   <li>any other failure (attempts used up, or not retryable): removes the task, appends
     *       {@code ActivityFailed}, queues a workflow task.
     * </ul>
     */
    public CompletionOutcome completeActivityTask(ClaimedTask task, ActivityResult result) {
        requireKind(task, TaskKind.ACTIVITY_TASK);
        CompletionOutcome outcome = transactions.execute(connection -> applyActivityResult(connection, task, result));
        // A scheduled retry becomes visible only after its backoff, so polling covers that case.
        if (outcome == CompletionOutcome.APPLIED) {
            wake(TaskKind.WORKFLOW_TASK);
        }
        return outcome;
    }

    /**
     * Fails activity attempts that outlived their start-to-close timeout. Each one is treated like
     * a reported failure: retried if its policy allows, otherwise recorded as {@code ActivityFailed}.
     * The worker that was running the attempt is fenced out, so its late result is ignored.
     *
     * <p>Something must call this regularly (the server's scheduler in a later phase, or a worker
     * loop); until it runs, a timed-out attempt just waits.
     *
     * @param max the most attempts to handle in this call
     * @return how many attempts were handled
     */
    public int timeOutExpiredActivities(int max) {
        int handled = 0;
        while (handled < max && transactions.execute(this::timeOutOneActivity)) {
            handled++;
        }
        if (handled > 0) {
            wake(TaskKind.WORKFLOW_TASK);
            wake(TaskKind.ACTIVITY_TASK);
        }
        return handled;
    }

    private boolean timeOutOneActivity(Connection connection) throws SQLException {
        Optional<ClaimedTask> expired = tasks.lockTimedOut(connection);
        if (expired.isEmpty()) {
            return false;
        }
        applyActivityResult(connection, expired.get(), ActivityResult.failure(TIMEOUT_ERROR));
        return true;
    }

    /**
     * Fires timers that are due: for each one, appends {@code TimerFired} and queues a workflow
     * task so the workflow can continue. A timer whose workflow has already closed is just marked
     * as fired.
     *
     * <p>Like {@link #timeOutExpiredActivities}, something must call this regularly.
     *
     * @param max the most timers to fire in this call
     * @return how many timers were fired
     */
    public int fireDueTimers(int max) {
        int fired = 0;
        while (fired < max && transactions.execute(this::fireOneTimer)) {
            fired++;
        }
        if (fired > 0) {
            wake(TaskKind.WORKFLOW_TASK);
        }
        return fired;
    }

    private boolean fireOneTimer(Connection connection) throws SQLException {
        Optional<TimerRepository.Due> due = timers.lockNextDue(connection);
        if (due.isEmpty()) {
            return false;
        }
        WorkflowRepository.Locked workflow = workflows.lock(connection, due.get().workflowId())
                .orElseThrow(() -> new IllegalStateException("Workflow " + due.get().workflowId() + " does not exist"));
        if (workflow.status() == WorkflowStatus.RUNNING) {
            NewEvent event = Events.timerFired(due.get().commandId());
            workflows.append(connection, due.get().workflowId(), workflow.lastSeq(), List.of(event));
            outbox.enqueue(connection, due.get().workflowId(), event.type(), event.payloadJson());
            tasks.enqueue(connection, due.get().workflowId(), TaskKind.WORKFLOW_TASK, "{}", Duration.ZERO);
        }
        timers.markFired(connection, due.get().timerId());
        return true;
    }

    private CompletionOutcome applyActivityResult(Connection connection, ClaimedTask task,
            ActivityResult result) throws SQLException {
        if (!result.succeeded() && result.retryable()) {
            Optional<TaskRepository.FailureState> state =
                    tasks.lockForFailure(connection, task.taskId(), task.attempt());
            if (state.isEmpty()) {
                return CompletionOutcome.LEASE_LOST;
            }
            Optional<Duration> delay = state.get().retryPolicy().delayAfterFailure(state.get().failures() + 1);
            if (delay.isPresent()) {
                tasks.scheduleRetry(connection, task.taskId(), result.payloadJson(), delay.get());
                return CompletionOutcome.RETRY_SCHEDULED;
            }
        }
        return finishActivity(connection, task, result);
    }

    /** The activity is done for good, one way or the other: record it and wake the workflow. */
    private CompletionOutcome finishActivity(Connection connection, ClaimedTask task,
            ActivityResult result) throws SQLException {
        if (!tasks.complete(connection, task.taskId(), task.attempt())) {
            return CompletionOutcome.LEASE_LOST;
        }
        WorkflowRepository.Locked workflow = workflows.lock(connection, task.workflowId())
                .orElseThrow(() -> new IllegalStateException("Workflow " + task.workflowId() + " does not exist"));
        if (workflow.status() != WorkflowStatus.RUNNING) {
            return CompletionOutcome.STALE_DISCARDED;
        }

        NewEvent event = result.succeeded()
                ? Events.activityCompleted(task.commandId(), task.name(), result.payloadJson())
                : Events.activityFailed(task.commandId(), task.name(), result.payloadJson());
        workflows.append(connection, task.workflowId(), workflow.lastSeq(), List.of(event));
        outbox.enqueue(connection, task.workflowId(), event.type(), event.payloadJson());
        tasks.enqueue(connection, task.workflowId(), TaskKind.WORKFLOW_TASK, "{}", Duration.ZERO);
        return CompletionOutcome.APPLIED;
    }

    private static void requireKind(ClaimedTask task, TaskKind expected) {
        if (task.kind() != expected) {
            throw new IllegalArgumentException("Expected a " + expected + " but got a " + task.kind());
        }
    }
}
