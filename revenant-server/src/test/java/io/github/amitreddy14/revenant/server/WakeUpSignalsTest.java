package io.github.amitreddy14.revenant.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amitreddy14.revenant.core.TaskKind;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WakeUpSignalsTest {

    private final WakeUpSignals signals = new WakeUpSignals();

    @Test
    void aWaitWithoutAnySignalTimesOut() throws InterruptedException {
        assertThat(signals.await(TaskKind.WORKFLOW_TASK, Duration.ofMillis(50))).isFalse();
    }

    @Test
    void aSignalSentBeforeTheWaitIsNotLost() throws InterruptedException {
        signals.signal(TaskKind.WORKFLOW_TASK);

        assertThat(signals.await(TaskKind.WORKFLOW_TASK, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void manySignalsMeanOneLook() throws InterruptedException {
        signals.signal(TaskKind.WORKFLOW_TASK);
        signals.signal(TaskKind.WORKFLOW_TASK);
        signals.signal(TaskKind.WORKFLOW_TASK);

        assertThat(signals.await(TaskKind.WORKFLOW_TASK, Duration.ofSeconds(5))).isTrue();
        assertThat(signals.await(TaskKind.WORKFLOW_TASK, Duration.ofMillis(50))).isFalse();
    }

    @Test
    void kindsDoNotWakeEachOther() throws InterruptedException {
        signals.signal(TaskKind.ACTIVITY_TASK);

        assertThat(signals.await(TaskKind.WORKFLOW_TASK, Duration.ofMillis(50))).isFalse();
        assertThat(signals.await(TaskKind.ACTIVITY_TASK, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void aWaitingThreadIsWokenByASignalFromAnotherThread() throws InterruptedException {
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean woken = new AtomicBoolean();
        Thread poller = new Thread(() -> {
            try {
                waiting.countDown();
                woken.set(signals.await(TaskKind.ACTIVITY_TASK, Duration.ofSeconds(30)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        poller.start();
        waiting.await();

        signals.signal(TaskKind.ACTIVITY_TASK);

        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(woken).isTrue();
    }
}
