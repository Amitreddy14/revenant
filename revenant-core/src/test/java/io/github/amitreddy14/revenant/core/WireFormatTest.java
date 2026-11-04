package io.github.amitreddy14.revenant.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WireFormatTest {

    private static final Instant T = Instant.parse("2026-11-05T10:15:30Z");
    private static final WorkflowId ID = WorkflowId.parse("0d6a3f0e-1c7e-4a52-9a52-2d3d6c1f6b11");

    @Test
    void aWorkflowTaskCarriesItsTypeAndTheWholeHistory() {
        List<HistoryEvent> history = List.of(
                new HistoryEvent(1, "WorkflowStarted", "{\"orderId\": 7}", T),
                new HistoryEvent(2, "ActivityScheduled", 1L, "charge \"card\"", "{\"amount\":5}", T));
        ClaimedTask task = new ClaimedTask(41, ID, TaskKind.WORKFLOW_TASK, null, null, "{}", 3);

        PolledTask back = WireFormat.decodePolledTask(WireFormat.encodePolledTask(new PolledTask(task, "Order", history)));

        assertThat(back.task()).isEqualTo(task);
        assertThat(back.workflowType()).isEqualTo("Order");
        assertThat(back.history()).isEqualTo(history);
    }

    @Test
    void anActivityTaskCarriesNameCommandAndInputButNoHistory() {
        ClaimedTask task = new ClaimedTask(9, ID, TaskKind.ACTIVITY_TASK, 2L, "charge", "{\"amount\":  5}", 1);

        String json = WireFormat.encodePolledTask(new PolledTask(task, null, List.of()));
        PolledTask back = WireFormat.decodePolledTask(json);

        assertThat(back.task()).isEqualTo(task); // the input text survives unchanged
        assertThat(back.workflowType()).isNull();
        assertThat(back.history().isEmpty()).isTrue();
        assertThat(json.contains("history")).isFalse();
    }

    @Test
    void aDecisionOfEveryKindOfCommandSurvivesTheTrip() {
        ActivityOptions options = new ActivityOptions(
                new RetryPolicy(5, Duration.ofMillis(250), 1.5, Duration.ofSeconds(30)), Duration.ofSeconds(10));
        Decision decision = new Decision(List.of(
                new Command.ScheduleActivity(1, "charge", "{\"amount\": 5}", options),
                new Command.ScheduleActivity(2, "ship", "\"o-1\""),
                new Command.StartTimer(3, 3_600_000),
                new Command.CompleteWorkflow("{\"ok\":true}"),
                new Command.FailWorkflow("{\"type\":\"Boom\"}")));

        assertThat(WireFormat.decodeDecision(WireFormat.encodeDecision(decision))).isEqualTo(decision);
    }

    @Test
    void anEmptyDecisionSurvivesTheTrip() {
        assertThat(WireFormat.decodeDecision(WireFormat.encodeDecision(Decision.none()))).isEqualTo(Decision.none());
    }

    @Test
    void activityResultsSurviveTheTrip() {
        for (ActivityResult result : List.of(
                ActivityResult.success("{\"id\": 1}"),
                ActivityResult.failure("{\"type\":\"Flaky\"}"),
                ActivityResult.nonRetryableFailure("{\"type\":\"Declined\"}"))) {
            assertThat(WireFormat.decodeActivityResult(WireFormat.encodeActivityResult(result))).isEqualTo(result);
        }
    }

    @Test
    void aFailureWithoutARetryableFlagCountsAsRetryable() {
        ActivityResult result = WireFormat.decodeActivityResult("{\"succeeded\":false,\"payload\":{}}");

        assertThat(result.retryable()).isTrue();
    }

    @Test
    void strangeNamesAreEscaped() {
        HistoryEvent event = new HistoryEvent(1, "ActivityScheduled", 1L, "a\"b\\c\nd", "{}", T);

        PolledTask back = WireFormat.decodePolledTask(WireFormat.encodePolledTask(new PolledTask(
                new ClaimedTask(1, ID, TaskKind.WORKFLOW_TASK, null, null, "{}", 1), "T", List.of(event))));

        assertThat(back.history().get(0).name()).isEqualTo("a\"b\\c\nd");
    }

    @Test
    void messagesWithTheWrongShapeAreRejected() {
        assertThatThrownBy(() -> WireFormat.decodeDecision("{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WireFormat.decodeDecision("{\"commands\":[{\"type\":\"Explode\"}]}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WireFormat.decodeDecision("{\"commands\":[{\"type\":\"StartTimer\",\"commandId\":1}]}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WireFormat.decodeActivityResult("{\"payload\":1}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WireFormat.decodeDecision("not json")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRetryPolicyThatBreaksTheRulesIsRejectedOnDecode() {
        String bad = "{\"commands\":[{\"type\":\"ScheduleActivity\",\"commandId\":1,\"name\":\"x\",\"input\":{},"
                + "\"maxAttempts\":0,\"initialIntervalMs\":1,\"backoffCoefficient\":2.0,\"maxIntervalMs\":5,"
                + "\"startToCloseMs\":null}]}";

        assertThatThrownBy(() -> WireFormat.decodeDecision(bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
