package io.github.amitreddy14.revenant.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Sends outbox rows to an {@link EventSink}, oldest first (ADR-0005, ADR-0011).
 *
 * <p>One batch is one transaction: take a database-wide lock so only one relay runs, read the
 * oldest unpublished rows, send them one by one, and mark the ones that were confirmed. The
 * batch stops at the first failure, so events are never sent out of order; the rest wait for the
 * next call.
 *
 * <p>Delivery is <b>at least once</b>. If the process dies after a send was confirmed but before
 * the commit, the event is sent again. Consumers drop duplicates by {@link OutboxEvent#eventId()}.
 */
public final class OutboxRelay {

    private final Transactions transactions;
    private final OutboxRepository outbox = new OutboxRepository();
    private final EventSink sink;

    public OutboxRelay(DataSource dataSource, EventSink sink) {
        this.transactions = new Transactions(dataSource);
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Relays up to {@code maxEvents} events.
     *
     * @return what happened; never throws because the sink failed (see {@link RelayOutcome#failure()})
     * @throws PersistenceException if the database itself fails
     */
    public RelayOutcome relayBatch(int maxEvents) {
        if (maxEvents < 1) {
            throw new IllegalArgumentException("maxEvents must be >= 1, was " + maxEvents);
        }
        return transactions.execute(connection -> {
            if (!outbox.tryLockRelay(connection)) {
                return RelayOutcome.skippedBecauseAnotherRelayRuns();
            }
            List<OutboxEvent> batch = outbox.fetchUnpublished(connection, maxEvents);
            List<Long> sent = new ArrayList<>();
            Exception failure = null;
            for (OutboxEvent event : batch) {
                try {
                    sink.publish(event);
                    sent.add(event.eventId());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    failure = e;
                    break; // keep order: nothing after a failed event may go out before it
                }
            }
            if (!sent.isEmpty()) {
                outbox.markPublished(connection, sent);
            }
            return new RelayOutcome(sent.size(), false, failure);
        });
    }
}
