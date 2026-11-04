package io.github.amitreddy14.revenant.core;

/** Something the workflow code asked the engine to do while being replayed. */
public sealed interface Command {

    /** Run an activity. {@code commandId} is 1 for the first activity call, 2 for the second, and so on. */
    record ScheduleActivity(long commandId, String name, String inputJson) implements Command {}

    /** The workflow code returned normally. */
    record CompleteWorkflow(String resultJson) implements Command {}

    /** The workflow code threw an exception that it did not handle. */
    record FailWorkflow(String errorJson) implements Command {}
}
