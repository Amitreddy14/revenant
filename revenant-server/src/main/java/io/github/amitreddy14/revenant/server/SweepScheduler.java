package io.github.amitreddy14.revenant.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@link SweepJob} on a fixed delay. Disable with {@code revenant.scheduler.enabled=false}. */
@Component
@ConditionalOnProperty(name = "revenant.scheduler.enabled", havingValue = "true", matchIfMissing = true)
class SweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(SweepScheduler.class);

    private final SweepJob job;

    SweepScheduler(SweepJob job) {
        this.job = job;
    }

    @Scheduled(fixedDelayString = "${revenant.scheduler.interval:PT1S}")
    void tick() {
        try {
            SweepJob.Result result = job.runOnce();
            if (result.timersFired() > 0 || result.attemptsTimedOut() > 0) {
                log.info("sweep: fired {} timer(s), timed out {} attempt(s)", result.timersFired(),
                        result.attemptsTimedOut());
            }
        } catch (RuntimeException e) {
            // Database trouble must not stop the schedule: the next tick tries again.
            log.warn("sweep failed, will retry on the next tick: {}", e.toString());
        }
    }
}
