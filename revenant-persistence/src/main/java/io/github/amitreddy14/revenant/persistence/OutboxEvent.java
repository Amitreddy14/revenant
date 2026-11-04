package io.github.amitreddy14.revenant.persistence;

import io.github.amitreddy14.revenant.core.WorkflowId;
import java.time.Instant;
import java.util.Objects;

/**
 * One row of the outbox, waiting to be sent to the outside world.
 *
 * @param eventId     unique and increasing; consumers use it to drop duplicates
 * @param workflowId  the workflow the event belongs to; the message key, so one workflow's events stay in order
 * @param eventType   the history event type, for example {@code ActivityCompleted}
 * @param payloadJson the event data as a JSON document
 * @param createdAt   when the event was recorded
 */
public record OutboxEvent(
        long eventId, WorkflowId workflowId, String eventType, String payloadJson, Instant createdAt) {

    public OutboxEvent {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
