package io.github.amitreddy14.revenant.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amitreddy14.revenant.core.TaskKind;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Redis carries hints between instances, and its absence changes nothing but speed. */
@Testcontainers
class RedisWakeUpsTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static String url() {
        return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
    }

    @Test
    void aHintFromOneInstanceWakesAPollerOnAnother() throws Exception {
        WakeUpSignals signalsA = new WakeUpSignals();
        WakeUpSignals signalsB = new WakeUpSignals();
        try (RedisWakeUps a = new RedisWakeUps(url(), signalsA); RedisWakeUps b = new RedisWakeUps(url(), signalsB)) {
            a.start();
            b.start();

            // The instances connect in the background, so keep sending until B hears one.
            boolean heard = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (!heard && System.nanoTime() < deadline) {
                a.tasksMayBeReady(TaskKind.ACTIVITY_TASK);
                heard = signalsB.await(TaskKind.ACTIVITY_TASK, Duration.ofMillis(300));
            }

            assertThat(heard).as("instance B was woken through Redis").isTrue();
            assertThat(signalsB.await(TaskKind.WORKFLOW_TASK, Duration.ofMillis(50)))
                    .as("a different kind of task is not woken").isFalse();
        }
    }

    @Test
    void whenRedisIsUnreachableNothingFailsAndTheLocalPollerIsStillWoken() throws Exception {
        WakeUpSignals signals = new WakeUpSignals();
        try (RedisWakeUps wakeUps = new RedisWakeUps("redis://127.0.0.1:1", signals)) {
            wakeUps.start();

            wakeUps.tasksMayBeReady(TaskKind.WORKFLOW_TASK);

            assertThat(wakeUps.connected()).isFalse();
            assertThat(signals.await(TaskKind.WORKFLOW_TASK, Duration.ofSeconds(5))).isTrue();
        }
    }
}
