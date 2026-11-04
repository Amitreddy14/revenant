package io.github.amitreddy14.revenant.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amitreddy14.revenant.core.ActivityResult;
import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.Decider;
import io.github.amitreddy14.revenant.core.Decision;
import io.github.amitreddy14.revenant.core.HistoryEvent;
import io.github.amitreddy14.revenant.core.NewEvent;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WorkflowDefinition;
import io.github.amitreddy14.revenant.core.WorkflowId;
import io.github.amitreddy14.revenant.core.WorkflowStatus;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompletionTest extends PostgresIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private static final WorkflowDefinition TWO_STEPS = (ctx, input) -> {
        String charged = ctx.callActivity("charge", input);
        return ctx.callActivity("ship", charged);
    };

    private final WorkflowStore store = new WorkflowStore(DATA_SOURCE);
    private final Transactions transactions = new Transactions(DATA_SOURCE);
    private final WorkflowRepository workflows = new WorkflowRepository();

    @Test
    void aDecisionIsCommittedAsOneStep() throws SQLException {
        WorkflowId id = startWorkflow();
        ClaimedTask task = store.claimTask(TaskKind.WORKFLOW_TASK, "worker-1", LEASE).orElseThrow();
        List<HistoryEvent> history = store.history(id);

        Decision decision = Decider.decide(TWO_STEPS, history);
        CompletionOutcome outcome = store.completeWorkflowTask(task, lastSeq(history), decision);

        assertThat(outcome).isEqualTo(CompletionOutcome.APPLIED);
        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "ActivityScheduled");
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'WORKFLOW_TASK'")).isZero();
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'ACTIVITY_TASK' AND command_id = 1 AND name = 'charge'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(2);
    }

    @Test
    void aWorkerThatLostItsLeaseCannotCommitADecision() throws SQLException {
        WorkflowId id = startWorkflow();
        ClaimedTask slowWorkerTask = store.claimTask(TaskKind.WORKFLOW_TASK, "slow-worker", LEASE).orElseThrow();
        List<HistoryEvent> history = store.history(id);
        Decision decision = Decider.decide(TWO_STEPS, history);

        expireAllLeases();
        ClaimedTask newOwnerTask = store.claimTask(TaskKind.WORKFLOW_TASK, "new-owner", LEASE).orElseThrow();

        assertThat(store.completeWorkflowTask(slowWorkerTask, lastSeq(history), decision))
                .isEqualTo(CompletionOutcome.LEASE_LOST);
        assertThat(store.history(id)).hasSize(1);
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'ACTIVITY_TASK'")).isZero();

        assertThat(store.completeWorkflowTask(newOwnerTask, lastSeq(history), decision))
                .isEqualTo(CompletionOutcome.APPLIED);
        assertThat(store.history(id)).hasSize(2);
    }

    @Test
    void aDecisionBasedOnOldHistoryIsDroppedInsteadOfCorruptingIt() throws SQLException {
        WorkflowId id = startWorkflow();
        ClaimedTask task = store.claimTask(TaskKind.WORKFLOW_TASK, "worker-1", LEASE).orElseThrow();
        List<HistoryEvent> oldHistory = store.history(id);
        Decision staleDecision = Decider.decide(TWO_STEPS, oldHistory);

        // While the worker was deciding, another event landed.
        transactions.executeVoid(connection ->
                workflows.append(connection, id, 1, List.of(new NewEvent("ActivityScheduled", 1L, "charge", "{}"))));

        assertThat(store.completeWorkflowTask(task, lastSeq(oldHistory), staleDecision))
                .isEqualTo(CompletionOutcome.STALE_DISCARDED);
        assertThat(store.history(id)).hasSize(2);
        assertThat(count("SELECT count(*) FROM task")).isZero();
    }

    @Test
    void anActivityResultIsRecordedAndWakesTheWorkflow() throws SQLException {
        WorkflowId id = startWorkflow();
        runOneDecision(id);
        ClaimedTask activity = store.claimTask(TaskKind.ACTIVITY_TASK, "worker-1", LEASE).orElseThrow();

        assertThat(activity.commandId()).isEqualTo(1L);
        assertThat(activity.name()).isEqualTo("charge");

        CompletionOutcome outcome = store.completeActivityTask(activity, ActivityResult.success("\"charged\""));

        assertThat(outcome).isEqualTo(CompletionOutcome.APPLIED);
        List<HistoryEvent> history = store.history(id);
        assertThat(types(history)).containsExactly("WorkflowStarted", "ActivityScheduled", "ActivityCompleted");
        assertThat(history.get(2).commandId()).isEqualTo(1L);
        assertThat(history.get(2).payloadJson()).isEqualTo("\"charged\"");
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'WORKFLOW_TASK'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM task WHERE kind = 'ACTIVITY_TASK'")).isZero();
    }

    @Test
    void aFailedActivityIsRecordedAsFailed() throws SQLException {
        WorkflowId id = startWorkflow();
        runOneDecision(id);
        ClaimedTask activity = store.claimTask(TaskKind.ACTIVITY_TASK, "worker-1", LEASE).orElseThrow();

        store.completeActivityTask(activity, ActivityResult.failure("{\"reason\":\"declined\"}"));

        assertThat(types(store.history(id))).containsExactly("WorkflowStarted", "ActivityScheduled", "ActivityFailed");
    }

    @Test
    void reportingTheSameActivityResultTwiceRecordsItOnce() throws SQLException {
        WorkflowId id = startWorkflow();
        runOneDecision(id);
        ClaimedTask activity = store.claimTask(TaskKind.ACTIVITY_TASK, "worker-1", LEASE).orElseThrow();

        CompletionOutcome first = store.completeActivityTask(activity, ActivityResult.success("1"));
        CompletionOutcome second = store.completeActivityTask(activity, ActivityResult.success("1"));

        assertThat(first).isEqualTo(CompletionOutcome.APPLIED);
        assertThat(second).isEqualTo(CompletionOutcome.LEASE_LOST);
        assertThat(store.history(id)).hasSize(3);
    }

    @Test
    void aLateActivityResultFromAWorkerThatLostItsLeaseIsTurnedAway() throws SQLException {
        WorkflowId id = startWorkflow();
        runOneDecision(id);
        ClaimedTask slow = store.claimTask(TaskKind.ACTIVITY_TASK, "slow-worker", LEASE).orElseThrow();

        expireAllLeases();
        ClaimedTask replacement = store.claimTask(TaskKind.ACTIVITY_TASK, "new-owner", LEASE).orElseThrow();

        assertThat(store.completeActivityTask(slow, ActivityResult.success("\"late\"")))
                .isEqualTo(CompletionOutcome.LEASE_LOST);
        assertThat(store.completeActivityTask(replacement, ActivityResult.success("\"fresh\"")))
                .isEqualTo(CompletionOutcome.APPLIED);
        assertThat(store.history(id).get(2).payloadJson()).isEqualTo("\"fresh\"");
    }

    @Test
    void finishingTheWorkflowClosesIt() throws SQLException {
        WorkflowId id = startWorkflow();
        runOneDecision(id);
        finishActivity("\"charged\"");
        runOneDecision(id);
        finishActivity("\"shipped\"");
        runOneDecision(id);

        var execution = store.find(id).orElseThrow();
        assertThat(execution.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(types(store.history(id)).get(5)).isEqualTo("WorkflowCompleted");
        assertThat(count("SELECT count(*) FROM workflow_execution WHERE closed_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void aClosedWorkflowRefusesFurtherHistory() {
        WorkflowId id = startWorkflow();
        runOneDecision(id);
        finishActivity("\"charged\"");
        runOneDecision(id);
        finishActivity("\"shipped\"");
        runOneDecision(id);

        assertThatThrownBy(() -> transactions.executeVoid(connection ->
                        workflows.append(connection, id, 6, List.of(new NewEvent("Late", "{}")))))
                .isInstanceOf(AppendConflictException.class);
    }

    @Test
    void aTaskOfTheWrongKindIsRejectedBeforeTouchingTheDatabase() {
        startWorkflow();
        ClaimedTask workflowTask = store.claimTask(TaskKind.WORKFLOW_TASK, "worker-1", LEASE).orElseThrow();

        assertThatThrownBy(() -> store.completeActivityTask(workflowTask, ActivityResult.success("1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private WorkflowId startWorkflow() {
        WorkflowId id = WorkflowId.random();
        store.start(id, "TwoSteps", "{\"amount\":5}");
        return id;
    }

    /** Claims the next workflow task, replays, and commits the decision. */
    private void runOneDecision(WorkflowId id) {
        ClaimedTask task = store.claimTask(TaskKind.WORKFLOW_TASK, "decider", LEASE).orElseThrow();
        List<HistoryEvent> history = store.history(id);
        CompletionOutcome outcome = store.completeWorkflowTask(task, lastSeq(history), Decider.decide(TWO_STEPS, history));
        assertThat(outcome).isEqualTo(CompletionOutcome.APPLIED);
    }

    private void finishActivity(String resultJson) {
        ClaimedTask activity = store.claimTask(TaskKind.ACTIVITY_TASK, "runner", LEASE).orElseThrow();
        assertThat(store.completeActivityTask(activity, ActivityResult.success(resultJson)))
                .isEqualTo(CompletionOutcome.APPLIED);
    }

    private static long lastSeq(List<HistoryEvent> history) {
        return history.get(history.size() - 1).seq();
    }

    private static List<String> types(List<HistoryEvent> history) {
        return history.stream().map(HistoryEvent::type).toList();
    }
}
