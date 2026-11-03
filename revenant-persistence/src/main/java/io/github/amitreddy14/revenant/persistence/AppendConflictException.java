package io.github.amitreddy14.revenant.persistence;

/**
 * A history append was rejected: another writer got there first (stale expected sequence),
 * or the workflow is closed or does not exist. The caller should re-read and decide again.
 */
public class AppendConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AppendConflictException(String message) {
        super(message);
    }
}
