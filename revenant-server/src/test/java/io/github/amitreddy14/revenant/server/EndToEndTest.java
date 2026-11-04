package io.github.amitreddy14.revenant.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amitreddy14.revenant.core.ActivityOptions;
import io.github.amitreddy14.revenant.core.HistoryEvent;
import io.github.amitreddy14.revenant.core.JsonNode;
import io.github.amitreddy14.revenant.core.RetryPolicy;
import io.github.amitreddy14.revenant.core.WorkflowDefinition;
import io.github.amitreddy14.revenant.core.WorkflowId;
import io.github.amitreddy14.revenant.sdk.NonRetryableException;
import io.github.amitreddy14.revenant.sdk.RevenantApiException;
import io.github.amitreddy14.revenant.sdk.RevenantClient;
import io.github.amitreddy14.revenant.sdk.Worker;
import io.github.amitreddy14.revenant.sdk.WorkflowDetails;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The whole engine, from the outside: a real HTTP server on a random port, a real PostgreSQL, and
 * real workers from the SDK. Workflows start over HTTP and finish without any test code touching
 * the store.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"revenant.kafka.enabled=false", "revenant.redis.enabled=false",
            "revenant.scheduler.interval=PT0.2S"})
class EndToEndTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Value("${local.server.port}")
    int port;

    private RevenantClient client() {
        return new RevenantClient("http://localhost:" + port);
    }

    private Worker.Builder worker(String name) {
        return Worker.builder(client())
                .workerId(name)
                .workflowThreads(1)
                .activityThreads(2)
                // A closed worker's last long poll can still claim a task on the server and lose the
                // answer. A short lease makes such a "ghost" claim expire quickly (see ADR-0013).
                .lease(Duration.ofSeconds(3))
                .pollWait(Duration.ofSeconds(1))
                .errorBackoff(Duration.ofMillis(200));
    }

    private WorkflowDetails awaitClosed(WorkflowId id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            WorkflowDetails details = client().getWorkflow(id).orElseThrow();
            if (!details.isRunning()) {
                return details;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("workflow " + id + " did not finish in time");
    }

    private static List<String> types(WorkflowDetails details) {
        return details.history().stream().map(HistoryEvent::type).toList();
    }

    @Test
    void aWorkflowRunsToCompletionThroughHttpAndAWorker() throws Exception {
        WorkflowDefinition order = (ctx, input) -> {
            String charge = ctx.callActivity("charge", input);
            String shipment = ctx.callActivity("ship", charge);
            return "{\"charge\":" + charge + ",\"shipment\":" + shipment + "}";
        };
        try (Worker worker = worker("w-order")
                .workflow("E2E-Order", order)
                .activity("charge", in -> "{\"charged\":true}")
                .activity("ship", in -> "{\"tracking\":\"T-1\"}")
                .build()) {
            worker.start();

            WorkflowId id = client().startWorkflow("E2E-Order", "{\"orderId\":7}");
            WorkflowDetails done = awaitClosed(id);

            assertThat(done.status()).isEqualTo("COMPLETED");
            // PostgreSQL's JSONB rewrites spacing, so compare the content, not the text.
            JsonNode result = JsonNode.parse(done.resultJson().orElseThrow());
            assertThat(result.required("charge").required("charged").asBoolean()).isTrue();
            assertThat(result.required("shipment").required("tracking").asText()).isEqualTo("T-1");
            assertThat(types(done)).containsExactly("WorkflowStarted", "ActivityScheduled", "ActivityCompleted",
                    "ActivityScheduled", "ActivityCompleted", "WorkflowCompleted");
        }
    }

    @Test
    void aFlakyActivityIsRetriedUntilItSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WorkflowDefinition flaky = (ctx, input) -> ctx.callActivity("flaky", input,
                ActivityOptions.retrying(RetryPolicy.exponential(4, Duration.ofMillis(50))));
        try (Worker worker = worker("w-flaky")
                .workflow("E2E-Flaky", flaky)
                .activity("flaky", in -> {
                    if (calls.incrementAndGet() < 3) {
                        throw new IllegalStateException("not yet");
                    }
                    return "\"finally\"";
                })
                .build()) {
            worker.start();

            WorkflowDetails done = awaitClosed(client().startWorkflow("E2E-Flaky", "{}"));

            assertThat(done.status()).isEqualTo("COMPLETED");
            assertThat(calls.get()).isEqualTo(3);
            assertThat(types(done)).containsExactly(
                    "WorkflowStarted", "ActivityScheduled", "ActivityCompleted", "WorkflowCompleted");
        }
    }

    @Test
    void aNonRetryableFailureFailsTheWorkflow() throws Exception {
        WorkflowDefinition declined = (ctx, input) -> ctx.callActivity("charge", input,
                ActivityOptions.retrying(RetryPolicy.exponential(5, Duration.ofMillis(50))));
        AtomicInteger calls = new AtomicInteger();
        try (Worker worker = worker("w-declined")
                .workflow("E2E-Declined", declined)
                .activity("charge", in -> {
                    calls.incrementAndGet();
                    throw new NonRetryableException("card declined");
                })
                .build()) {
            worker.start();

            WorkflowDetails done = awaitClosed(client().startWorkflow("E2E-Declined", "{}"));

            assertThat(done.status()).isEqualTo("FAILED");
            assertThat(calls.get()).isEqualTo(1); // five attempts allowed, none wasted
            assertThat(types(done)).containsExactly(
                    "WorkflowStarted", "ActivityScheduled", "ActivityFailed", "WorkflowFailed");
        }
    }

    @Test
    void aWorkflowThatSleepsContinuesAfterTheTimerFires() throws Exception {
        WorkflowDefinition sleeper = (ctx, input) -> {
            ctx.sleep(Duration.ofMillis(800));
            return ctx.callActivity("wake", input);
        };
        try (Worker worker = worker("w-sleeper")
                .workflow("E2E-Sleeper", sleeper)
                .activity("wake", in -> "\"awake\"")
                .build()) {
            worker.start();

            WorkflowDetails done = awaitClosed(client().startWorkflow("E2E-Sleeper", "{}"));

            assertThat(done.status()).isEqualTo("COMPLETED");
            assertThat(types(done)).containsExactly("WorkflowStarted", "TimerStarted", "TimerFired",
                    "ActivityScheduled", "ActivityCompleted", "WorkflowCompleted");
        }
    }

    @Test
    void aWorkerThatDiesMidActivityIsReplacedAndTheWorkflowStillFinishesOnce() throws Exception {
        WorkflowDefinition twoSteps = (ctx, input) -> {
            String first = ctx.callActivity("first", input);
            return ctx.callActivity("second", first);
        };
        CountDownLatch dyingWorkerIsInsideSecond = new CountDownLatch(1);
        AtomicInteger firstRuns = new AtomicInteger();
        AtomicInteger secondRunsByReplacement = new AtomicInteger();

        Worker doomed = worker("w-doomed")
                .lease(Duration.ofSeconds(2))
                .workflow("E2E-Crash", twoSteps)
                .activity("first", in -> {
                    firstRuns.incrementAndGet();
                    return "\"one\"";
                })
                .activity("second", in -> {
                    dyingWorkerIsInsideSecond.countDown();
                    new CountDownLatch(1).await(); // never finishes: the process "dies" here
                    return "\"never\"";
                })
                .build();
        doomed.start();
        WorkflowId id = client().startWorkflow("E2E-Crash", "{}");
        assertThat(dyingWorkerIsInsideSecond.await(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        doomed.abandon(); // like kill -9: nothing is reported, nothing is cleaned up

        try (Worker replacement = worker("w-replacement")
                .lease(Duration.ofSeconds(2))
                .workflow("E2E-Crash", twoSteps)
                .activity("first", in -> {
                    firstRuns.incrementAndGet();
                    return "\"one\"";
                })
                .activity("second", in -> {
                    secondRunsByReplacement.incrementAndGet();
                    return "\"two\"";
                })
                .build()) {
            replacement.start();

            WorkflowDetails done = awaitClosed(id);

            assertThat(done.status()).isEqualTo("COMPLETED");
            assertThat(done.resultJson().orElseThrow()).isEqualTo("\"two\"");
            assertThat(firstRuns.get()).isEqualTo(1);   // the finished step was not repeated
            assertThat(secondRunsByReplacement.get()).isEqualTo(1);
            assertThat(types(done)).containsExactly("WorkflowStarted", "ActivityScheduled", "ActivityCompleted",
                    "ActivityScheduled", "ActivityCompleted", "WorkflowCompleted");
        }
    }

    @Test
    void startingTheSameIdTwiceIsRefused() {
        WorkflowId id = WorkflowId.random();
        client().startWorkflow(id, "E2E-Twice", "{}");

        assertThatThrownBy(() -> client().startWorkflow(id, "E2E-Twice", "{}"))
                .isInstanceOf(RevenantApiException.class)
                .hasMessageContaining("409");
    }

    @Test
    void badRequestsGetClearStatusCodes() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://localhost:" + port + "/api/v1";

        assertThat(status(http, "POST", base + "/workflows?type=T", "{not json")).isEqualTo(400);
        assertThat(status(http, "POST", base + "/workflows?type=T&id=nope", "{}")).isEqualTo(400);
        assertThat(status(http, "POST", base + "/workflows?type=%20", "{}")).isEqualTo(400);
        assertThat(status(http, "POST", base + "/tasks/poll?kind=NOPE&worker=w", "")).isEqualTo(400);
        assertThat(status(http, "GET", base + "/workflows/" + WorkflowId.random(), null)).isEqualTo(404);
        assertThat(status(http, "GET", base + "/workflows/not-a-uuid", null)).isEqualTo(400);
        assertThat(status(http, "POST", base + "/tasks/999999/complete-activity?attempt=1",
                "{\"succeeded\":true,\"payload\":1}")).isEqualTo(409); // no such lease
        assertThat(status(http, "POST", base + "/tasks/1/complete-workflow?attempt=1&decidedAtSeq=1",
                "{\"commands\":[{\"type\":\"Explode\"}]}")).isEqualTo(400);
        assertThat(status(http, "POST", base + "/tasks/poll?kind=ACTIVITY_TASK&worker=w&waitMs=0", ""))
                .isEqualTo(204);
    }

    private static int status(HttpClient http, String method, String url, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json");
        request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
