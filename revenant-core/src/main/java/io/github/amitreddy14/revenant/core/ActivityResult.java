package io.github.amitreddy14.revenant.core;

import java.util.Objects;

/**
 * What a worker reports after running an activity.
 *
 * @param succeeded   true if the activity returned a result, false if this attempt failed
 * @param payloadJson the result (on success) or the error details (on failure), as a JSON document
 * @param retryable   for a failure: whether the retry policy may try again. {@code false} for
 *                    errors that can never succeed on retry (bad input, for example). Always
 *                    {@code false} for a success.
 */
public record ActivityResult(boolean succeeded, String payloadJson, boolean retryable) {

    public ActivityResult {
        Objects.requireNonNull(payloadJson, "payloadJson");
        retryable = !succeeded && retryable;
    }

    /** A success, or a retryable failure. */
    public ActivityResult(boolean succeeded, String payloadJson) {
        this(succeeded, payloadJson, !succeeded);
    }

    public static ActivityResult success(String resultJson) {
        return new ActivityResult(true, resultJson, false);
    }

    /** This attempt failed; the retry policy decides whether to try again. */
    public static ActivityResult failure(String errorJson) {
        return new ActivityResult(false, errorJson, true);
    }

    /** This attempt failed and trying again cannot help: the activity is failed at once. */
    public static ActivityResult nonRetryableFailure(String errorJson) {
        return new ActivityResult(false, errorJson, false);
    }
}
