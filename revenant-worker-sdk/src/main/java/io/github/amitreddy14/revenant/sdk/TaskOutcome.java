package io.github.amitreddy14.revenant.sdk;

/** What the server did with a result a worker reported. Only {@link #LEASE_LOST} means it was refused. */
public enum TaskOutcome {
    /** Recorded. */
    APPLIED,
    /** The history moved on while the worker was deciding; a fresher decision task exists. */
    STALE_DISCARDED,
    /** The failure will be tried again after its backoff. */
    RETRY_SCHEDULED,
    /** The worker no longer holds the task (its lease ran out and the task was handed on). */
    LEASE_LOST
}
