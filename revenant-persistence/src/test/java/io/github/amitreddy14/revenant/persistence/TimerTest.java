package io.github.amitreddy14.revenant.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amitreddy14.revenant.core.ActivityResult;
import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.Decider;
import io.github.amitreddy14.revenant.core.HistoryEvent;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WorkflowDefinition;
import io.github.amitreddy14.revenant.core.WorkflowId;
import io.github.amitreddy14.revenant.core.WorkflowStatus;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Durable timers, against a real database. */
class TimerTest extends PostgresIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

    /** Wait an hour, then ship. */
    private static final WorkflowDefinition WAIT_THEN_SHIP = (ctx, input) -> {
        ctx.sleep(Duration.ofHours(1));
        return ctx.callActivity("ship", input);
    };

    private final WorkflowStore store = new WorkflowStore(DATA_SOURCE);

    @Test
    void aDecisionThatSleepsStartsATimerInTheSameTransaction() throws SQLException {
        WorkflowId id = startWorkflow();

        decide(id);

        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "TimerStarted");
        assertThat(count("SELECT count(*) FROM timer WHERE timer_seq = 2 AND command_id = 1 AND fired_at IS NULL "
                + "AND fire_at BETWEEN now() + interval '59 minutes' AND now() + interval '61 minutes'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM task")).isZero(); // nothing to run while waiting
        assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(2);
    }

    @Test
    void aTimerThatIsNotDueYetDoesNotFire() throws SQLException {
        WorkflowId id = startWorkflow();
        decide(id);

        assertThat(store.fireDueTimers(10)).isZero();
        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "TimerStarted");
    }

    @Test
    void aDueTimerAppendsTimerFiredAndWakesTheWorkflow() throws SQLException {
        WorkflowId id = startWorkflow();
        decide(id);
        timersAreDue();

        int fired = store.fireDueTimers(10);

        assertThat(fired).isEqualTo(1);
        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "TimerStarted", "TimerFired");
        assertThat(store.history(id).get(2).commandId()).isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'WORKFLOW_TASK'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM timer WHERE fired_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(3);
    }

    @Test
    void aTimerFiresOnlyOnce() throws SQLException {
        WorkflowId id = startWorkflow();
        decide(id);
        timersAreDue();

        assertThat(store.fireDueTimers(10)).isEqualTo(1);
        assertThat(store.fireDueTimers(10)).isZero();
        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "TimerStarted", "TimerFired");
    }

    @Test
    void aTimerForAWorkflowThatAlreadyClosedIsOnlyMarkedFired() throws SQLException {
        WorkflowId id = startWorkflow();
        decide(id);
        execute("UPDATE workflow_execution SET status = 'FAILED', closed_at = now()");
        timersAreDue();

        int fired = store.fireDueTimers(10);

        assertThat(fired).isEqualTo(1);
        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "TimerStarted");
        assertThat(count("SELECT count(*) FROM task")).isZero();
        assertThat(count("SELECT count(*) FROM timer WHERE fired_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void severalDueTimersFireUpToTheLimit() throws SQLException {
        WorkflowId first = startWorkflow();
        WorkflowId second = startWorkflow();
        decide(first);
        decide(second);
        timersAreDue();

        assertThat(store.fireDueTimers(1)).isEqualTo(1);
        assertThat(store.fireDueTimers(10)).isEqualTo(1);
        assertThat(store.fireDueTimers(10)).isZero();
    }

    @Test
    void aWorkflowThatSleepsRunsToCompletionAfterTheWait() throws SQLException {
        WorkflowId id = startWorkflow();
        decide(id);
        timersAreDue();
        store.fireDueTimers(10);
        decide(id); // after the wait: schedules the activity

        ClaimedTask ship = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        assertThat(ship.commandId()).isEqualTo(2L);
        assertThat(ship.name()).isEqualTo("ship");
        store.completeActivityTask(ship, ActivityResult.success("\"shipped\""));
        decide(id);

        assertThat(store.find(id).orElseThrow().status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(types(store.history(id))).containsExactly(
                "WorkflowStarted", "TimerStarted", "TimerFired",
                "ActivityScheduled", "ActivityCompleted", "WorkflowCompleted");
    }

    private WorkflowId startWorkflow() {
        WorkflowId id = WorkflowId.random();
        store.start(id, "WaitThenShip", "{\"orderId\":42}");
        return id;
    }

    /** Claims a workflow task, replays, and commits the decision. */
    private void decide(WorkflowId id) {
        ClaimedTask task = store.claimTask(TaskKind.WORKFLOW_TASK, "decider", LEASE).orElseThrow();
        List<HistoryEvent> history = store.history(id);
        CompletionOutcome outcome = store.completeWorkflowTask(
                task, history.get(history.size() - 1).seq(), Decider.decide(WAIT_THEN_SHIP, history));
        assertThat(outcome).isEqualTo(CompletionOutcome.APPLIED);
    }

    /** Pretends the wait is over. */
    private static void timersAreDue() throws SQLException {
        execute("UPDATE timer SET fire_at = now() - interval '1 second' WHERE fired_at IS NULL");
    }

    private static List<String> types(List<HistoryEvent> history) {
        return history.stream().map(HistoryEvent::type).toList();
    }
}
