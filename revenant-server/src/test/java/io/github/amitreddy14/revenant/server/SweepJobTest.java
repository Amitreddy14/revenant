package io.github.amitreddy14.revenant.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.Decider;
import io.github.amitreddy14.revenant.core.HistoryEvent;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WorkflowDefinition;
import io.github.amitreddy14.revenant.core.WorkflowId;
import io.github.amitreddy14.revenant.persistence.WorkflowStore;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The scheduler's job, run by hand so the test does not depend on timing: a due timer fires and
 * wakes its workflow. The scheduler itself and Kafka are switched off here.
 */
@Testcontainers
@SpringBootTest(properties = {"revenant.kafka.enabled=false", "revenant.scheduler.enabled=false",
        "revenant.redis.enabled=false"})
class SweepJobTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private static final WorkflowDefinition SLEEPS_THEN_FINISHES = (ctx, input) -> {
        ctx.sleep(Duration.ZERO);
        return "\"done\"";
    };

    @Autowired
    WorkflowStore store;

    @Autowired
    SweepJob job;

    @Test
    void aPassWithNothingDueDoesNothing() {
        SweepJob.Result result = job.runOnce();

        assertThat(result).isEqualTo(new SweepJob.Result(0, 0));
    }

    @Test
    void aDueTimerFiresAndTheWorkflowGetsAWorkflowTaskToContinue() {
        WorkflowId id = WorkflowId.random();
        store.start(id, "SleepsThenFinishes", "{}");
        ClaimedTask task = store.claimTask(TaskKind.WORKFLOW_TASK, "decider", Duration.ofSeconds(30)).orElseThrow();
        List<HistoryEvent> history = store.history(id);
        store.completeWorkflowTask(
                task, history.get(history.size() - 1).seq(), Decider.decide(SLEEPS_THEN_FINISHES, history));

        SweepJob.Result result = job.runOnce();

        assertThat(result.timersFired()).isEqualTo(1);
        assertThat(store.history(id).stream().map(HistoryEvent::type).toList())
                .containsExactly("WorkflowStarted", "TimerStarted", "TimerFired");
        assertThat(store.claimTask(TaskKind.WORKFLOW_TASK, "decider", Duration.ofSeconds(30))).isPresent();
    }
}
