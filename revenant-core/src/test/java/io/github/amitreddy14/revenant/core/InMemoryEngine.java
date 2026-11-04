package io.github.amitreddy14.revenant.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * A tiny single-threaded engine for tests: a history list, a queue of activity tasks, and a
 * worker loop. It exists to let tests "pull the plug" at any point.
 *
 * <p>It also models retries and timers. An activity may throw {@link RetryableFailure} or
 * {@link PermanentFailure}; failures are counted per command (surviving crashes, like a column
 * in the task table) and the command's {@link RetryPolicy} decides between another attempt and a
 * recorded {@code ActivityFailed}. Time is virtual: a timer fires as soon as nothing else is
 * waiting, and the backoff delays are collected rather than slept.
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

    /** An attempt that failed but may be tried again, if the retry policy allows. */
    static final class RetryableFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RetryableFailure(String message) {
            super(message);
        }
    }

    /** An attempt that failed and can never succeed on retry. */
    static final class PermanentFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PermanentFailure(String message) {
            super(message);
        }
    }

    /** What a run produced. */
    record Outcome(
            List<HistoryEvent> history,
            Map<String, Integer> activityRuns,
            Map<String, Integer> crashedActivityRuns,
            int operations,
            List<Duration> backoffs) {}

    private final WorkflowDefinition definition;
    private final Map<String, UnaryOperator<String>> activities;

    InMemoryEngine(WorkflowDefinition definition, Map<String, UnaryOperator<String>> activities) {
        this.definition = definition;
        this.activities = activities;
    }

    Outcome run(String inputJson, Set<Integer> crashedOperations) {
        List<HistoryEvent> history = new ArrayList<>();
        Deque<Command.ScheduleActivity> activityQueue = new ArrayDeque<>();
        Deque<Command.StartTimer> timerQueue = new ArrayDeque<>();
        Map<Long, Integer> failures = new HashMap<>();
        List<Duration> backoffs = new ArrayList<>();
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
                String result = null;
                String error = null;
                boolean retryable = false;
                try {
                    result = activities.get(task.name()).apply(task.inputJson());
                } catch (RetryableFailure e) {
                    error = JsonText.quote(e.getMessage());
                    retryable = true;
                } catch (PermanentFailure e) {
                    error = JsonText.quote(e.getMessage());
                }
                activityRuns.merge(task.name(), 1, Integer::sum);
                if (crash) {
                    crashedRuns.merge(task.name(), 1, Integer::sum);
                    continue; // side effect happened, result lost, task stays queued
                }
                if (error == null) {
                    activityQueue.poll();
                    append(history, Events.activityCompleted(task.commandId(), task.name(), result));
                    decisionNeeded = true;
                } else {
                    int failed = failures.merge(task.commandId(), 1, Integer::sum);
                    Optional<Duration> delay = retryable
                            ? task.options().retryPolicy().delayAfterFailure(failed)
                            : Optional.empty();
                    if (delay.isPresent()) {
                        backoffs.add(delay.get()); // stays queued, tried again after the wait
                    } else {
                        activityQueue.poll();
                        append(history, Events.activityFailed(task.commandId(), task.name(), error));
                        decisionNeeded = true;
                    }
                }
            } else if (!timerQueue.isEmpty()) {
                if (crash) {
                    continue; // the firing was never committed, the timer is still due
                }
                Command.StartTimer timer = timerQueue.poll();
                append(history, Events.timerFired(timer.commandId()));
                decisionNeeded = true;
            } else if (decisionNeeded) {
                Decision decision = Decider.decide(definition, history);
                if (crash) {
                    continue; // decision computed but never committed
                }
                decision.events().forEach(event -> append(history, event));
                activityQueue.addAll(decision.activitiesToSchedule());
                timerQueue.addAll(decision.timersToStart());
                decisionNeeded = false;
            }
        }
        return new Outcome(List.copyOf(history), activityRuns, crashedRuns, operation, List.copyOf(backoffs));
    }

    private static boolean closed(List<HistoryEvent> history) {
        return EventType.isTerminal(history.get(history.size() - 1).type());
    }

    private static void append(List<HistoryEvent> history, NewEvent event) {
        history.add(new HistoryEvent(
                history.size() + 1L, event.type(), event.commandId(), event.name(), event.payloadJson(), Instant.EPOCH));
    }
}
