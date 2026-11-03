package io.github.amitreddy14.revenant.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amitreddy14.revenant.core.HistoryEvent;
import io.github.amitreddy14.revenant.core.NewEvent;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WorkflowExecution;
import io.github.amitreddy14.revenant.core.WorkflowId;
import io.github.amitreddy14.revenant.core.WorkflowStatus;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorkflowStoreTest extends PostgresIntegrationTest {

    private final WorkflowStore store = new WorkflowStore(DATA_SOURCE);
    private final Transactions transactions = new Transactions(DATA_SOURCE);
    private final WorkflowRepository workflows = new WorkflowRepository();
    private final TaskRepository tasks = new TaskRepository();

    @Test
    void startCreatesWorkflowHistoryOutboxRowAndFirstTask() throws SQLException {
        WorkflowId id = WorkflowId.random();

        store.start(id, "OrderFlow", "{\"orderId\":42}");

        WorkflowExecution execution = store.find(id).orElseThrow();
        assertThat(execution.type()).isEqualTo("OrderFlow");
        assertThat(execution.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(execution.lastSeq()).isEqualTo(1);

        List<HistoryEvent> history = store.history(id);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).seq()).isEqualTo(1);
        assertThat(history.get(0).type()).isEqualTo("WorkflowStarted");
        assertThat(history.get(0).payloadJson()).contains("orderId");

        assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'WORKFLOW_TASK'")).isEqualTo(1);
    }

    @Test
    void startingTheSameWorkflowTwiceIsRejectedWithoutDuplicates() throws SQLException {
        WorkflowId id = WorkflowId.random();
        store.start(id, "OrderFlow", "{}");

        assertThatThrownBy(() -> store.start(id, "OrderFlow", "{}"))
                .isInstanceOf(WorkflowAlreadyExistsException.class);

        assertThat(count("SELECT count(*) FROM history_event")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM task")).isEqualTo(1);
    }

    @Test
    void aFailureBeforeCommitRollsBackEveryWrite() throws SQLException {
        WorkflowId id = WorkflowId.random();

        assertThatThrownBy(() -> transactions.executeVoid(connection -> {
                    workflows.create(connection, id, "OrderFlow");
                    workflows.append(connection, id, 0, List.of(new NewEvent("WorkflowStarted", "{}")));
                    tasks.enqueue(connection, id, TaskKind.WORKFLOW_TASK, "{}", Duration.ZERO);
                    throw new IllegalStateException("simulated crash before commit");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated crash");

        assertThat(store.find(id)).isEmpty();
        assertThat(count("SELECT count(*) FROM history_event")).isZero();
        assertThat(count("SELECT count(*) FROM task")).isZero();
    }

    @Test
    void aStaleAppendIsRejectedAndTheHistoryIsUnchanged() {
        WorkflowId id = WorkflowId.random();
        store.start(id, "OrderFlow", "{}");
        transactions.executeVoid(connection ->
                workflows.append(connection, id, 1, List.of(new NewEvent("ActivityScheduled", "{}"))));

        assertThatThrownBy(() -> transactions.executeVoid(connection ->
                        workflows.append(connection, id, 1, List.of(new NewEvent("Stale", "{}")))))
                .isInstanceOf(AppendConflictException.class);

        assertThat(store.history(id)).extracting(HistoryEvent::type).containsExactly("WorkflowStarted", "ActivityScheduled");
        assertThat(store.find(id).orElseThrow().lastSeq()).isEqualTo(2);
    }

    @Test
    void ofManyConcurrentWritersExactlyOneWins() throws Exception {
        WorkflowId id = WorkflowId.random();
        store.start(id, "OrderFlow", "{}");
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();

        for (int i = 0; i < writers; i++) {
            outcomes.add(pool.submit(() -> {
                startTogether.await();
                try {
                    transactions.executeVoid(connection ->
                            workflows.append(connection, id, 1, List.of(new NewEvent("Racer", "{}"))));
                    return true;
                } catch (AppendConflictException e) {
                    return false;
                }
            }));
        }
        startTogether.countDown();

        int winners = 0;
        for (Future<Boolean> outcome : outcomes) {
            if (outcome.get(30, TimeUnit.SECONDS)) {
                winners++;
            }
        }
        pool.shutdownNow();

        assertThat(winners).isEqualTo(1);
        assertThat(store.history(id)).hasSize(2);
    }
}
