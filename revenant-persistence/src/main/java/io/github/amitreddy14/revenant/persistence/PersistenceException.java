package io.github.amitreddy14.revenant.persistence;

/** Unexpected database failure (connection lost, SQL error). Wraps the underlying {@link java.sql.SQLException}. */
public class PersistenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
