package io.github.amitreddy14.revenant.core;

/** The stored history is malformed (gaps, duplicates, results without a request). This is a bug or corruption. */
public class InvalidHistoryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidHistoryException(String message) {
        super(message);
    }
}
