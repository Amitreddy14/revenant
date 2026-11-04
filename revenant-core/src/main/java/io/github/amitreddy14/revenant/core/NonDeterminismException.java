package io.github.amitreddy14.revenant.core;

/**
 * The workflow code no longer matches its recorded history, for example because the code was
 * changed while workflows were running. The engine refuses to guess: this fails the <em>decision
 * task</em>, not the workflow. Deploy corrected code and the workflow continues.
 */
public class NonDeterminismException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NonDeterminismException(String message) {
        super(message);
    }
}
