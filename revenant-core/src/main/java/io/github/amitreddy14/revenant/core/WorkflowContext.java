package io.github.amitreddy14.revenant.core;

import java.time.Duration;

/** What a workflow may do besides plain computation. */
public interface WorkflowContext {

    /**
     * Calls an activity and waits for its result.
     *
     * <p>If the history already holds the result, it is returned immediately and the activity is
     * not run again. Otherwise the call schedules the activity and pauses the workflow until a
     * worker has run it.
     *
     * @param name      activity name
     * @param inputJson activity input as a JSON document
     * @return the activity result as a JSON document
     * @throws ActivityFailedException if the activity failed for good
     */
    default String callActivity(String name, String inputJson) {
        return callActivity(name, inputJson, ActivityOptions.defaults());
    }

    /**
     * Calls an activity with a retry policy and/or a timeout. Retries happen behind the scenes: the
     * workflow code sees one result, or one {@link ActivityFailedException} after the last attempt.
     *
     * @param options retry policy and per-attempt timeout
     * @see #callActivity(String, String)
     */
    String callActivity(String name, String inputJson, ActivityOptions options);

    /**
     * Waits for the given time. The wait is stored in the database, so it survives restarts and
     * deploys: a workflow that sleeps for a week keeps no thread and no memory in the meantime.
     * Use this instead of {@code Thread.sleep}, which would break replay.
     *
     * @throws IllegalArgumentException if the duration is negative
     */
    void sleep(Duration duration);
}
