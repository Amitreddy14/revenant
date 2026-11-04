package io.github.amitreddy14.revenant.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WorkflowId;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Looking a leased task up by the id and attempt a worker was given. */
class FindLeasedTest extends PostgresIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private final WorkflowStore store = new WorkflowStore(DATA_SOURCE);

    @Test
    void aLeasedTaskIsFoundByIdAndAttempt() {
        store.start(WorkflowId.random(), "Order", "{}");
        ClaimedTask claimed = store.claimTask(TaskKind.WORKFLOW_TASK, "w1", LEASE).orElseThrow();

        assertThat(store.findLeasedTask(claimed.taskId(), claimed.attempt())).isPresent();
        assertThat(store.findLeasedTask(claimed.taskId(), claimed.attempt()).orElseThrow()).isEqualTo(claimed);
    }

    @Test
    void aTaskThatWasNeverClaimedIsNotFound() throws SQLException {
        store.start(WorkflowId.random(), "Order", "{}");
        long taskId = count("SELECT task_id FROM task");

        assertThat(store.findLeasedTask(taskId, 0)).isEmpty();
        assertThat(store.findLeasedTask(taskId, 1)).isEmpty();
    }

    @Test
    void theWrongAttemptIsNotFound() {
        store.start(WorkflowId.random(), "Order", "{}");
        ClaimedTask claimed = store.claimTask(TaskKind.WORKFLOW_TASK, "w1", LEASE).orElseThrow();

        assertThat(store.findLeasedTask(claimed.taskId(), claimed.attempt() + 1)).isEmpty();
    }

    @Test
    void aWorkerWhoseLeaseWasTakenOverNoLongerFindsTheTask() throws SQLException {
        store.start(WorkflowId.random(), "Order", "{}");
        ClaimedTask first = store.claimTask(TaskKind.WORKFLOW_TASK, "w1", LEASE).orElseThrow();
        expireAllLeases();
        ClaimedTask second = store.claimTask(TaskKind.WORKFLOW_TASK, "w2", LEASE).orElseThrow();

        assertThat(store.findLeasedTask(first.taskId(), first.attempt())).isEmpty();
        assertThat(store.findLeasedTask(second.taskId(), second.attempt())).isPresent();
    }

    @Test
    void aCompletedTaskIsGone() {
        store.start(WorkflowId.random(), "Order", "{}");
        ClaimedTask claimed = store.claimTask(TaskKind.WORKFLOW_TASK, "w1", LEASE).orElseThrow();
        store.completeTask(claimed.taskId(), claimed.attempt());

        assertThat(store.findLeasedTask(claimed.taskId(), claimed.attempt())).isEmpty();
    }
}
