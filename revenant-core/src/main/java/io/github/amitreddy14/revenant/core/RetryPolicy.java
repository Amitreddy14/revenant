package io.github.amitreddy14.revenant.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * How often, and how patiently, a failed activity is tried again.
 *
 * <p>The wait before retry <em>n</em> is {@code initialInterval * backoffCoefficient^(n-1)}, never
 * more than {@code maxInterval}. With the defaults of {@link #exponential}, an initial interval of
 * 1 second gives waits of 1s, 2s, 4s, 8s, ...
 *
 * @param maxAttempts        total tries including the first one; 1 means "never retry"
 * @param initialInterval    wait before the first retry (zero retries immediately)
 * @param backoffCoefficient growth factor between waits; 1.0 keeps the wait constant
 * @param maxInterval        upper limit for any single wait
 */
public record RetryPolicy(
        int maxAttempts, Duration initialInterval, double backoffCoefficient, Duration maxInterval) {

    public RetryPolicy {
        Objects.requireNonNull(initialInterval, "initialInterval");
        Objects.requireNonNull(maxInterval, "maxInterval");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, was " + maxAttempts);
        }
        if (initialInterval.isNegative()) {
            throw new IllegalArgumentException("initialInterval must not be negative");
        }
        if (!(backoffCoefficient >= 1.0) || Double.isInfinite(backoffCoefficient)) {
            throw new IllegalArgumentException("backoffCoefficient must be a finite number >= 1.0, was "
                    + backoffCoefficient);
        }
        if (maxInterval.compareTo(initialInterval) < 0) {
            throw new IllegalArgumentException("maxInterval must not be shorter than initialInterval");
        }
    }

    /** One attempt, no retries. This is what an activity gets unless the workflow asks for more. */
    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(1));
    }

    /** Doubling waits starting at {@code initialInterval}, capped at 100 times that. */
    public static RetryPolicy exponential(int maxAttempts, Duration initialInterval) {
        return new RetryPolicy(maxAttempts, initialInterval, 2.0, initialInterval.multipliedBy(100));
    }

    public RetryPolicy withBackoffCoefficient(double coefficient) {
        return new RetryPolicy(maxAttempts, initialInterval, coefficient, maxInterval);
    }

    public RetryPolicy withMaxInterval(Duration interval) {
        return new RetryPolicy(maxAttempts, initialInterval, backoffCoefficient, interval);
    }

    /**
     * Decides what happens after an attempt failed.
     *
     * @param failedAttempts how many attempts have failed so far, including the one that just
     *                       failed (1 after the first failure)
     * @return how long to wait before the next attempt, or empty if the activity has used up its
     *         attempts and must be reported as failed
     */
    public Optional<Duration> delayAfterFailure(int failedAttempts) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("failedAttempts must be >= 1, was " + failedAttempts);
        }
        if (failedAttempts >= maxAttempts) {
            return Optional.empty();
        }
        double millis = initialInterval.toMillis() * Math.pow(backoffCoefficient, failedAttempts - 1);
        double capped = Math.min(millis, maxInterval.toMillis());
        return Optional.of(Duration.ofMillis((long) capped));
    }
}
