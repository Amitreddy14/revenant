package io.github.amitreddy14.revenant.server;

import io.github.amitreddy14.revenant.core.TaskKind;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * An in-memory "something may be ready" flag per kind of task, so a poller can sleep until it is
 * told to look instead of looking on a fixed timer.
 *
 * <p>A signal that arrives before anyone waits is remembered (one flag, not a count: ten signals
 * mean "look once"). The wait always has a timeout, so a poller that is never signalled still
 * looks at the database on its normal schedule. That timeout is the guarantee; the signal only
 * makes the common case faster (ADR-0012).
 */
final class WakeUpSignals {

    private final Map<TaskKind, Semaphore> flags = new EnumMap<>(TaskKind.class);

    WakeUpSignals() {
        for (TaskKind kind : TaskKind.values()) {
            flags.put(kind, new Semaphore(0));
        }
    }

    /** Remember that tasks of this kind may be ready; wakes one waiting poller. */
    void signal(TaskKind kind) {
        Semaphore flag = flags.get(kind);
        if (flag.availablePermits() == 0) {
            flag.release();
        }
    }

    /**
     * Waits until a signal for this kind has arrived or the time is up.
     *
     * @return true if woken by a signal, false if the wait simply timed out
     */
    boolean await(TaskKind kind, Duration maxWait) throws InterruptedException {
        Semaphore flag = flags.get(kind);
        boolean woken = flag.tryAcquire(maxWait.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        flag.drainPermits();
        return woken;
    }
}
