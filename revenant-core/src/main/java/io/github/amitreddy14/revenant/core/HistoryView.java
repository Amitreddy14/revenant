package io.github.amitreddy14.revenant.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A validated, indexed view of one workflow's history.
 *
 * <p>Validation happens up front so the replay logic can trust what it reads: a history that
 * breaks these rules is corrupt, and replaying it would only produce confusing errors later.
 */
final class HistoryView {

    private final String startInput;
    private final Map<Long, HistoryEvent> scheduled = new HashMap<>();
    private final Map<Long, HistoryEvent> finished = new HashMap<>();
    private long lastScheduledCommandId;
    private boolean closed;

    private HistoryView(String startInput) {
        this.startInput = startInput;
    }

    static HistoryView of(List<HistoryEvent> history) {
        if (history.isEmpty()) {
            throw new InvalidHistoryException("history is empty");
        }
        HistoryEvent first = history.get(0);
        if (!EventType.WORKFLOW_STARTED.equals(first.type())) {
            throw new InvalidHistoryException("history must start with " + EventType.WORKFLOW_STARTED
                    + " but starts with " + first.type());
        }
        HistoryView view = new HistoryView(first.payloadJson());

        for (int i = 0; i < history.size(); i++) {
            HistoryEvent event = history.get(i);
            long expectedSeq = i + 1L;
            if (event.seq() != expectedSeq) {
                throw new InvalidHistoryException(
                        "expected sequence number " + expectedSeq + " but found " + event.seq());
            }
            if (view.closed) {
                throw new InvalidHistoryException("event " + event.seq() + " (" + event.type()
                        + ") comes after the workflow was closed");
            }
            if (i > 0) {
                view.accept(event);
            }
        }
        return view;
    }

    private void accept(HistoryEvent event) {
        switch (event.type()) {
            case EventType.ACTIVITY_SCHEDULED -> {
                long id = requireCommand(event);
                if (event.name() == null) {
                    throw new InvalidHistoryException("event " + event.seq() + " has no activity name");
                }
                if (id != lastScheduledCommandId + 1) {
                    throw new InvalidHistoryException("event " + event.seq() + " schedules command " + id
                            + " but command " + (lastScheduledCommandId + 1) + " was expected");
                }
                lastScheduledCommandId = id;
                scheduled.put(id, event);
            }
            case EventType.ACTIVITY_COMPLETED, EventType.ACTIVITY_FAILED -> {
                long id = requireCommand(event);
                if (!scheduled.containsKey(id)) {
                    throw new InvalidHistoryException(
                            "event " + event.seq() + " answers command " + id + " which was never scheduled");
                }
                if (finished.put(id, event) != null) {
                    throw new InvalidHistoryException(
                            "command " + id + " has more than one result (event " + event.seq() + ")");
                }
            }
            case EventType.WORKFLOW_COMPLETED, EventType.WORKFLOW_FAILED -> closed = true;
            default -> throw new InvalidHistoryException(
                    "event " + event.seq() + " has unexpected type " + event.type());
        }
    }

    private static long requireCommand(HistoryEvent event) {
        if (event.commandId() == null) {
            throw new InvalidHistoryException("event " + event.seq() + " (" + event.type() + ") has no command id");
        }
        return event.commandId();
    }

    String startInput() {
        return startInput;
    }

    boolean isClosed() {
        return closed;
    }

    /** The ActivityScheduled event for a command, or null if the workflow has not issued it yet. */
    HistoryEvent scheduled(long commandId) {
        return scheduled.get(commandId);
    }

    /** The ActivityCompleted or ActivityFailed event for a command, or null if it is still running. */
    HistoryEvent finished(long commandId) {
        return finished.get(commandId);
    }

    long lastScheduledCommandId() {
        return lastScheduledCommandId;
    }
}
