package io.github.amitreddy14.revenant.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amitreddy14.revenant.core.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderActivitiesTest {

    private static final String ORDER = "{\"orderId\":\"A-7\",\"amount\":250,\"item\":\"lamp\"}";

    private final Path file = tempLedger();
    private final OrderActivities activities = new OrderActivities(new Ledger(file), 0);

    private static Path tempLedger() {
        try {
            return Files.createTempDirectory("revenant-demo-test").resolve("ledger.txt");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private List<String> lines() throws IOException {
        return Files.exists(file) ? Files.readAllLines(file) : List.of();
    }

    @Test
    void chargingWritesOneLineAndReturnsAnIdDerivedFromTheOrder() throws Exception {
        String result = activities.charge(ORDER);

        assertThat(lines().size()).isEqualTo(1);
        assertThat(lines().get(0).endsWith("| charge order=A-7 amount=250")).isTrue();
        assertThat(JsonNode.parse(result).required("chargeId").asText()).isEqualTo("ch_A-7");
    }

    @Test
    void reservingNamesTheItem() throws Exception {
        String result = activities.reserveStock(ORDER);

        assertThat(lines().get(0).endsWith("| reserve order=A-7 item=lamp")).isTrue();
        assertThat(JsonNode.parse(result).required("reservationId").asText()).isEqualTo("rs_A-7");
    }

    @Test
    void shippingRecordsStartAndDoneAroundTheSlowWork() throws Exception {
        activities.ship(ORDER);

        assertThat(lines().size()).isEqualTo(2);
        assertThat(lines().get(0).endsWith("| ship STARTED order=A-7")).isTrue();
        assertThat(lines().get(1).endsWith("| ship DONE order=A-7")).isTrue();
    }

    @Test
    void shippingTwiceGivesTheSameTrackingNumber() throws Exception {
        String first = activities.ship(ORDER);
        String second = activities.ship(ORDER);

        assertThat(JsonNode.parse(second).required("tracking").asText())
                .isEqualTo(JsonNode.parse(first).required("tracking").asText());
        assertThat(JsonNode.parse(first).required("tracking").asText()).isEqualTo("TRK-A-7");
    }

    @Test
    void theReceiptNamesTheTrackingNumberItWasGiven() throws Exception {
        activities.sendReceipt("{\"tracking\": \"TRK-A-7\"}");

        assertThat(lines().get(0).endsWith("| receipt tracking=TRK-A-7")).isTrue();
    }

    @Test
    void aMissingOrderFieldIsReportedAsAnError() {
        try {
            activities.charge("{\"amount\":1}");
            throw new AssertionError("expected an exception");
        } catch (IllegalArgumentException | InterruptedException expected) {
            assertThat(expected instanceof IllegalArgumentException).isTrue();
        }
    }
}
