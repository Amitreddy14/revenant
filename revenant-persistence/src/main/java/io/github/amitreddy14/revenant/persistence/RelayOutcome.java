package io.github.amitreddy14.revenant.persistence;

/**
 * What one {@link OutboxRelay#relayBatch} call did.
 *
 * @param published how many events were sent and marked as published
 * @param skipped   true if another relay was already running, so nothing was attempted
 * @param failure   why the batch stopped early, or {@code null}; the unsent events stay in the outbox
 */
public record RelayOutcome(int published, boolean skipped, Exception failure) {

    static RelayOutcome skippedBecauseAnotherRelayRuns() {
        return new RelayOutcome(0, true, null);
    }

    public boolean failed() {
        return failure != null;
    }
}
