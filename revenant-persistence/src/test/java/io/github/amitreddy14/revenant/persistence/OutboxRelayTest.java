package io.github.amitreddy14.revenant.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.Decider;
import io.github.amitreddy14.revenant.core.HistoryEvent;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WorkflowDefinition;
import io.github.amitreddy14.revenant.core.WorkflowId;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The outbox relay against a real database, with a fake sink instead of Kafka. */
class OutboxRelayTest extends PostgresIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private static final WorkflowDefinition TWO_STEPS = (ctx, input) -> {
        String charged = ctx.callActivity("charge", input);
        return ctx.callActivity("ship", charged);
    };

    private final WorkflowStore store = new WorkflowStore(DATA_SOURCE);

    /** A sink that remembers what it was given. */
    private static final class RecordingSink implements EventSink {
        final List<OutboxEvent> received = new ArrayList<>();

        @Override
        public void publish(OutboxEvent event) {
            received.add(event);
        }
    }

    @Test
    void sendsUnpublishedEventsOldestFirstAndMarksThemPublished() throws SQLException {
        WorkflowId first = start();
        WorkflowId second = start();
        RecordingSink sink = new RecordingSink();

        RelayOutcome outcome = new OutboxRelay(DATA_SOURCE, sink).relayBatch(100);

        assertThat(outcome.published()).isEqualTo(2);
        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.failed()).isFalse();
        assertThat(sink.received).extracting(OutboxEvent::workflowId).containsExactly(first, second);
        assertThat(sink.received).extracting(OutboxEvent::eventType).containsOnly("WorkflowStarted");
        assertThat(sink.received.get(0).eventId()).isLessThan(sink.received.get(1).eventId());
        assertThat(count("SELECT count(*) FROM outbox WHERE published_at IS NULL")).isZero();
    }

    @Test
    void eventsOfOneWorkflowArriveInTheOrderTheyHappened() {
        WorkflowId id = start();
        decide(id); // ActivityScheduled
        RecordingSink sink = new RecordingSink();

        new OutboxRelay(DATA_SOURCE, sink).relayBatch(100);

        assertThat(sink.received).extracting(OutboxEvent::eventType)
                .containsExactly("WorkflowStarted", "ActivityScheduled");
        assertThat(sink.received).extracting(OutboxEvent::workflowId).containsOnly(id);
    }

    @Test
    void carriesTheEventPayload() {
        start();
        RecordingSink sink = new RecordingSink();

        new OutboxRelay(DATA_SOURCE, sink).relayBatch(100);

        assertThat(sink.received.get(0).payloadJson()).contains("\"orderId\": 42"); // JSONB normalises spacing
        assertThat(sink.received.get(0).createdAt()).isNotNull();
    }

    @Test
    void anEventIsSentOnlyOnceWhenNothingFails() {
        start();
        RecordingSink sink = new RecordingSink();
        OutboxRelay relay = new OutboxRelay(DATA_SOURCE, sink);

        relay.relayBatch(100);
        RelayOutcome second = relay.relayBatch(100);

        assertThat(second.published()).isZero();
        assertThat(sink.received).hasSize(1);
    }

    @Test
    void respectsTheBatchSize() throws SQLException {
        start();
        start();
        start();
        RecordingSink sink = new RecordingSink();
        OutboxRelay relay = new OutboxRelay(DATA_SOURCE, sink);

        assertThat(relay.relayBatch(2).published()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM outbox WHERE published_at IS NULL")).isEqualTo(1);
        assertThat(relay.relayBatch(2).published()).isEqualTo(1);
        assertThat(sink.received).hasSize(3);
    }

    @Test
    void stopsAtTheFirstFailureAndKeepsTheRestInOrderForNextTime() throws SQLException {
        start();
        start();
        start();
        start();
        List<Long> delivered = new ArrayList<>();
        EventSink failsOnThird = event -> {
            if (delivered.size() == 2) {
                throw new IllegalStateException("kafka is down");
            }
            delivered.add(event.eventId());
        };

        RelayOutcome outcome = new OutboxRelay(DATA_SOURCE, failsOnThird).relayBatch(100);

        assertThat(outcome.published()).isEqualTo(2);
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.failure()).hasMessage("kafka is down");
        assertThat(count("SELECT count(*) FROM outbox WHERE published_at IS NULL")).isEqualTo(2);

        RecordingSink recovered = new RecordingSink();
        RelayOutcome retry = new OutboxRelay(DATA_SOURCE, recovered).relayBatch(100);

        assertThat(retry.published()).isEqualTo(2);
        assertThat(recovered.received).extracting(OutboxEvent::eventId)
                .doesNotContainAnyElementsOf(delivered)
                .isSorted();
        assertThat(count("SELECT count(*) FROM outbox WHERE published_at IS NULL")).isZero();
    }

    @Test
    void aSinkThatFailsImmediatelyLosesNothing() throws SQLException {
        start();
        start();

        RelayOutcome outcome = new OutboxRelay(DATA_SOURCE, event -> {
            throw new IllegalStateException("down");
        }).relayBatch(100);

        assertThat(outcome.published()).isZero();
        assertThat(outcome.failed()).isTrue();
        assertThat(count("SELECT count(*) FROM outbox WHERE published_at IS NULL")).isEqualTo(2);
    }

    @Test
    void onlyOneRelayRunsAtATime() throws Exception {
        start();
        start();
        CountDownLatch insideSink = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EventSink slow = event -> {
            insideSink.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timed out");
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RelayOutcome> running = executor.submit(() -> new OutboxRelay(DATA_SOURCE, slow).relayBatch(100));
            assertThat(insideSink.await(10, TimeUnit.SECONDS)).isTrue();

            RecordingSink rival = new RecordingSink();
            RelayOutcome skipped = new OutboxRelay(DATA_SOURCE, rival).relayBatch(100);

            assertThat(skipped.skipped()).isTrue();
            assertThat(skipped.published()).isZero();
            assertThat(rival.received).isEmpty();

            release.countDown();
            assertThat(running.get(10, TimeUnit.SECONDS).published()).isEqualTo(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void theLockIsFreeAgainAfterABatch() {
        start();
        OutboxRelay relay = new OutboxRelay(DATA_SOURCE, new RecordingSink());

        assertThat(relay.relayBatch(100).skipped()).isFalse();
        assertThat(relay.relayBatch(100).skipped()).isFalse();
    }

    @Test
    void aBatchSizeBelowOneIsRejected() {
        OutboxRelay relay = new OutboxRelay(DATA_SOURCE, new RecordingSink());

        assertThatThrownBy(() -> relay.relayBatch(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private WorkflowId start() {
        WorkflowId id = WorkflowId.random();
        store.start(id, "TwoSteps", "{\"orderId\":42}");
        return id;
    }

    private void decide(WorkflowId id) {
        ClaimedTask task = store.claimTask(TaskKind.WORKFLOW_TASK, "decider", LEASE).orElseThrow();
        List<HistoryEvent> history = store.history(id);
        store.completeWorkflowTask(task, history.get(history.size() - 1).seq(), Decider.decide(TWO_STEPS, history));
    }
}
