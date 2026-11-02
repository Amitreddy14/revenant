package io.github.amitreddy14.revenant.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Globally unique identifier of one workflow execution.
 *
 * <p>Wrapping the {@link UUID} in its own type stops a workflow id from being mixed up
 * with other identifiers (activity ids, task ids) at compile time.
 */
public record WorkflowId(UUID value) {

    public WorkflowId {
        Objects.requireNonNull(value, "value");
    }

    /** Creates a new random identifier. */
    public static WorkflowId random() {
        return new WorkflowId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form produced by {@link #toString()}.
     *
     * @throws IllegalArgumentException if the text is not a valid UUID
     */
    public static WorkflowId parse(String text) {
        return new WorkflowId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
