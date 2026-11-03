package io.github.amitreddy14.revenant.core;

import java.time.Instant;
import java.util.Objects;

/**
 * Summary row for one workflow execution.
 *
 * @param workflowId the workflow's identifier
 * @param type       workflow type name, which selects the code that runs it
 * @param status     lifecycle state
 * @param lastSeq    sequence number of the newest history event (0 if none yet)
 * @param createdAt  when the workflow was created
 */
public record WorkflowExecution(
        WorkflowId workflowId, String type, WorkflowStatus status, long lastSeq, Instant createdAt) {

    public WorkflowExecution {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
