package io.github.amitreddy14.revenant.persistence;

/** A workflow with the same id has already been started. */
public class WorkflowAlreadyExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WorkflowAlreadyExistsException(String message) {
        super(message);
    }
}
