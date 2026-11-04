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
import java.util.List;
import org.junit.jupiter.api.Test;

/** Start-to-close timeouts, enforced by the timeout sweep. */
class TimeoutTest extends PostgresIntegrationTest {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final WorkflowStore store = new WorkflowStore(DATA_SOURCE);

    @Test
    void anAttemptWithATimeoutIsLeasedForExactlyItsTimeout() throws SQLException {
        startAndSchedule(ActivityOptions.defaults().withStartToCloseTimeout(TIMEOUT));

        store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();

        assertThat(count("SELECT count(*) FROM task WHERE locked_until BETWEEN now() + interval '25 seconds' "
                + "AND now() + interval '35 seconds'")).isEqualTo(1);
    }

    @Test
    void anAttemptWithoutATimeoutUsesTheWorkersLease() throws SQLException {
        startAndSchedule(ActivityOptions.defaults());

        store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();

        assertThat(count("SELECT count(*) FROM task WHERE locked_until BETWEEN now() + interval '4 minutes' "
                + "AND now() + interval '6 minutes'")).isEqualTo(1);
    }

    @Test
    void aTimedOutAttemptIsNotQuietlyHandedOutAgain() throws SQLException {
        startAndSchedule(ActivityOptions.defaults().withStartToCloseTimeout(TIMEOUT));
        store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        expireAllLeases();

        assertThat(store.claimTask(TaskKind.ACTIVITY_TASK, "other-worker", LEASE)).isEmpty();
    }

    @Test
    void anAttemptWithoutATimeoutIsStillHandedOutAgainAfterItsLeaseExpires() throws SQLException {
        startAndSchedule(ActivityOptions.defaults());
        store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        expireAllLeases();

        assertThat(store.claimTask(TaskKind.ACTIVITY_TASK, "other-worker", LEASE)).isPresent();
        assertThat(store.timeOutExpiredActivities(10)).isZero();
    }

    @Test
    void theSweepLeavesAttemptsThatAreStillInsideTheirTimeout() throws SQLException {
        startAndSchedule(ActivityOptions.defaults().withStartToCloseTimeout(TIMEOUT));
        store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();

        assertThat(store.timeOutExpiredActivities(10)).isZero();
    }

    @Test
    void theSweepRetriesATimedOutAttemptWhenThePolicyAllows() throws SQLException {
        ActivityOptions options = ActivityOptions.retrying(RetryPolicy.exponential(3, Duration.ofSeconds(2)))
                .withStartToCloseTimeout(TIMEOUT);
        WorkflowId id = startAndSchedule(options);
        store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        expireAllLeases();

        int handled = store.timeOutExpiredActivities(10);

        assertThat(handled).isEqualTo(1);
        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "ActivityScheduled");
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'ACTIVITY_TASK' AND failures = 1 "
                + "AND locked_by IS NULL AND visible_at > now() AND last_error->>'type' = 'ActivityTimeout'"))
                .isEqualTo(1);

        execute("UPDATE task SET visible_at = now() - interval '1 second'");
        assertThat(store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow().attempt()).isEqualTo(2);
    }

    @Test
    void theSweepRecordsAFailureWhenTheAttemptsAreUsedUp() throws SQLException {
        WorkflowId id = startAndSchedule(ActivityOptions.defaults().withStartToCloseTimeout(TIMEOUT));
        store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        expireAllLeases();

        int handled = store.timeOutExpiredActivities(10);

        assertThat(handled).isEqualTo(1);
        List<HistoryEvent> history = store.history(id);
        assertThat(types(history)).containsExactly("WorkflowStarted", "ActivityScheduled", "ActivityFailed");
        assertThat(history.get(2).payloadJson()).contains("ActivityTimeout");
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'ACTIVITY_TASK'")).isZero();
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'WORKFLOW_TASK'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(3);
    }

    @Test
    void aTimedOutWorkersLateResultIsIgnored() throws SQLException {
        ActivityOptions options = ActivityOptions.retrying(RetryPolicy.exponential(3, Duration.ofSeconds(2)))
                .withStartToCloseTimeout(TIMEOUT);
        WorkflowId id = startAndSchedule(options);
        ClaimedTask slow = store.claimTask(TaskKind.ACTIVITY_TASK, "slow-worker", LEASE).orElseThrow();
        expireAllLeases();
        store.timeOutExpiredActivities(10);

        CompletionOutcome late = store.completeActivityTask(slow, ActivityResult.success("\"too late\""));

        assertThat(late).isEqualTo(CompletionOutcome.LEASE_LOST);
        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "ActivityScheduled");
    }

    @Test
    void theSweepRespectsTheLimit() throws SQLException {
        startAndSchedule(ActivityOptions.defaults().withStartToCloseTimeout(TIMEOUT));
        startAndSchedule(ActivityOptions.defaults().withStartToCloseTimeout(TIMEOUT));
        store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        expireAllLeases();

        assertThat(store.timeOutExpiredActivities(1)).isEqualTo(1);
        assertThat(store.timeOutExpiredActivities(10)).isEqualTo(1);
        assertThat(store.timeOutExpiredActivities(10)).isZero();
    }

    private WorkflowId startAndSchedule(ActivityOptions options) {
        WorkflowDefinition definition = (ctx, input) -> ctx.callActivity("charge", input, options);
        WorkflowId id = WorkflowId.random();
        store.start(id, "Charge", "{\"amount\":5}");
        ClaimedTask decision = store.claimTask(TaskKind.WORKFLOW_TASK, "decider", LEASE).orElseThrow();
        List<HistoryEvent> history = store.history(id);
        CompletionOutcome outcome = store.completeWorkflowTask(
                decision, history.get(history.size() - 1).seq(), Decider.decide(definition, history));
        assertThat(outcome).isEqualTo(CompletionOutcome.APPLIED);
        return id;
    }

    private static List<String> types(List<HistoryEvent> history) {
        return history.stream().map(HistoryEvent::type).toList();
    }
}
