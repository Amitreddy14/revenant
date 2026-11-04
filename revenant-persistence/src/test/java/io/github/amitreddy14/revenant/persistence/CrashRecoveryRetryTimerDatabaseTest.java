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
import io.github.amitreddy14.revenant.core.WorkflowStatus;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Crash recovery with flaky activities, retries and a timer in the workflow: however the worker
 * dies, the workflow ends with the same history as a run without crashes.
 */
class CrashRecoveryRetryTimerDatabaseTest extends PostgresIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final ActivityOptions RETRY_UP_TO_FIVE = ActivityOptions
            .retrying(RetryPolicy.exponential(5, Duration.ofSeconds(1)))
            .withStartToCloseTimeout(Duration.ofSeconds(30));

    /** charge (flaky, retried), wait an hour, ship. */
    private static final WorkflowDefinition FLOW = (ctx, input) -> {
        String charge = ctx.callActivity("charge", input, RETRY_UP_TO_FIVE);
        ctx.sleep(Duration.ofHours(1));
        String shipment = ctx.callActivity("ship", charge, RETRY_UP_TO_FIVE);
        return "[" + charge + "," + shipment + "]";
    };

    private final WorkflowStore store = new WorkflowStore(DATA_SOURCE);

    @Test
    void killingTheWorkerAtAnyPointLeavesTheSameFinalHistory() throws SQLException {
        Run clean = run(Set.of());
        assertThat(clean.status).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(clean.history).contains("TimerStarted|2|null|{\"durationMillis\": 3600000}");

        for (int crashAt = 0; crashAt < clean.operations + 3; crashAt++) {
            Run crashed = run(Set.of(crashAt));

            assertThat(crashed.status).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(crashed.history).isEqualTo(clean.history);
        }
    }

    @Test
    void aWorkerThatDiesManyTimesInARowStillFinishesTheWorkflow() throws SQLException {
        Run clean = run(Set.of());
        Set<Integer> firstTwelveCrash = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            firstTwelveCrash.add(i);
        }

        Run crashed = run(firstTwelveCrash);

        assertThat(crashed.status).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(crashed.history).isEqualTo(clean.history);
    }

    private record Run(WorkflowStatus status, List<String> history, int operations) {}

    /**
     * One worker loop over the real store, with a sweeper for timeouts and timers. Operations named
     * in {@code crashedOperations} die before committing. When nothing can be claimed, time
     * "passes": leases expire, backoffs end and timers fall due.
     */
    private Run run(Set<Integer> crashedOperations) throws SQLException {
        emptyAllTables();
        AtomicInteger chargeRuns = new AtomicInteger(); // "charge" fails on its first two runs
        WorkflowId id = WorkflowId.random();
        store.start(id, "RetryTimerFlow", "{\"orderId\":42}");
        int operation = 0;

        while (store.find(id).orElseThrow().status() == WorkflowStatus.RUNNING) {
            if (operation > 500) {
                throw new IllegalStateException("no progress after 500 operations");
            }
            boolean crash = crashedOperations.contains(operation++);

            Optional<ClaimedTask> activity = store.claimTask(TaskKind.ACTIVITY_TASK, "worker", LEASE);
            if (activity.isPresent()) {
                String name = activity.get().name();
                boolean flaky = "charge".equals(name) && chargeRuns.incrementAndGet() <= 2;
                if (crash) {
                    expireAllLeases();
                } else if (flaky) {
                    store.completeActivityTask(activity.get(), ActivityResult.failure("{\"message\":\"busy\"}"));
                } else {
                    store.completeActivityTask(activity.get(), ActivityResult.success("\"" + name + "-done\""));
                }
                continue;
            }

            Optional<ClaimedTask> decision = store.claimTask(TaskKind.WORKFLOW_TASK, "worker", LEASE);
            if (decision.isPresent()) {
                List<HistoryEvent> history = store.history(id);
                var result = Decider.decide(FLOW, history);
                if (crash) {
                    expireAllLeases();
                } else {
                    store.completeWorkflowTask(decision.get(), history.get(history.size() - 1).seq(), result);
                }
                continue;
            }

            if (!crash && store.fireDueTimers(1) > 0) {
                continue;
            }
            store.timeOutExpiredActivities(10);
            timePasses();
        }

        List<String> finalHistory = store.history(id).stream()
                .map(e -> e.type() + "|" + e.commandId() + "|" + e.name() + "|" + e.payloadJson())
                .toList();
        return new Run(store.find(id).orElseThrow().status(), finalHistory, operation);
    }

    private static void timePasses() throws SQLException {
        expireAllLeases();
        execute("UPDATE task SET visible_at = now() - interval '1 second' WHERE visible_at > now()");
        execute("UPDATE timer SET fire_at = now() - interval '1 second' WHERE fired_at IS NULL");
    }
}
