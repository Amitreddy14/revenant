package io.github.amitreddy14.revenant.sdk;

/** The server answered with an error status that the caller has to deal with. */
public class RevenantApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public RevenantApiException(int status, String body) {
        super("Revenant server answered " + status + ": " + body);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
