package io.github.amitreddy14.revenant.core;

import java.time.Instant;
import java.util.Objects;

/**
 * One stored entry in a workflow's history.
 *
 * @param seq         position in the history, starting at 1 with no gaps
 * @param type        event type name, for example {@code WorkflowStarted}
 * @param payloadJson event data as a JSON document
 * @param createdAt   when the event was stored
 */
public record HistoryEvent(long seq, String type, String payloadJson, Instant createdAt) {

    public HistoryEvent {
        if (seq < 1) {
            throw new IllegalArgumentException("seq must be >= 1, was " + seq);
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
