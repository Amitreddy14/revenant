package io.github.amitreddy14.revenant.core;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * A tiny single-threaded engine for tests: a history list, a queue of activity tasks, and a
 * worker loop. It exists to let tests "pull the plug" at any point.
 *
 * <p>Every unit of work the worker attempts is an <em>operation</em>, numbered 0, 1, 2, ... in
 * the order attempted. A crash plan names operations that die:
 *
 * <ul>
 *   <li>a crashed <b>decision</b> loses its result, like a worker dying before it committed;
 *   <li>a crashed <b>activity</b> still performs its side effect but loses its result, like a
 *       worker dying right after calling the payment API.
 * </ul>
 *
 * Only the history and the task queue survive a crash, exactly as in the real system.
 */
final class InMemoryEngine {

    /** What a run produced. */
    record Outcome(
            List<HistoryEvent> history,
            Map<String, Integer> activityRuns,
            Map<String, Integer> crashedActivityRuns,
            int operations) {}

    private final WorkflowDefinition definition;
    private final Map<String, UnaryOperator<String>> activities;

    InMemoryEngine(WorkflowDefinition definition, Map<String, UnaryOperator<String>> activities) {
        this.definition = definition;
        this.activities = activities;
    }

    Outcome run(String inputJson, Set<Integer> crashedOperations) {
        List<HistoryEvent> history = new ArrayList<>();
        Deque<Command.ScheduleActivity> activityQueue = new ArrayDeque<>();
        Map<String, Integer> activityRuns = new HashMap<>();
        Map<String, Integer> crashedRuns = new HashMap<>();
        boolean decisionNeeded = true;
        int operation = 0;

        append(history, Events.workflowStarted(inputJson));

        while (!closed(history)) {
            if (operation > 10_000) {
                throw new IllegalStateException("engine made no progress after 10000 operations");
            }
            int thisOperation = operation++;
            boolean crash = crashedOperations.contains(thisOperation);

            if (!activityQueue.isEmpty()) {
                Command.ScheduleActivity task = activityQueue.peek();
                String result = activities.get(task.name()).apply(task.inputJson());
                activityRuns.merge(task.name(), 1, Integer::sum);
                if (crash) {
                    crashedRuns.merge(task.name(), 1, Integer::sum);
                    continue; // side effect happened, result lost, task stays queued
                }
                activityQueue.poll();
                append(history, Events.activityCompleted(task.commandId(), task.name(), result));
                decisionNeeded = true;
            } else if (decisionNeeded) {
                Decision decision = Decider.decide(definition, history);
                if (crash) {
                    continue; // decision computed but never committed
                }
                decision.events().forEach(event -> append(history, event));
                activityQueue.addAll(decision.activitiesToSchedule());
                decisionNeeded = false;
            }
        }
        return new Outcome(List.copyOf(history), activityRuns, crashedRuns, operation);
    }

    private static boolean closed(List<HistoryEvent> history) {
        return EventType.isTerminal(history.get(history.size() - 1).type());
    }

    private static void append(List<HistoryEvent> history, NewEvent event) {
        history.add(new HistoryEvent(
                history.size() + 1L, event.type(), event.commandId(), event.name(), event.payloadJson(), Instant.EPOCH));
    }
}
