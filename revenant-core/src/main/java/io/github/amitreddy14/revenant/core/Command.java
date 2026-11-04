package io.github.amitreddy14.revenant.core;

import java.util.Objects;

/**
 * Something the workflow code asked the engine to do while being replayed. Activity calls and
 * timers share one numbering: 1 for the first call of either kind, 2 for the second, and so on.
 */
public sealed interface Command {

    /** Run an activity. */
    record ScheduleActivity(long commandId, String name, String inputJson, ActivityOptions options)
            implements Command {

        public ScheduleActivity {
            Objects.requireNonNull(options, "options");
        }

        /** An activity with the default options: no retries, no timeout. */
        public ScheduleActivity(long commandId, String name, String inputJson) {
            this(commandId, name, inputJson, ActivityOptions.defaults());
        }
    }

    /** Wait for a while. The wait survives restarts: it is stored in the database. */
    record StartTimer(long commandId, long durationMillis) implements Command {

        public StartTimer {
            if (durationMillis < 0) {
                throw new IllegalArgumentException("durationMillis must not be negative");
            }
        }
    }

    /** The workflow code returned normally. */
    record CompleteWorkflow(String resultJson) implements Command {}

    /** The workflow code threw an exception that it did not handle. */
    record FailWorkflow(String errorJson) implements Command {}
}
