package io.github.amitreddy14.revenant.core;

/** Names of the event types stored in a workflow's history. */
public final class EventType {

    public static final String WORKFLOW_STARTED = "WorkflowStarted";
    public static final String ACTIVITY_SCHEDULED = "ActivityScheduled";
    public static final String ACTIVITY_COMPLETED = "ActivityCompleted";
    public static final String ACTIVITY_FAILED = "ActivityFailed";
    public static final String WORKFLOW_COMPLETED = "WorkflowCompleted";
    public static final String WORKFLOW_FAILED = "WorkflowFailed";

    private EventType() {}

    /** True for the two events that end a workflow. */
    public static boolean isTerminal(String type) {
        return WORKFLOW_COMPLETED.equals(type) || WORKFLOW_FAILED.equals(type);
    }
}
