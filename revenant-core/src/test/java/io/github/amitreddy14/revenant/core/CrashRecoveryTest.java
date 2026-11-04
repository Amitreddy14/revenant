package io.github.amitreddy14.revenant.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The headline claim, tested: kill the worker at ANY point, any number of times, and the
 * workflow still ends with exactly the same history it would have had without any crash.
 */
class CrashRecoveryTest {

    private static final WorkflowDefinition ORDER_FLOW = (ctx, input) -> {
        String charge = ctx.callActivity("charge", input);
        String stock = ctx.callActivity("reserve", charge);
        String shipment = ctx.callActivity("ship", stock);
        return "[" + charge + "," + stock + "," + shipment + "]";
    };

    private static final Map<String, UnaryOperator<String>> ACTIVITIES = Map.of(
            "charge", in -> "\"charged\"",
            "reserve", in -> "\"reserved\"",
            "ship", in -> "\"shipped\"");

    private final InMemoryEngine engine = new InMemoryEngine(ORDER_FLOW, ACTIVITIES);

    @Test
    void aRunWithoutCrashesRunsEachActivityOnceAndCompletes() {
        InMemoryEngine.Outcome outcome = engine.run("{}", Set.of());

        assertThat(types(outcome.history())).containsExactly(
                "WorkflowStarted",
                "ActivityScheduled", "ActivityCompleted",
                "ActivityScheduled", "ActivityCompleted",
                "ActivityScheduled", "ActivityCompleted",
                "WorkflowCompleted");
        assertThat(lastPayload(outcome)).isEqualTo("[\"charged\",\"reserved\",\"shipped\"]");
        assertThat(outcome.activityRuns().get("charge")).isEqualTo(1);
        assertThat(outcome.activityRuns().get("reserve")).isEqualTo(1);
        assertThat(outcome.activityRuns().get("ship")).isEqualTo(1);
    }

    @Test
    void aSingleCrashAtAnyMomentChangesNothingInTheFinalHistory() {
        InMemoryEngine.Outcome clean = engine.run("{}", Set.of());

        for (int crashAt = 0; crashAt < clean.operations() + 3; crashAt++) {
            InMemoryEngine.Outcome crashed = engine.run("{}", Set.of(crashAt));

            assertThat(crashed.history()).isEqualTo(clean.history());
            assertActivitiesRanOncePlusCrashes(crashed);
        }
    }

    @Test
    void anyTwoCrashesChangeNothingInTheFinalHistory() {
        InMemoryEngine.Outcome clean = engine.run("{}", Set.of());
        int limit = clean.operations() + 4;

        for (int first = 0; first < limit; first++) {
            for (int second = first; second < limit; second++) {
                InMemoryEngine.Outcome crashed = engine.run("{}", new HashSet<>(List.of(first, second)));

                assertThat(crashed.history()).isEqualTo(clean.history());
                assertActivitiesRanOncePlusCrashes(crashed);
            }
        }
    }

    @Test
    void aWorkerThatKeepsDyingForManyAttemptsStillGetsThere() {
        InMemoryEngine.Outcome clean = engine.run("{}", Set.of());
        Set<Integer> firstTwentyAttemptsCrash = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            firstTwentyAttemptsCrash.add(i);
        }

        InMemoryEngine.Outcome crashed = engine.run("{}", firstTwentyAttemptsCrash);

        assertThat(crashed.history()).isEqualTo(clean.history());
        assertActivitiesRanOncePlusCrashes(crashed);
    }

    @Test
    void randomCrashStormsNeverCorruptTheHistory() {
        InMemoryEngine.Outcome clean = engine.run("{}", Set.of());
        Random random = new Random(42);

        for (int round = 0; round < 500; round++) {
            Set<Integer> crashes = new HashSet<>();
            for (int i = 0; i < 40; i++) {
                if (random.nextInt(100) < 35) {
                    crashes.add(i);
                }
            }
            InMemoryEngine.Outcome crashed = engine.run("{}", crashes);

            assertThat(crashed.history()).isEqualTo(clean.history());
            assertActivitiesRanOncePlusCrashes(crashed);
        }
    }

    /** At-least-once: an activity runs exactly once, plus once more for each crash that ate its result. */
    private static void assertActivitiesRanOncePlusCrashes(InMemoryEngine.Outcome outcome) {
        for (String name : List.of("charge", "reserve", "ship")) {
            int crashes = outcome.crashedActivityRuns().getOrDefault(name, 0);
            assertThat(outcome.activityRuns().get(name)).isEqualTo(1 + crashes);
        }
    }

    private static List<String> types(List<HistoryEvent> history) {
        List<String> types = new ArrayList<>();
        history.forEach(event -> types.add(event.type()));
        return types;
    }

    private static String lastPayload(InMemoryEngine.Outcome outcome) {
        return outcome.history().get(outcome.history().size() - 1).payloadJson();
    }
}
