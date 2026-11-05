package io.github.amitreddy14.revenant.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amitreddy14.revenant.core.Command;
import io.github.amitreddy14.revenant.core.Decider;
import io.github.amitreddy14.revenant.core.Decision;
import io.github.amitreddy14.revenant.core.HistoryEvent;
import io.github.amitreddy14.revenant.core.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The workflow's decisions at every point of its life, including after a crash. */
class OrderWorkflowTest {

    private static final Instant T = Instant.parse("2026-11-05T10:00:00Z");
    private static final String ORDER = "{\"orderId\":\"A-1\",\"amount\":4999,\"item\":\"book\"}";
    private static final OrderWorkflow WORKFLOW = new OrderWorkflow();

    private final List<HistoryEvent> history = new ArrayList<>();

    private void started() {
        history.add(new HistoryEvent(1, "WorkflowStarted", ORDER, T));
    }

    private void step(long commandId, String name, String result) {
        history.add(new HistoryEvent(history.size() + 1L, "ActivityScheduled", commandId, name, ORDER, T));
        history.add(new HistoryEvent(history.size() + 1L, "ActivityCompleted", commandId, name, result, T));
    }

    private Decision decide() {
        return Decider.decide(WORKFLOW, history);
    }

    @Test
    void aNewOrderStartsWithTheCharge() {
        started();

        Decision decision = decide();

        assertThat(decision.commands().size()).isEqualTo(1);
        Command.ScheduleActivity first = (Command.ScheduleActivity) decision.commands().get(0);
        assertThat(first.name()).isEqualTo("charge");
        assertThat(first.commandId()).isEqualTo(1L);
        assertThat(first.inputJson()).isEqualTo(ORDER);
    }

    @Test
    void afterTheChargeAndTheReservationTheNextStepIsShipping() {
        started();
        step(1, "charge", "{\"chargeId\":\"ch_A-1\"}");
        step(2, "reserveStock", "{\"reservationId\":\"rs_A-1\"}");

        Command.ScheduleActivity next = (Command.ScheduleActivity) decide().commands().get(0);

        assertThat(next.name()).isEqualTo("ship");
        assertThat(next.commandId()).isEqualTo(3L);
    }

    @Test
    void theReceiptReceivesTheShipmentWithItsTrackingNumber() {
        started();
        step(1, "charge", "{\"chargeId\":\"ch_A-1\"}");
        step(2, "reserveStock", "{\"reservationId\":\"rs_A-1\"}");
        step(3, "ship", "{\"tracking\": \"TRK-A-1\"}");

        Command.ScheduleActivity next = (Command.ScheduleActivity) decide().commands().get(0);

        assertThat(next.name()).isEqualTo("sendReceipt");
        assertThat(next.inputJson()).isEqualTo("{\"tracking\": \"TRK-A-1\"}");
    }

    @Test
    void whileShippingIsStillRunningThereIsNothingToDoAndNothingIsRepeated() {
        // The state a crash leaves behind: shipping was scheduled, no result yet.
        started();
        step(1, "charge", "{\"chargeId\":\"ch_A-1\"}");
        step(2, "reserveStock", "{\"reservationId\":\"rs_A-1\"}");
        history.add(new HistoryEvent(history.size() + 1L, "ActivityScheduled", 3L, "ship", ORDER, T));

        assertThat(decide().isEmpty()).isTrue(); // no second charge, no second reservation
    }

    @Test
    void afterTheLastStepTheWorkflowCompletesWithEveryResult() {
        started();
        step(1, "charge", "{\"chargeId\":\"ch_A-1\"}");
        step(2, "reserveStock", "{\"reservationId\":\"rs_A-1\"}");
        step(3, "ship", "{\"tracking\":\"TRK-A-1\"}");
        step(4, "sendReceipt", "{\"sent\":true}");

        Decision decision = decide();

        assertThat(decision.closesWorkflowAs().isPresent()).isTrue();
        Command.CompleteWorkflow done = (Command.CompleteWorkflow) decision.commands().get(0);
        JsonNode result = JsonNode.parse(done.resultJson());
        assertThat(result.required("charge").required("chargeId").asText()).isEqualTo("ch_A-1");
        assertThat(result.required("reservation").required("reservationId").asText()).isEqualTo("rs_A-1");
        assertThat(result.required("shipment").required("tracking").asText()).isEqualTo("TRK-A-1");
        assertThat(result.required("receipt").required("sent").asBoolean()).isTrue();
    }
}
