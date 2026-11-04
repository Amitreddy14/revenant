package io.github.amitreddy14.revenant.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Builds histories for tests, numbering the events 1, 2, 3, ... like the store does. */
final class TestHistories {

    private TestHistories() {}

    static List<HistoryEvent> of(NewEvent... events) {
        List<HistoryEvent> history = new ArrayList<>();
        long seq = 1;
        for (NewEvent event : events) {
            history.add(new HistoryEvent(
                    seq++, event.type(), event.commandId(), event.name(), event.payloadJson(), Instant.EPOCH));
        }
        return history;
    }

    /** Same as {@link #of} but lets a test choose the sequence numbers, to build broken histories. */
    static HistoryEvent at(long seq, NewEvent event) {
        return new HistoryEvent(seq, event.type(), event.commandId(), event.name(), event.payloadJson(), Instant.EPOCH);
    }

    static List<String> types(List<NewEvent> events) {
        return events.stream().map(NewEvent::type).toList();
    }
}
