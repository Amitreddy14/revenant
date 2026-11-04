package io.github.amitreddy14.revenant.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Per-call settings for an activity.
 *
 * @param retryPolicy         what to do when the activity fails
 * @param startToCloseTimeout how long one attempt may run before it counts as failed, or
 *                            {@code null} for no limit. The attempt is declared dead even if the
 *                            worker is still running; the worker's late result is then ignored.
 */
public record ActivityOptions(RetryPolicy retryPolicy, Duration startToCloseTimeout) {

    public ActivityOptions {
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        if (startToCloseTimeout != null && (startToCloseTimeout.isZero() || startToCloseTimeout.isNegative())) {
            throw new IllegalArgumentException("startToCloseTimeout must be positive, was " + startToCloseTimeout);
        }
    }

    /** No retries and no timeout: the behaviour of a plain {@code callActivity(name, input)}. */
    public static ActivityOptions defaults() {
        return new ActivityOptions(RetryPolicy.none(), null);
    }

    public static ActivityOptions retrying(RetryPolicy policy) {
        return new ActivityOptions(policy, null);
    }

    public ActivityOptions withRetryPolicy(RetryPolicy policy) {
        return new ActivityOptions(policy, startToCloseTimeout);
    }

    public ActivityOptions withStartToCloseTimeout(Duration timeout) {
        return new ActivityOptions(retryPolicy, timeout);
    }
}
