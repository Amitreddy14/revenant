package io.github.amitreddy14.revenant.core;

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
    String callActivity(String name, String inputJson);
}
