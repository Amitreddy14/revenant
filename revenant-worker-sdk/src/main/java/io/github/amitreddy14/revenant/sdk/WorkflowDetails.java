package io.github.amitreddy14.revenant.sdk;

import io.github.amitreddy14.revenant.core.HistoryEvent;
import io.github.amitreddy14.revenant.core.WorkflowId;
import java.util.List;
import java.util.Optional;

/**
 * A workflow as the server reports it.
 *
 * @param status one of RUNNING, COMPLETED, FAILED, CANCELLED, TIMED_OUT
 */
public record WorkflowDetails(WorkflowId workflowId, String type, String status, List<HistoryEvent> history) {

    public WorkflowDetails {
        history = List.copyOf(history);
    }

    public boolean isRunning() {
        return status.equals("RUNNING");
    }

    /** The JSON the workflow returned, once it has completed. */
    public Optional<String> resultJson() {
        return history.stream()
                .filter(event -> event.type().equals("WorkflowCompleted"))
                .map(HistoryEvent::payloadJson)
                .findFirst();
    }
}
