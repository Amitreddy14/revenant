package io.github.amitreddy14.revenant.core;

import java.util.Objects;

/**
 * A task a worker has claimed under a lease.
 *
 * @param taskId      queue identifier
 * @param workflowId  the workflow the task belongs to
 * @param kind        what to do
 * @param payloadJson task data as a JSON document
 * @param attempt     1 on the first claim, +1 every time the task is claimed again. Pass it back
 *                    when completing: it fences out a worker whose lease already expired.
 */
public record ClaimedTask(long taskId, WorkflowId workflowId, TaskKind kind, String payloadJson, int attempt) {

    public ClaimedTask {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payloadJson, "payloadJson");
    }
}
