package io.github.amitreddy14.revenant.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WorkflowId;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TaskQueueTest extends PostgresIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private final WorkflowStore store = new WorkflowStore(DATA_SOURCE);
    private final Transactions transactions = new Transactions(DATA_SOURCE);
    private final TaskRepository tasks = new TaskRepository();

    @Test
    void claimLeasesTheTaskAndHidesItFromOtherWorkers() {
        WorkflowId id = WorkflowId.random();
        store.start(id, "OrderFlow", "{}");

        Optional<ClaimedTask> first = store.claimTask(TaskKind.WORKFLOW_TASK, "worker-1", LEASE);
        Optional<ClaimedTask> second = store.claimTask(TaskKind.WORKFLOW_TASK, "worker-2", LEASE);

        assertThat(first).isPresent();
        assertThat(first.get().workflowId()).isEqualTo(id);
        assertThat(first.get().kind()).isEqualTo(TaskKind.WORKFLOW_TASK);
        assertThat(first.get().attempt()).isEqualTo(1);
        assertThat(second).isEmpty();
    }

    @Test
    void aWorkerOnlyReceivesTasksOfTheKindItAskedFor() {
        store.start(WorkflowId.random(), "OrderFlow", "{}");

        assertThat(store.claimTask(TaskKind.ACTIVITY_TASK, "worker-1", LEASE)).isEmpty();
    }

    @Test
    void anExpiredLeaseLetsAnotherWorkerTakeOverAndFencesOutTheOldOne() throws SQLException {
        store.start(WorkflowId.random(), "OrderFlow", "{}");
        ClaimedTask first = store.claimTask(TaskKind.WORKFLOW_TASK, "worker-1", LEASE).orElseThrow();

        expireAllLeases(); // worker-1 "died": it stopped renewing its lease
        ClaimedTask second = store.claimTask(TaskKind.WORKFLOW_TASK, "worker-2", LEASE).orElseThrow();

        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(second.attempt()).isEqualTo(2);

        // worker-1 comes back and reports its result late: it must be turned away.
        assertThat(store.completeTask(first.taskId(), first.attempt())).isFalse();
        assertThat(count("SELECT count(*) FROM task")).isEqualTo(1);

        assertThat(store.completeTask(second.taskId(), second.attempt())).isTrue();
        assertThat(count("SELECT count(*) FROM task")).isZero();
    }

    @Test
    void aDelayedTaskIsInvisibleUntilItIsDue() throws SQLException {
        WorkflowId id = WorkflowId.random();
        store.start(id, "OrderFlow", "{}");
        ClaimedTask first = store.claimTask(TaskKind.WORKFLOW_TASK, "worker-1", LEASE).orElseThrow();
        store.completeTask(first.taskId(), first.attempt());

        transactions.executeVoid(connection ->
                tasks.enqueue(connection, id, TaskKind.WORKFLOW_TASK, "{}", Duration.ofMinutes(5)));
        assertThat(store.claimTask(TaskKind.WORKFLOW_TASK, "worker-1", LEASE)).isEmpty();

        execute("UPDATE task SET visible_at = now() - interval '1 second'");
        assertThat(store.claimTask(TaskKind.WORKFLOW_TASK, "worker-1", LEASE)).isPresent();
    }

    @Test
    void manyWorkersNeverReceiveTheSameTask() throws Exception {
        int taskCount = 40;
        for (int i = 0; i < taskCount; i++) {
            store.start(WorkflowId.random(), "Load", "{}");
        }
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Long> claimedIds = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> done = new ArrayList<>();

        for (int w = 0; w < workers; w++) {
            String workerId = "worker-" + w;
            done.add(pool.submit(() -> {
                Optional<ClaimedTask> task;
                while ((task = store.claimTask(TaskKind.WORKFLOW_TASK, workerId, LEASE)).isPresent()) {
                    claimedIds.add(task.get().taskId());
                }
            }));
        }
        for (Future<?> worker : done) {
            worker.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(claimedIds).hasSize(taskCount).doesNotHaveDuplicates();
    }
}
