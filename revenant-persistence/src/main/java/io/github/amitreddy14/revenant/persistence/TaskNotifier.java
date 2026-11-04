package io.github.amitreddy14.revenant.persistence;

import io.github.amitreddy14.revenant.core.TaskKind;

/**
 * Told, after a transaction has committed, that tasks of a kind may now be ready to claim.
 *
 * <p>This is a hint, never a promise: it can be late, duplicated or lost, and workers must still
 * poll the database (ADR-0004, ADR-0012). Implementations should return quickly and must not
 * depend on the notification arriving. The store ignores any exception they throw.
 */
@FunctionalInterface
public interface TaskNotifier {

    /** Tasks of {@code kind} may be ready. */
    void tasksMayBeReady(TaskKind kind);

    /** A notifier that does nothing: workers find tasks only by polling. */
    static TaskNotifier none() {
        return kind -> { };
    }
}
