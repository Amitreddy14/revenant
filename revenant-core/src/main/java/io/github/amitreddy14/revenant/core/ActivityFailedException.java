package io.github.amitreddy14.revenant.core;

/**
 * Thrown inside workflow code when an activity it called has failed for good. The workflow may
 * catch it and carry on (for example to compensate); if it does not, the workflow fails.
 */
public class ActivityFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String activityName;
    private final long commandId;
    private final String errorJson;

    public ActivityFailedException(String activityName, long commandId, String errorJson) {
        super("Activity '" + activityName + "' (command " + commandId + ") failed: " + errorJson);
        this.activityName = activityName;
        this.commandId = commandId;
        this.errorJson = errorJson;
    }

    public String activityName() {
        return activityName;
    }

    public long commandId() {
        return commandId;
    }

    /** The failure details exactly as recorded in the history (a JSON document). */
    public String errorJson() {
        return errorJson;
    }
}
