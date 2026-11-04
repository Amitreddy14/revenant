package io.github.amitreddy14.revenant.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Crash recovery with retries and timers in the mix: retries must stay invisible in the history,
 * and a crash must never change how the workflow ends.
 */
class CrashRecoveryRetryTimerTest {

    private static final ActivityOptions RETRY_UP_TO_FIVE =
            ActivityOptions.retrying(RetryPolicy.exponential(5, Duration.ofSeconds(1)));

    /** charge (flaky, retried), wait, ship. */
    private static final WorkflowDefinition FLOW = (ctx, input) -> {
        String charge = ctx.callActivity("charge", input, RETRY_UP_TO_FIVE);
        ctx.sleep(Duration.ofHours(1));
        String shipment = ctx.callActivity("ship", charge, RETRY_UP_TO_FIVE);
        return "[" + charge + "," + shipment + "]";
    };

    /** Fresh counters each run: "charge" fails on its first two runs, "ship" always works. */
    private static InMemoryEngine freshEngine() {
        AtomicInteger chargeRuns = new AtomicInteger();
        Map<String, UnaryOperator<String>> activities = new HashMap<>();
        activities.put("charge", in -> {
            if (chargeRuns.incrementAndGet() <= 2) {
                throw new InMemoryEngine.RetryableFailure("card service busy");
            }
            return "\"charged\"";
        });
        activities.put("ship", in -> "\"shipped\"");
        return new InMemoryEngine(FLOW, activities);
    }

    @Test
    void retriesAndTimersLeaveACleanHistory() {
        InMemoryEngine.Outcome outcome = freshEngine().run("{}", Set.of());

        assertThat(outcome.history().stream().map(HistoryEvent::type).toList()).containsExactly(
                "WorkflowStarted",
                "ActivityScheduled", "ActivityCompleted",
                "TimerStarted", "TimerFired",
                "ActivityScheduled", "ActivityCompleted",
                "WorkflowCompleted");
        assertThat(outcome.activityRuns().get("charge")).isEqualTo(3);
        assertThat(outcome.backoffs()).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void aSingleCrashAtAnyMomentChangesNothingInTheFinalHistory() {
        InMemoryEngine.Outcome clean = freshEngine().run("{}", Set.of());

        for (int crashAt = 0; crashAt < clean.operations() + 3; crashAt++) {
            InMemoryEngine.Outcome crashed = freshEngine().run("{}", Set.of(crashAt));

            assertThat(crashed.history()).isEqualTo(clean.history());
        }
    }

    @Test
    void anyTwoCrashesChangeNothingInTheFinalHistory() {
        InMemoryEngine.Outcome clean = freshEngine().run("{}", Set.of());
        int limit = clean.operations() + 4;

        for (int first = 0; first < limit; first++) {
            for (int second = first; second < limit; second++) {
                InMemoryEngine.Outcome crashed = freshEngine().run("{}", new HashSet<>(List.of(first, second)));

                assertThat(crashed.history()).isEqualTo(clean.history());
            }
        }
    }

    @Test
    void randomCrashStormsNeverCorruptTheHistory() {
        InMemoryEngine.Outcome clean = freshEngine().run("{}", Set.of());
        Random random = new Random(7);

        for (int round = 0; round < 500; round++) {
            Set<Integer> crashes = new HashSet<>();
            for (int i = 0; i < 40; i++) {
                if (random.nextInt(100) < 35) {
                    crashes.add(i);
                }
            }

            assertThat(freshEngine().run("{}", crashes).history()).isEqualTo(clean.history());
        }
    }

    @Test
    void anActivityThatKeepsFailingIsRecordedAsFailedOnlyAfterTheLastAttempt() {
        WorkflowDefinition flow = (ctx, input) -> {
            try {
                return ctx.callActivity("charge", input, RETRY_UP_TO_FIVE);
            } catch (ActivityFailedException e) {
                return "\"gave up\"";
            }
        };
        InMemoryEngine engine = new InMemoryEngine(flow, Map.of("charge", in -> {
            throw new InMemoryEngine.RetryableFailure("down");
        }));

        InMemoryEngine.Outcome outcome = engine.run("{}", Set.of());

        assertThat(outcome.activityRuns().get("charge")).isEqualTo(5);
        assertThat(outcome.backoffs()).hasSize(4);
        assertThat(outcome.history().stream().map(HistoryEvent::type).toList()).containsExactly(
                "WorkflowStarted", "ActivityScheduled", "ActivityFailed", "WorkflowCompleted");
    }

    @Test
    void aPermanentFailureIsNotRetried() {
        WorkflowDefinition flow = (ctx, input) -> {
            try {
                return ctx.callActivity("validate", input, RETRY_UP_TO_FIVE);
            } catch (ActivityFailedException e) {
                return "\"rejected\"";
            }
        };
        InMemoryEngine engine = new InMemoryEngine(flow, Map.of("validate", in -> {
            throw new InMemoryEngine.PermanentFailure("bad input");
        }));

        InMemoryEngine.Outcome outcome = engine.run("{}", Set.of());

        assertThat(outcome.activityRuns().get("validate")).isEqualTo(1);
        assertThat(outcome.backoffs()).isEmpty();
    }

    @Test
    void withoutARetryPolicyOneFailureIsFinal() {
        WorkflowDefinition flow = (ctx, input) -> {
            try {
                return ctx.callActivity("charge", input);
            } catch (ActivityFailedException e) {
                return "\"declined\"";
            }
        };
        InMemoryEngine engine = new InMemoryEngine(flow, Map.of("charge", in -> {
            throw new InMemoryEngine.RetryableFailure("declined");
        }));

        InMemoryEngine.Outcome outcome = engine.run("{}", Set.of());

        assertThat(outcome.activityRuns().get("charge")).isEqualTo(1);
    }
}
