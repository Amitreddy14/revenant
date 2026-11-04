package io.github.amitreddy14.revenant.core;

/**
 * Thrown by the engine to pause workflow code at a step that has no result yet. It is an
 * {@link Error}, not an exception, so ordinary {@code catch (Exception e)} blocks in workflow
 * code do not swallow it. It carries no stack trace because it is control flow, not a failure.
 */
final class WorkflowSuspendedError extends Error {

    private static final long serialVersionUID = 1L;

    WorkflowSuspendedError() {
        super("workflow suspended", null, false, false);
    }
}
