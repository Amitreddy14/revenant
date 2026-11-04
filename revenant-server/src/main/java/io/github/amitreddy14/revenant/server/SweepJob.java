package io.github.amitreddy14.revenant.server;

import io.github.amitreddy14.revenant.persistence.WorkflowStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One pass of the periodic housekeeping the engine needs: fire timers that are due and fail
 * activity attempts that outlived their start-to-close timeout (ADR-0010). Both operations are
 * safe to run from every server instance at once.
 */
@Component
class SweepJob {

    /** What one pass did. */
    record Result(int timersFired, int attemptsTimedOut) {}

    private final WorkflowStore store;
    private final int batchSize;

    SweepJob(WorkflowStore store, @Value("${revenant.scheduler.batch-size:100}") int batchSize) {
        this.store = store;
        this.batchSize = batchSize;
    }

    Result runOnce() {
        int fired = store.fireDueTimers(batchSize);
        int timedOut = store.timeOutExpiredActivities(batchSize);
        return new Result(fired, timedOut);
    }
}
