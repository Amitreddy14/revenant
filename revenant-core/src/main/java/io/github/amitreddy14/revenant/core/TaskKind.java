package io.github.amitreddy14.revenant.core;

/** The two kinds of work a worker can be handed. */
public enum TaskKind {
    /** "Your workflow has new events, decide what to do next." */
    WORKFLOW_TASK,
    /** "Run this activity." */
    ACTIVITY_TASK
}
