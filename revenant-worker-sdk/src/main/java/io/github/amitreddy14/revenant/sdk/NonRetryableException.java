package io.github.amitreddy14.revenant.sdk;

/** Thrown by an activity to fail without further retries, for example when a card is declined. */
public class NonRetryableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NonRetryableException(String message) {
        super(message);
    }
}
