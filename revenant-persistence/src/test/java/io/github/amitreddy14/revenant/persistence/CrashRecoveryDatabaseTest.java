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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The crash-recovery guarantee against a real database: a worker that dies at any point (its
 * work lost, its leases left to expire) never changes how the workflow ends.
 */
class CrashRecoveryDatabaseTest extends PostgresIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private static final WorkflowDefinition ORDER_FLOW = (ctx, input) -> {
        String charge = ctx.callActivity("charge", input);
        String stock = ctx.callActivity("reserve", charge);
        return ctx.callActivity("ship", stock);
    };

    private final WorkflowStore store = new WorkflowStore(DATA_SOURCE);

    @Test
    void killingTheWorkerAtAnyPointLeavesTheSameFinalHistory() throws SQLException {
        Run clean = run(Set.of());
        assertThat(clean.status).isEqualTo(WorkflowStatus.COMPLETED);

        for (int crashAt = 0; crashAt < clean.operations + 2; crashAt++) {
            Run crashed = run(Set.of(crashAt));

            assertThat(crashed.status).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(crashed.history).isEqualTo(clean.history);
            assertThat(crashed.activityRuns.get("charge")).isEqualTo(1 + crashed.crashedRuns.getOrDefault("charge", 0));
            assertThat(crashed.activityRuns.get("reserve")).isEqualTo(1 + crashed.crashedRuns.getOrDefault("reserve", 0));
            assertThat(crashed.activityRuns.get("ship")).isEqualTo(1 + crashed.crashedRuns.getOrDefault("ship", 0));
        }
    }

    @Test
    void aWorkerThatDiesManyTimesInARowStillFinishesTheWorkflow() throws SQLException {
        Run clean = run(Set.of());
        Set<Integer> firstTenCrash = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            firstTenCrash.add(i);
        }

        Run crashed = run(firstTenCrash);

        assertThat(crashed.status).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(crashed.history).isEqualTo(clean.history);
    }

    private record Run(
            WorkflowStatus status,
            List<String> history,
            Map<String, Integer> activityRuns,
            Map<String, Integer> crashedRuns,
            int operations) {}

    /**
     * One worker loop over the real store. Every pass through the loop is an operation; those
     * named in {@code crashedOperations} die before their result is committed, and time then
     * passes so the dead worker's lease expires.
     */
    private Run run(Set<Integer> crashedOperations) throws SQLException {
        emptyAllTables(); // each run starts from a clean database, so no leftover task can leak in
        WorkflowId id = WorkflowId.random();
        store.start(id, "OrderFlow", "{\"orderId\":42}");
        Map<String, Integer> activityRuns = new HashMap<>();
        Map<String, Integer> crashedRuns = new HashMap<>();
        int operation = 0;

        while (store.find(id).orElseThrow().status() == WorkflowStatus.RUNNING) {
            if (operation > 500) {
                throw new IllegalStateException("no progress after 500 operations");
            }
            boolean crash = crashedOperations.contains(operation++);

            Optional<ClaimedTask> activity = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE);
            if (activity.isPresent()) {
                String name = activity.get().name();
                activityRuns.merge(name, 1, Integer::sum); // the side effect happens either way
                if (crash) {
                    crashedRuns.merge(name, 1, Integer::sum);
                    expireAllLeases();
                } else {
                    store.completeActivityTask(activity.get(), ActivityResult.success("\"" + name + "-done\""));
                }
                continue;
            }

            Optional<ClaimedTask> decision = store.claimTask(TaskKind.WORKFLOW_TASK, "worker", LEASE);
            if (decision.isPresent()) {
                List<HistoryEvent> history = store.history(id);
                var result = Decider.decide(ORDER_FLOW, history);
                if (crash) {
                    expireAllLeases();
                } else {
                    store.completeWorkflowTask(decision.get(), history.get(history.size() - 1).seq(), result);
                }
                continue;
            }

            expireAllLeases(); // nothing claimable: wait for dead workers' leases to run out
        }

        List<String> finalHistory = store.history(id).stream()
                .map(e -> e.type() + "|" + e.commandId() + "|" + e.name() + "|" + e.payloadJson())
                .toList();
        return new Run(store.find(id).orElseThrow().status(), finalHistory, activityRuns, crashedRuns, operation);
    }
}
