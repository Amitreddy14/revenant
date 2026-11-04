package io.github.amitreddy14.revenant.core;

import java.util.Objects;

/**
 * What a worker reports after running an activity.
 *
 * @param succeeded   true if the activity returned a result, false if it failed for good
 * @param payloadJson the result (on success) or the error details (on failure), as a JSON document
 */
public record ActivityResult(boolean succeeded, String payloadJson) {

    public ActivityResult {
        Objects.requireNonNull(payloadJson, "payloadJson");
    }

    public static ActivityResult success(String resultJson) {
        return new ActivityResult(true, resultJson);
    }

    public static ActivityResult failure(String errorJson) {
        return new ActivityResult(false, errorJson);
    }
}
