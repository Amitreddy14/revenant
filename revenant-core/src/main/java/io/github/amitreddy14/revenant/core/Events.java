package io.github.amitreddy14.revenant.core;

/** Factory methods for the events the engine writes. */
public final class Events {

    private Events() {}

    public static NewEvent workflowStarted(String inputJson) {
        return new NewEvent(EventType.WORKFLOW_STARTED, inputJson);
    }

    public static NewEvent activityScheduled(long commandId, String name, String inputJson) {
        return new NewEvent(EventType.ACTIVITY_SCHEDULED, commandId, name, inputJson);
    }

    public static NewEvent activityCompleted(long commandId, String name, String resultJson) {
        return new NewEvent(EventType.ACTIVITY_COMPLETED, commandId, name, resultJson);
    }

    public static NewEvent activityFailed(long commandId, String name, String errorJson) {
        return new NewEvent(EventType.ACTIVITY_FAILED, commandId, name, errorJson);
    }

    public static NewEvent workflowCompleted(String resultJson) {
        return new NewEvent(EventType.WORKFLOW_COMPLETED, resultJson);
    }

    public static NewEvent workflowFailed(String errorJson) {
        return new NewEvent(EventType.WORKFLOW_FAILED, errorJson);
    }
}
