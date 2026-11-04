package io.github.amitreddy14.revenant.core;

import java.util.Objects;

/**
 * An event that has not been stored yet. The store assigns its sequence number on append.
 *
 * @param type        event type name, see {@link EventType}
 * @param commandId   which command (activity call) this event belongs to, or {@code null} for
 *                    events that concern the whole workflow. Command ids are 1, 2, 3, ... in the
 *                    order the workflow code issues them.
 * @param name        activity name for command events, otherwise {@code null}
 * @param payloadJson event data as a JSON document. The engine never parses it.
 */
public record NewEvent(String type, Long commandId, String name, String payloadJson) {

    public NewEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payloadJson");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (commandId != null && commandId < 1) {
            throw new IllegalArgumentException("commandId must be >= 1, was " + commandId);
        }
    }

    /** An event that concerns the whole workflow rather than one command. */
    public NewEvent(String type, String payloadJson) {
        this(type, null, null, payloadJson);
    }
}
