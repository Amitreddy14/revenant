package io.github.amitreddy14.revenant.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amitreddy14.revenant.core.ActivityResult;
import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.Decision;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WorkflowId;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RevenantClientTest {

    private static final WorkflowId ID = WorkflowId.parse("0d6a3f0e-1c7e-4a52-9a52-2d3d6c1f6b11");
    private static final ClaimedTask TASK = new ClaimedTask(41, ID, TaskKind.ACTIVITY_TASK, 1L, "charge", "{}", 3);

    @Test
    void startingAWorkflowSendsTypeIdAndInputAndReturnsTheId() throws Exception {
        try (FakeServer server = new FakeServer()) {
            RevenantClient client = new RevenantClient(server.url());

            WorkflowId id = client.startWorkflow(ID, "Order & Co", "{\"orderId\": 7}");

            assertThat(id).isEqualTo(ID);
            FakeServer.Request sent = server.requests().get(0);
            assertThat(sent.method()).isEqualTo("POST");
            assertThat(sent.path()).isEqualTo("/api/v1/workflows");
            assertThat(sent.query()).isEqualTo("type=Order+%26+Co&id=" + ID);
            assertThat(sent.body()).isEqualTo("{\"orderId\": 7}");
        }
    }

    @Test
    void aTaskThatIsNotThereIsAnEmptyPoll() throws Exception {
        try (FakeServer server = new FakeServer()) {
            RevenantClient client = new RevenantClient(server.url());

            assertThat(client.poll(TaskKind.ACTIVITY_TASK, "w1", Duration.ofSeconds(30), Duration.ofMillis(100)))
                    .isEmpty();
            assertThat(server.requests().get(0).query())
                    .isEqualTo("kind=ACTIVITY_TASK&worker=w1&leaseMs=30000&waitMs=100");
        }
    }

    @Test
    void aPolledTaskIsDecoded() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.offerActivityTask("{\"taskId\":41,\"attempt\":3,\"workflowId\":\"" + ID
                    + "\",\"kind\":\"ACTIVITY_TASK\",\"commandId\":1,\"name\":\"charge\",\"payload\":{\"a\": 1}}");
            RevenantClient client = new RevenantClient(server.url());

            var polled = client.poll(TaskKind.ACTIVITY_TASK, "w1", Duration.ofSeconds(30), Duration.ofMillis(100));

            assertThat(polled.orElseThrow().task())
                    .isEqualTo(new ClaimedTask(41, ID, TaskKind.ACTIVITY_TASK, 1L, "charge", "{\"a\": 1}", 3));
        }
    }

    @Test
    void completionsCarryTheFencingAttemptAndMapTheOutcome() throws Exception {
        try (FakeServer server = new FakeServer()) {
            RevenantClient client = new RevenantClient(server.url());

            TaskOutcome outcome = client.completeActivityTask(TASK, ActivityResult.success("{\"ok\":true}"));
            client.completeWorkflowTask(TASK, 7, Decision.none());

            assertThat(outcome).isEqualTo(TaskOutcome.APPLIED);
            assertThat(server.completions().get(0).path()).isEqualTo("/api/v1/tasks/41/complete-activity");
            assertThat(server.completions().get(0).query()).isEqualTo("attempt=3");
            assertThat(server.completions().get(1).query()).isEqualTo("attempt=3&decidedAtSeq=7");
        }
    }

    @Test
    void aRefusedLeaseIsAnOutcomeNotAnError() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.answerCompletionsWith(409, "{\"outcome\":\"LEASE_LOST\"}");
            RevenantClient client = new RevenantClient(server.url());

            assertThat(client.completeActivityTask(TASK, ActivityResult.success("1"))).isEqualTo(TaskOutcome.LEASE_LOST);
        }
    }

    @Test
    void otherErrorStatusesAreReported() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.answerCompletionsWith(500, "{\"error\":\"boom\"}");
            RevenantClient client = new RevenantClient(server.url());

            assertThatThrownBy(() -> client.completeActivityTask(TASK, ActivityResult.success("1")))
                    .isInstanceOf(RevenantApiException.class)
                    .hasMessageContaining("500");
        }
    }

    @Test
    void anUnknownWorkflowIsAnEmptyResult() throws Exception {
        try (FakeServer server = new FakeServer()) {
            assertThat(new RevenantClient(server.url()).getWorkflow(ID)).isEmpty();
        }
    }

    @Test
    void anUnreachableServerIsReported() {
        RevenantClient client = new RevenantClient("http://127.0.0.1:1");

        assertThatThrownBy(() -> client.startWorkflow("T", "{}"))
                .isInstanceOf(RevenantClient.UncheckedServerException.class);
    }
}
