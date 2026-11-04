package io.github.amitreddy14.revenant.core;

import java.util.List;
import java.util.Objects;

/**
 * A task as a worker receives it from the server.
 *
 * <p>A workflow task carries the workflow type and the whole history, so the worker can replay
 * the workflow code locally (ADR-0009). An activity task carries the activity name and input.
 *
 * @param task         the leased task; {@code taskId} and {@code attempt} identify the lease
 * @param workflowType the workflow's type for workflow tasks, otherwise null
 * @param history      the full history for workflow tasks, otherwise empty
 */
public record PolledTask(ClaimedTask task, String workflowType, List<HistoryEvent> history) {

    public PolledTask {
        Objects.requireNonNull(task, "task");
        history = List.copyOf(history);
    }
}
