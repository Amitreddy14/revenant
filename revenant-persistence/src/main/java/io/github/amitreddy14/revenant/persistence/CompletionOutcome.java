package io.github.amitreddy14.revenant.persistence;

/** What happened when a worker reported the result of a task. */
public enum CompletionOutcome {

    /** The result was recorded. */
    APPLIED,

    /**
     * The result was no longer needed: the workflow moved on or closed while the worker was busy.
     * The task is removed and nothing else changes. A newer workflow task already exists to make
     * the next decision, so no work is lost.
     */
    STALE_DISCARDED,

    /**
     * The worker's lease had expired and the task was handed to someone else. Nothing was
     * written; the worker should drop its result.
     */
    LEASE_LOST
}
