package io.github.amitreddy14.revenant.core;

/**
 * The code of one workflow type.
 *
 * <p>The engine calls {@link #execute} again from the top every time it needs to decide what
 * happens next, so the method must be <b>deterministic</b>: given the same input and the same
 * activity results it must make the same calls in the same order. Concretely, inside a workflow
 * do not read the clock, generate random numbers, use mutable global state, start threads, or talk
 * to the outside world. Do all of that inside activities, via {@link WorkflowContext#callActivity}.
 *
 * <p>Do not catch {@link Throwable} (or {@link Error}) around activity calls: the engine uses an
 * error to pause the workflow, and swallowing it breaks replay.
 */
@FunctionalInterface
public interface WorkflowDefinition {

    /**
     * Runs the workflow.
     *
     * @param context   the only way to cause side effects
     * @param inputJson the input given when the workflow was started (a JSON document)
     * @return the result as a JSON document ({@code null} is recorded as the JSON value {@code null})
     * @throws Exception any exception ends the workflow as failed
     */
    String execute(WorkflowContext context, String inputJson) throws Exception;
}
