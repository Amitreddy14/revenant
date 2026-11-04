package io.github.amitreddy14.revenant.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The {@link WorkflowContext} used while replaying. Each {@code callActivity} is matched, in
 * order, against the history:
 *
 * <ul>
 *   <li>result recorded: return it (or throw, if the activity failed). No work is done.
 *   <li>scheduled but no result yet: pause, the activity is still running.
 *   <li>not scheduled: this is the first unfinished step. Record a new command and pause.
 * </ul>
 *
 * <p>Timers work the same way: {@code sleep} is matched against a recorded timer and pauses until
 * it has fired. Activity calls and timers share one numbering.
 *
 * <p>Only the activity <em>name</em> is compared with the history, not the input: stored payloads
 * are normalised by PostgreSQL's JSONB, so the same input can come back with different spacing
 * or key order.
 */
final class ReplayContext implements WorkflowContext {

    private final HistoryView history;
    private final List<Command> newCommands = new ArrayList<>();
    private long commandsIssued;
    private boolean suspended;
    private NonDeterminismException violation;

    ReplayContext(HistoryView history) {
        this.history = history;
    }

    @Override
    public String callActivity(String name, String inputJson, ActivityOptions options) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(inputJson, "inputJson");
        Objects.requireNonNull(options, "options");
        if (suspended) {
            // Workflow code swallowed the pause and kept going. Refuse to do anything more.
            throw new WorkflowSuspendedError();
        }

        long commandId = ++commandsIssued;
        HistoryEvent scheduled = history.scheduled(commandId);

        if (scheduled == null) {
            newCommands.add(new Command.ScheduleActivity(commandId, name, inputJson, options));
            suspended = true;
            throw new WorkflowSuspendedError();
        }

        if (!EventType.ACTIVITY_SCHEDULED.equals(scheduled.type())) {
            violation = new NonDeterminismException("command " + commandId + " was recorded as a timer"
                    + " but the workflow code now calls activity '" + name + "'");
            throw violation;
        }
        if (!scheduled.name().equals(name)) {
            violation = new NonDeterminismException("command " + commandId + " was recorded as activity '"
                    + scheduled.name() + "' but the workflow code now calls '" + name + "'");
            throw violation;
        }

        HistoryEvent result = history.finished(commandId);
        if (result == null) {
            suspended = true;
            throw new WorkflowSuspendedError();
        }
        if (EventType.ACTIVITY_COMPLETED.equals(result.type())) {
            return result.payloadJson();
        }
        throw new ActivityFailedException(name, commandId, result.payloadJson());
    }

    @Override
    public void sleep(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative, was " + duration);
        }
        if (suspended) {
            throw new WorkflowSuspendedError();
        }

        long commandId = ++commandsIssued;
        HistoryEvent started = history.scheduled(commandId);

        if (started == null) {
            newCommands.add(new Command.StartTimer(commandId, duration.toMillis()));
            suspended = true;
            throw new WorkflowSuspendedError();
        }
        if (!EventType.TIMER_STARTED.equals(started.type())) {
            violation = new NonDeterminismException("command " + commandId + " was recorded as activity '"
                    + started.name() + "' but the workflow code now sleeps");
            throw violation;
        }
        if (history.finished(commandId) == null) {
            suspended = true;
            throw new WorkflowSuspendedError();
        }
    }

    List<Command> newCommands() {
        return newCommands;
    }

    /** Re-throws a non-determinism problem even if the workflow code caught and ignored it. */
    void throwIfViolated() {
        if (violation != null) {
            throw violation;
        }
    }

    /**
     * Every activity call or timer the history records must have been issued again by the code.
     * If the code stopped short of one, the code and the history have diverged.
     */
    void verifyHistoryFullyConsumed() {
        long recorded = history.lastCommandId();
        if (commandsIssued < recorded) {
            HistoryEvent missing = history.scheduled(commandsIssued + 1);
            String what = EventType.TIMER_STARTED.equals(missing.type()) ? "a timer" : "activity '" + missing.name() + "'";
            throw new NonDeterminismException("the history has " + what + " as command "
                    + missing.commandId() + " but the workflow code stopped after " + commandsIssued + " call(s)");
        }
    }
}
