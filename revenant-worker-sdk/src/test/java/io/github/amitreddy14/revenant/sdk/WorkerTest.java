package io.github.amitreddy14.revenant.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.Command;
import io.github.amitreddy14.revenant.core.HistoryEvent;
import io.github.amitreddy14.revenant.core.JsonNode;
import io.github.amitreddy14.revenant.core.PolledTask;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WireFormat;
import io.github.amitreddy14.revenant.core.WorkflowDefinition;
import io.github.amitreddy14.revenant.core.WorkflowId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** The worker's behaviour, against a scripted server. */
class WorkerTest {

    private static final WorkflowId ID = WorkflowId.parse("0d6a3f0e-1c7e-4a52-9a52-2d3d6c1f6b11");
    private static final Instant T = Instant.parse("2026-11-05T10:15:30Z");
    private static final WorkflowDefinition CHARGE = (ctx, input) -> ctx.callActivity("charge", input);

    private static String workflowTask(List<HistoryEvent> history) {
        return WireFormat.encodePolledTask(new PolledTask(
                new ClaimedTask(5, ID, TaskKind.WORKFLOW_TASK, null, null, "{}", 2), "Order", history));
    }

    private static String activityTask(String name, String input) {
        return WireFormat.encodePolledTask(new PolledTask(
                new ClaimedTask(9, ID, TaskKind.ACTIVITY_TASK, 1L, name, input, 4), null, List.of()));
    }

    private static Worker.Builder worker(FakeServer server) {
        return Worker.builder(new RevenantClient(server.url()))
                .workerId("test-worker")
                .workflowThreads(1)
                .activityThreads(1)
                .pollWait(Duration.ofMillis(100))
                .errorBackoff(Duration.ofMillis(50));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for the condition");
            }
            Thread.sleep(20);
        }
    }

    @Test
    void aWorkflowTaskIsReplayedAndTheDecisionIsReported() throws Exception {
        try (FakeServer server = new FakeServer();
                Worker worker = worker(server).workflow("Order", CHARGE).build()) {
            server.offerWorkflowTask(workflowTask(List.of(new HistoryEvent(1, "WorkflowStarted", "{\"amount\":5}", T))));

            worker.start();
            await(() -> !server.completions().isEmpty());

            FakeServer.Request sent = server.completions().get(0);
            assertThat(sent.path()).isEqualTo("/api/v1/tasks/5/complete-workflow");
            assertThat(sent.query()).isEqualTo("attempt=2&decidedAtSeq=1");
            assertThat(WireFormat.decodeDecision(sent.body()).commands())
                    .isEqualTo(List.of(new Command.ScheduleActivity(1, "charge", "{\"amount\":5}")));
        }
    }

    @Test
    void anActivityTaskRunsTheFunctionAndReportsItsResult() throws Exception {
        try (FakeServer server = new FakeServer();
                Worker worker = worker(server).activity("charge", input -> "{\"charged\":true}").build()) {
            server.offerActivityTask(activityTask("charge", "{\"amount\":5}"));

            worker.start();
            await(() -> !server.completions().isEmpty());

            FakeServer.Request sent = server.completions().get(0);
            assertThat(sent.path()).isEqualTo("/api/v1/tasks/9/complete-activity");
            assertThat(sent.query()).isEqualTo("attempt=4");
            JsonNode body = JsonNode.parse(sent.body());
            assertThat(body.required("succeeded").asBoolean()).isTrue();
            assertThat(body.required("payload").raw()).isEqualTo("{\"charged\":true}");
        }
    }

    @Test
    void anActivityThatThrowsIsReportedAsARetryableFailure() throws Exception {
        try (FakeServer server = new FakeServer();
                Worker worker = worker(server)
                        .activity("charge", input -> {
                            throw new IllegalStateException("bank \"down\"");
                        })
                        .build()) {
            server.offerActivityTask(activityTask("charge", "{}"));

            worker.start();
            await(() -> !server.completions().isEmpty());

            JsonNode body = JsonNode.parse(server.completions().get(0).body());
            assertThat(body.required("succeeded").asBoolean()).isFalse();
            assertThat(body.required("retryable").asBoolean()).isTrue();
            assertThat(body.required("payload").required("type").asText()).isEqualTo("IllegalStateException");
            assertThat(body.required("payload").required("message").asText()).isEqualTo("bank \"down\"");
        }
    }

    @Test
    void aNonRetryableExceptionIsReportedAsFinal() throws Exception {
        try (FakeServer server = new FakeServer();
                Worker worker = worker(server)
                        .activity("charge", input -> {
                            throw new NonRetryableException("card declined");
                        })
                        .build()) {
            server.offerActivityTask(activityTask("charge", "{}"));

            worker.start();
            await(() -> !server.completions().isEmpty());

            JsonNode body = JsonNode.parse(server.completions().get(0).body());
            assertThat(body.required("retryable").asBoolean()).isFalse();
            assertThat(body.required("payload").required("message").asText()).isEqualTo("card declined");
        }
    }

    @Test
    void anActivityThatReturnsInvalidJsonFailsWithoutRetries() throws Exception {
        try (FakeServer server = new FakeServer();
                Worker worker = worker(server).activity("charge", input -> "not json").build()) {
            server.offerActivityTask(activityTask("charge", "{}"));

            worker.start();
            await(() -> !server.completions().isEmpty());

            JsonNode body = JsonNode.parse(server.completions().get(0).body());
            assertThat(body.required("succeeded").asBoolean()).isFalse();
            assertThat(body.required("retryable").asBoolean()).isFalse();
            assertThat(body.required("payload").required("type").asText()).isEqualTo("InvalidActivityResult");
        }
    }

    @Test
    void aTaskForSomethingTheWorkerDoesNotKnowIsLeftAlone() throws Exception {
        try (FakeServer server = new FakeServer();
                Worker worker = worker(server).activity("charge", input -> "1").build()) {
            server.offerActivityTask(activityTask("refund", "{}"));
            server.offerActivityTask(activityTask("charge", "{}")); // proves the worker kept going

            worker.start();
            await(() -> !server.completions().isEmpty());

            assertThat(server.completions().size()).isEqualTo(1); // only the known one was answered
        }
    }

    @Test
    void aWorkflowThatNoLongerMatchesItsHistoryIsNotAnswered() throws Exception {
        // History says the first call was "ship" but the code calls "charge": non-determinism.
        List<HistoryEvent> history = List.of(
                new HistoryEvent(1, "WorkflowStarted", "{}", T),
                new HistoryEvent(2, "ActivityScheduled", 1L, "ship", "{}", T));
        try (FakeServer server = new FakeServer();
                Worker worker = worker(server).workflow("Order", CHARGE).build()) {
            server.offerWorkflowTask(workflowTask(history));
            server.offerWorkflowTask(workflowTask(List.of(new HistoryEvent(1, "WorkflowStarted", "{}", T))));

            worker.start();
            await(() -> !server.completions().isEmpty()); // the second, valid task gets answered

            assertThat(server.completions().size()).isEqualTo(1);
            assertThat(WireFormat.decodeDecision(server.completions().get(0).body()).commands().size()).isEqualTo(1);
        }
    }

    @Test
    void aWorkerThatIsAbandonedMidActivityReportsNothing() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (FakeServer server = new FakeServer()) {
            Worker worker = worker(server)
                    .activity("charge", input -> {
                        started.countDown();
                        new CountDownLatch(1).await(); // hangs until interrupted
                        return "1";
                    })
                    .build();
            server.offerActivityTask(activityTask("charge", "{}"));
            worker.start();
            started.await();

            worker.abandon();
            Thread.sleep(300);

            assertThat(server.completions().isEmpty()).isTrue();
        }
    }

    @Test
    void anAbandonedWorkerStaysSilentEvenIfItsActivityIgnoresTheInterrupt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try (FakeServer server = new FakeServer()) {
            Worker worker = worker(server)
                    .activity("charge", input -> {
                        started.countDown();
                        while (release.getCount() > 0) {
                            try {
                                release.await();
                            } catch (InterruptedException ignored) {
                                // a stubborn activity that keeps going after the interrupt
                            }
                        }
                        finished.countDown();
                        return "1";
                    })
                    .build();
            server.offerActivityTask(activityTask("charge", "{}"));
            worker.start();
            started.await();

            worker.abandon();
            release.countDown(); // the activity now completes normally
            finished.await();
            Thread.sleep(300);

            assertThat(server.completions().isEmpty()).isTrue();
        }
    }

    @Test
    void closeStopsThePollingThreads() throws Exception {
        try (FakeServer server = new FakeServer()) {
            Worker worker = worker(server).build();
            worker.start();
            await(() -> !server.requests().isEmpty());

            worker.close();
            int seen = server.requests().size();
            Thread.sleep(300);

            assertThat(server.requests().size() - seen < 3).isTrue(); // at most a request already in flight
        }
    }

    @Test
    void pollFailuresDoNotKillTheWorker() throws Exception {
        FakeServer server = new FakeServer();
        Worker worker = worker(server).activity("charge", input -> "1").build();
        worker.start();
        await(() -> !server.requests().isEmpty());
        server.close(); // the server disappears; the worker keeps retrying quietly
        Thread.sleep(200);

        worker.close(); // must return promptly
    }
}
