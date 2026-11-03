package io.github.amitreddy14.revenant.persistence;

import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.HistoryEvent;
import io.github.amitreddy14.revenant.core.NewEvent;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WorkflowExecution;
import io.github.amitreddy14.revenant.core.WorkflowId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Entry point to storage. Each method is one atomic transaction.
 *
 * <p>Phase 2 adds richer operations (record an activity result, schedule a timer) built from the
 * same repositories and {@link Transactions}.
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
            workflows.append(connection, id, 0, List.of(new NewEvent("WorkflowStarted", inputJson)));
            outbox.enqueue(connection, id, "WorkflowStarted", inputJson);
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
}
