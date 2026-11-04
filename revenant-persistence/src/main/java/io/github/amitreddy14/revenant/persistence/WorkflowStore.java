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

    private final Transactions transactions;
    private final WorkflowRepository workflows = new WorkflowRepository();
    private final TaskRepository tasks = new TaskRepository();
    private final OutboxRepository outbox = new OutboxRepository();

    public WorkflowStore(DataSource dataSource) {
        this.transactions = new Transactions(dataSource);
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
        return transactions.execute(connection -> {
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
                tasks.enqueueActivity(
                        connection, task.workflowId(), activity.commandId(), activity.name(), activity.inputJson());
            }
            Optional<WorkflowStatus> closing = decision.closesWorkflowAs();
            if (closing.isPresent()) {
                workflows.close(connection, task.workflowId(), closing.get());
            }
            return CompletionOutcome.APPLIED;
        });
    }

    /**
     * Commits an activity's result in one transaction: removes the activity task, appends
     * {@code ActivityCompleted} or {@code ActivityFailed}, writes the outbox row and queues a
     * workflow task so the workflow code can continue.
     */
    public CompletionOutcome completeActivityTask(ClaimedTask task, ActivityResult result) {
        requireKind(task, TaskKind.ACTIVITY_TASK);
        return transactions.execute(connection -> {
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
        });
    }

    private static void requireKind(ClaimedTask task, TaskKind expected) {
        if (task.kind() != expected) {
            throw new IllegalArgumentException("Expected a " + expected + " but got a " + task.kind());
        }
    }
}
