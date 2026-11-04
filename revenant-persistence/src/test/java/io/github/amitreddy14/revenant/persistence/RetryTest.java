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

/** Activity retries with backoff, against a real database. */
class RetryTest extends PostgresIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String ERROR = "{\"message\":\"card service busy\"}";

    private final WorkflowStore store = new WorkflowStore(DATA_SOURCE);

    private static WorkflowDefinition chargeWith(ActivityOptions options) {
        return (ctx, input) -> ctx.callActivity("charge", input, options);
    }

    private static ActivityOptions upTo(int attempts) {
        return ActivityOptions.retrying(RetryPolicy.exponential(attempts, Duration.ofSeconds(2)));
    }

    @Test
    void theRetryPolicyAndTimeoutTravelWithTheActivityTask() throws SQLException {
        ActivityOptions options = upTo(4).withStartToCloseTimeout(Duration.ofSeconds(30));

        startAndSchedule(options);

        assertThat(count("SELECT count(*) FROM task WHERE kind = 'ACTIVITY_TASK' AND max_attempts = 4 "
                + "AND initial_interval_ms = 2000 AND backoff_coefficient = 2.0 AND max_interval_ms = 200000 "
                + "AND start_to_close_ms = 30000 AND failures = 0")).isEqualTo(1);
    }

    @Test
    void aPlainCallStoresNoRetriesAndNoTimeout() throws SQLException {
        startAndSchedule(ActivityOptions.defaults());

        assertThat(count("SELECT count(*) FROM task WHERE kind = 'ACTIVITY_TASK' AND max_attempts = 1 "
                + "AND start_to_close_ms IS NULL")).isEqualTo(1);
    }

    @Test
    void aFailureWithRetriesLeftSchedulesARetryAndLeavesTheHistoryAlone() throws SQLException {
        WorkflowId id = startAndSchedule(upTo(3));
        ClaimedTask attempt = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();

        CompletionOutcome outcome = store.completeActivityTask(attempt, ActivityResult.failure(ERROR));

        assertThat(outcome).isEqualTo(CompletionOutcome.RETRY_SCHEDULED);
        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "ActivityScheduled");
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'WORKFLOW_TASK'")).isZero();
        assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'ACTIVITY_TASK' AND failures = 1 "
                + "AND locked_by IS NULL AND locked_until IS NULL AND visible_at > now() "
                + "AND last_error->>'message' = 'card service busy'")).isEqualTo(1);
    }

    @Test
    void theRetryWaitsForItsBackoff() throws SQLException {
        startAndSchedule(upTo(3));
        ClaimedTask first = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        store.completeActivityTask(first, ActivityResult.failure(ERROR));

        assertThat(store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE)).isEmpty();

        backoffHasPassed();
        ClaimedTask second = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(second.commandId()).isEqualTo(1L);
    }

    @Test
    void theWaitGrowsWithEachFailure() throws SQLException {
        startAndSchedule(upTo(4));

        ClaimedTask first = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        store.completeActivityTask(first, ActivityResult.failure(ERROR));
        assertThat(count("SELECT count(*) FROM task WHERE visible_at BETWEEN now() + interval '1 second' "
                + "AND now() + interval '3 seconds'")).isEqualTo(1); // about 2 s

        backoffHasPassed();
        ClaimedTask second = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        store.completeActivityTask(second, ActivityResult.failure(ERROR));
        assertThat(count("SELECT count(*) FROM task WHERE visible_at BETWEEN now() + interval '3 seconds' "
                + "AND now() + interval '5 seconds'")).isEqualTo(1); // about 4 s
    }

    @Test
    void anAttemptThatSucceedsAfterRetriesCompletesTheActivity() throws SQLException {
        WorkflowId id = startAndSchedule(upTo(3));
        ClaimedTask first = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        store.completeActivityTask(first, ActivityResult.failure(ERROR));
        backoffHasPassed();
        ClaimedTask second = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();

        CompletionOutcome outcome = store.completeActivityTask(second, ActivityResult.success("\"charged\""));

        assertThat(outcome).isEqualTo(CompletionOutcome.APPLIED);
        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "ActivityScheduled", "ActivityCompleted");
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'ACTIVITY_TASK'")).isZero();
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'WORKFLOW_TASK'")).isEqualTo(1);
    }

    @Test
    void whenTheAttemptsAreUsedUpTheLastErrorIsRecordedAsTheFailure() throws SQLException {
        WorkflowId id = startAndSchedule(upTo(2));
        ClaimedTask first = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        assertThat(store.completeActivityTask(first, ActivityResult.failure("{\"n\":1}")))
                .isEqualTo(CompletionOutcome.RETRY_SCHEDULED);
        backoffHasPassed();
        ClaimedTask second = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();

        CompletionOutcome outcome = store.completeActivityTask(second, ActivityResult.failure("{\"n\":2}"));

        assertThat(outcome).isEqualTo(CompletionOutcome.APPLIED);
        List<HistoryEvent> history = store.history(id);
        assertThat(types(history)).containsExactly("WorkflowStarted", "ActivityScheduled", "ActivityFailed");
        assertThat(history.get(2).payloadJson()).contains("\"n\": 2"); // JSONB normalises spacing
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'ACTIVITY_TASK'")).isZero();
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'WORKFLOW_TASK'")).isEqualTo(1);
    }

    @Test
    void aPlainCallFailsOnTheFirstFailure() throws SQLException {
        WorkflowId id = startAndSchedule(ActivityOptions.defaults());
        ClaimedTask attempt = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();

        CompletionOutcome outcome = store.completeActivityTask(attempt, ActivityResult.failure(ERROR));

        assertThat(outcome).isEqualTo(CompletionOutcome.APPLIED);
        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "ActivityScheduled", "ActivityFailed");
    }

    @Test
    void aNonRetryableFailureIsRecordedAtOnceEvenWithAttemptsLeft() throws SQLException {
        WorkflowId id = startAndSchedule(upTo(5));
        ClaimedTask attempt = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();

        CompletionOutcome outcome =
                store.completeActivityTask(attempt, ActivityResult.nonRetryableFailure("{\"message\":\"bad input\"}"));

        assertThat(outcome).isEqualTo(CompletionOutcome.APPLIED);
        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "ActivityScheduled", "ActivityFailed");
    }

    @Test
    void aWorkerCannotReportTheSameFailureTwice() throws SQLException {
        startAndSchedule(upTo(5));
        ClaimedTask attempt = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        store.completeActivityTask(attempt, ActivityResult.failure(ERROR));

        CompletionOutcome again = store.completeActivityTask(attempt, ActivityResult.failure(ERROR));

        assertThat(again).isEqualTo(CompletionOutcome.LEASE_LOST);
        assertThat(count("SELECT count(*) FROM task WHERE failures = 1")).isEqualTo(1);
    }

    @Test
    void aWorkerThatFailedCannotLaterSneakInASuccess() throws SQLException {
        WorkflowId id = startAndSchedule(upTo(5));
        ClaimedTask attempt = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        store.completeActivityTask(attempt, ActivityResult.failure(ERROR));

        CompletionOutcome late = store.completeActivityTask(attempt, ActivityResult.success("\"late\""));

        assertThat(late).isEqualTo(CompletionOutcome.LEASE_LOST);
        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "ActivityScheduled");
    }

    @Test
    void aStaleWorkerCannotAnswerTheRetry() throws SQLException {
        WorkflowId id = startAndSchedule(upTo(5));
        ClaimedTask slow = store.claimTask(TaskKind.ACTIVITY_TASK, "slow-worker", LEASE).orElseThrow();
        expireAllLeases();
        ClaimedTask newOwner = store.claimTask(TaskKind.ACTIVITY_TASK, "new-owner", LEASE).orElseThrow();

        assertThat(store.completeActivityTask(slow, ActivityResult.failure(ERROR)))
                .isEqualTo(CompletionOutcome.LEASE_LOST);
        assertThat(store.completeActivityTask(slow, ActivityResult.success("\"late\"")))
                .isEqualTo(CompletionOutcome.LEASE_LOST);
        assertThat(store.completeActivityTask(newOwner, ActivityResult.success("\"fresh\"")))
                .isEqualTo(CompletionOutcome.APPLIED);
        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "ActivityScheduled", "ActivityCompleted");
    }

    @Test
    void aCrashedWorkerDoesNotUseUpAnAttempt() throws SQLException {
        WorkflowId id = startAndSchedule(upTo(2));
        store.claimTask(TaskKind.ACTIVITY_TASK, "worker-that-dies", LEASE).orElseThrow();
        expireAllLeases();
        ClaimedTask second = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE).orElseThrow();
        assertThat(second.attempt()).isEqualTo(2);

        // Only one failure so far, so with two attempts allowed a retry is still available.
        CompletionOutcome outcome = store.completeActivityTask(second, ActivityResult.failure(ERROR));

        assertThat(outcome).isEqualTo(CompletionOutcome.RETRY_SCHEDULED);
        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "ActivityScheduled");
    }

    /** Starts a one-activity workflow and commits its first decision, so the activity task exists. */
    private WorkflowId startAndSchedule(ActivityOptions options) {
        WorkflowId id = WorkflowId.random();
        store.start(id, "Charge", "{\"amount\":5}");
        ClaimedTask decision = store.claimTask(TaskKind.WORKFLOW_TASK, "decider", LEASE).orElseThrow();
        List<HistoryEvent> history = store.history(id);
        CompletionOutcome outcome = store.completeWorkflowTask(
                decision, history.get(history.size() - 1).seq(), Decider.decide(chargeWith(options), history));
        assertThat(outcome).isEqualTo(CompletionOutcome.APPLIED);
        return id;
    }

    /** Pretends the backoff wait has passed. */
    private static void backoffHasPassed() throws SQLException {
        execute("UPDATE task SET visible_at = now() - interval '1 second'");
    }

    private static List<String> types(List<HistoryEvent> history) {
        return history.stream().map(HistoryEvent::type).toList();
    }
}
