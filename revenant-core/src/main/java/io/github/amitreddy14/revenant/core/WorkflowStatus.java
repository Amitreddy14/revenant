package io.github.amitreddy14.revenant.core;

/** Lifecycle state of a workflow execution. Once it leaves {@link #RUNNING} it never returns. */
public enum WorkflowStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    public boolean isClosed() {
        return this != RUNNING;
    }
}
