package io.github.amitreddy14.revenant.server;

import io.github.amitreddy14.revenant.persistence.OutboxRelay;
import io.github.amitreddy14.revenant.persistence.RelayOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the outbox relay on a fixed delay, draining the backlog while batches come back full. */
@Component
@ConditionalOnProperty(name = "revenant.kafka.enabled", havingValue = "true", matchIfMissing = true)
class RelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(RelayScheduler.class);

    private final OutboxRelay relay;
    private final int batchSize;

    RelayScheduler(OutboxRelay relay, @Value("${revenant.relay.batch-size:100}") int batchSize) {
        this.relay = relay;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${revenant.relay.interval:PT0.5S}")
    void tick() {
        try {
            RelayOutcome outcome;
            do {
                outcome = relay.relayBatch(batchSize);
                if (outcome.published() > 0) {
                    log.debug("relayed {} event(s) to Kafka", outcome.published());
                }
            } while (!outcome.skipped() && !outcome.failed() && outcome.published() == batchSize);
            if (outcome.failed()) {
                log.warn("relay stopped, events stay in the outbox and are retried: {}",
                        outcome.failure().toString());
            }
        } catch (RuntimeException e) {
            log.warn("relay failed, will retry on the next tick: {}", e.toString());
        }
    }
}
