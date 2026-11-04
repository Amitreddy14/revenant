package io.github.amitreddy14.revenant.persistence;

/**
 * Where relayed outbox events go (Kafka in the server). An implementation must not return until
 * the event is safely accepted, and must throw if it was not: the relay only marks an event as
 * published after {@link #publish} returned normally.
 */
@FunctionalInterface
public interface EventSink {

    /**
     * Sends one event and waits for the destination to confirm it.
     *
     * @throws Exception if the event was not delivered; it stays in the outbox and is tried again
     */
    void publish(OutboxEvent event) throws Exception;
}
