package io.github.amitreddy14.revenant.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amitreddy14.revenant.core.ActivityOptions;
import io.github.amitreddy14.revenant.core.ActivityResult;
import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.Decider;
import io.github.amitreddy14.revenant.core.HistoryEvent;
import io.github.amitreddy14.revenant.core.RetryPolicy;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WorkflowDefinition;
import io.github.amitreddy14.revenant.core.WorkflowId;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The store tells a notifier after commits, and a broken notifier never breaks the data. */
class TaskNotifierTest extends PostgresIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private static final WorkflowDefinition CHARGE_THEN_FINISH = (ctx, input) -> ctx.callActivity("charge", input);

    private static final WorkflowDefinition SLEEP_THEN_FINISH = (ctx, input) -> {
        ctx.sleep(Duration.ZERO);
        return "\"done\"";
    };

    /** Three attempts with a one-hour wait, so the retry is not ready any time soon. */
    private static final WorkflowDefinition RETRIES_LATER = (ctx, input) -> ctx.callActivity("charge", input,
            ActivityOptions.retrying(RetryPolicy.exponential(3, Duration.ofHours(1))));

    private final List<TaskKind> heard = new ArrayList<>();
    private final WorkflowStore store = new WorkflowStore(DATA_SOURCE, heard::add);

    @Test
    void startingAWorkflowSaysWorkflowTasksAreReady() {
        store.start(WorkflowId.random(), "Order", "{}");

        assertThat(heard).containsExactly(TaskKind.WORKFLOW_TASK);
    }

    @Test
    void aDecisionThatSchedulesAnActivitySaysActivityTasksAreReady() {
        WorkflowId id = startAndForget();

        decide(id, CHARGE_THEN_FINISH);

        assertThat(heard).containsExactly(TaskKind.ACTIVITY_TASK);
    }

    @Test
    void aFinishedActivitySaysWorkflowTasksAreReady() {
        WorkflowId id = startAndForget();
        decide(id, CHARGE_THEN_FINISH);
        heard.clear();
        ClaimedTask task = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();

        store.completeActivityTask(task, ActivityResult.success("1"));

        assertThat(heard).containsExactly(TaskKind.WORKFLOW_TASK);
    }

    @Test
    void aRetryThatWaitsForItsBackoffSaysNothing() {
        WorkflowId id = startAndForget();
        decide(id, RETRIES_LATER);
        heard.clear();
        ClaimedTask task = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();

        store.completeActivityTask(task, ActivityResult.failure("{\"type\":\"Flaky\"}"));

        assertThat(heard).isEmpty();
    }

    @Test
    void aTimerThatFiresSaysWorkflowTasksAreReady() throws SQLException {
        WorkflowId id = startAndForget();
        decide(id, SLEEP_THEN_FINISH);
        heard.clear();
        execute("UPDATE timer SET fire_at = now() - interval '1 second'");

        store.fireDueTimers(10);

        assertThat(heard).containsExactly(TaskKind.WORKFLOW_TASK);
    }

    @Test
    void aSweepThatFindsNothingSaysNothing() {
        store.fireDueTimers(10);
        store.timeOutExpiredActivities(10);

        assertThat(heard).isEmpty();
    }

    @Test
    void aNotifierThatThrowsDoesNotUndoTheCommit() throws SQLException {
        WorkflowStore broken = new WorkflowStore(DATA_SOURCE, kind -> {
            throw new IllegalStateException("redis is down");
        });
        WorkflowId id = WorkflowId.random();

        broken.start(id, "Order", "{}");

        assertThat(broken.find(id)).isPresent();
        assertThat(count("SELECT count(*) FROM task")).isEqualTo(1);
    }

    private WorkflowId startAndForget() {
        WorkflowId id = WorkflowId.random();
        store.start(id, "Order", "{}");
        heard.clear();
        return id;
    }

    private void decide(WorkflowId id, WorkflowDefinition definition) {
        ClaimedTask task = store.claimTask(TaskKind.WORKFLOW_TASK, "decider", LEASE).orElseThrow();
        List<HistoryEvent> history = store.history(id);
        store.completeWorkflowTask(task, history.get(history.size() - 1).seq(), Decider.decide(definition, history));
    }
}
