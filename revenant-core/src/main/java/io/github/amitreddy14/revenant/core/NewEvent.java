package io.github.amitreddy14.revenant.core;

import java.util.Objects;

/**
 * An event that has not been stored yet. The store assigns its sequence number on append.
 *
 * @param type        event type name, for example {@code WorkflowStarted}
 * @param payloadJson event data as a JSON document
 */
public record NewEvent(String type, String payloadJson) {

    public NewEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payloadJson");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
    }
}
