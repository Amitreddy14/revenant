package io.github.amitreddy14.revenant.server;

import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.PolledTask;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.persistence.WorkflowStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * What the HTTP endpoints do, without the HTTP: hand out tasks and look tasks up.
 *
 * <p>A poll waits for work with {@link WakeUpSignals} (ADR-0012) but never longer than
 * {@link #LOOK_EVERY} between two looks at the database, so a missed wake-up costs at most that.
 */
@Component
class WorkerApi {

    static final Duration MAX_WAIT = Duration.ofSeconds(30);
    static final Duration MIN_LEASE = Duration.ofSeconds(1);
    static final Duration MAX_LEASE = Duration.ofMinutes(10);
    static final Duration LOOK_EVERY = Duration.ofSeconds(1);

    private final WorkflowStore store;
    private final WakeUpSignals signals;

    WorkerApi(WorkflowStore store, WakeUpSignals signals) {
        this.store = store;
        this.signals = signals;
    }

    /**
     * Leases the next task of a kind, waiting up to {@code wait} for one to appear.
     *
     * @return the task with what the worker needs to run it, or empty if the wait ran out
     */
    Optional<PolledTask> poll(TaskKind kind, String workerId, Duration lease, Duration wait)
            throws InterruptedException {
        Duration boundedLease = clamp(lease, MIN_LEASE, MAX_LEASE);
        long deadline = System.nanoTime() + clamp(wait, Duration.ZERO, MAX_WAIT).toNanos();
        while (true) {
            Optional<ClaimedTask> claimed = store.claimTask(kind, workerId, boundedLease);
            if (claimed.isPresent()) {
                return Optional.of(describe(claimed.get()));
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return Optional.empty();
            }
            signals.await(kind, Duration.ofNanos(Math.min(remaining, LOOK_EVERY.toNanos())));
        }
    }

    private PolledTask describe(ClaimedTask task) {
        if (task.kind() == TaskKind.ACTIVITY_TASK) {
            return new PolledTask(task, null, List.of());
        }
        String type = store.find(task.workflowId())
                .orElseThrow(() -> new IllegalStateException("task " + task.taskId() + " has no workflow"))
                .type();
        return new PolledTask(task, type, store.history(task.workflowId()));
    }

    static Duration clamp(Duration value, Duration min, Duration max) {
        if (value.compareTo(min) < 0) {
            return min;
        }
        return value.compareTo(max) > 0 ? max : value;
    }
}
