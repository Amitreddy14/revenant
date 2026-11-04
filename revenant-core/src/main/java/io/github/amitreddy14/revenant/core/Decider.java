package io.github.amitreddy14.revenant.core;

import java.util.List;
import java.util.Objects;

/**
 * The replay engine. Given a workflow's code and its stored history, decides what must happen
 * next. It is a pure function: no database, no clock, no threads. The same history always
 * produces the same decision, which is what makes crash recovery safe.
 *
 * <pre>{@code
 * List<HistoryEvent> history = store.history(id);
 * Decision decision = Decider.decide(definition, history);
 * // write decision.events() and enqueue decision.activitiesToSchedule() in one transaction
 * }</pre>
 */
public final class Decider {

    private Decider() {}

    /**
     * Replays {@code definition} against {@code history}.
     *
     * @return the new commands, or {@link Decision#none()} if the workflow is waiting or already closed
     * @throws NonDeterminismException if the code no longer matches the history
     * @throws InvalidHistoryException if the history itself is malformed
     */
    public static Decision decide(WorkflowDefinition definition, List<HistoryEvent> history) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(history, "history");

        HistoryView view = HistoryView.of(history);
        if (view.isClosed()) {
            return Decision.none();
        }

        ReplayContext context = new ReplayContext(view);
        Decision decision;
        try {
            String result = definition.execute(context, view.startInput());
            decision = new Decision(List.of(new Command.CompleteWorkflow(result == null ? "null" : result)));
        } catch (WorkflowSuspendedError suspended) {
            decision = new Decision(context.newCommands());
        } catch (Exception e) {
            decision = new Decision(List.of(new Command.FailWorkflow(failureJson(e))));
        }

        context.throwIfViolated();
        context.verifyHistoryFullyConsumed();
        return decision;
    }

    private static String failureJson(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return "{\"type\":" + JsonText.quote(e.getClass().getName()) + ",\"message\":" + JsonText.quote(message) + "}";
    }
}
