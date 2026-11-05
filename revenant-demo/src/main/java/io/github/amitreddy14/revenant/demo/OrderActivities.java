package io.github.amitreddy14.revenant.demo;

import io.github.amitreddy14.revenant.core.JsonNode;
import io.github.amitreddy14.revenant.core.WireFormat;
import io.github.amitreddy14.revenant.sdk.Worker;

/**
 * The four activities of the order workflow. Each one does its "work" by writing to the
 * {@link Ledger} and prints a line, so a person watching the worker's console can follow along.
 *
 * <p>The ids they return come from the order id, never from a random number or the clock. That is
 * what makes an activity safe to run twice: a repeated {@code ship} produces the same tracking
 * number, which a real carrier API would treat as the same booking (an idempotency key).
 */
public final class OrderActivities {

    /** How long the slow step takes in total; the demo kills the worker in the middle of it. */
    static final int PACKING_STEPS = 6;

    private final Ledger ledger;
    private final long stepMillis;

    /**
     * @param stepMillis the pause per packing step and for the quick steps; tests pass 0
     */
    public OrderActivities(Ledger ledger, long stepMillis) {
        this.ledger = ledger;
        this.stepMillis = stepMillis;
    }

    /** Registers all four activities on a worker. */
    public Worker.Builder registerOn(Worker.Builder builder) {
        return builder
                .activity("charge", this::charge)
                .activity("reserveStock", this::reserveStock)
                .activity("ship", this::ship)
                .activity("sendReceipt", this::sendReceipt);
    }

    String charge(String inputJson) throws InterruptedException {
        JsonNode order = JsonNode.parse(inputJson);
        String id = orderId(order);
        say("charging the card for order " + id + " ...");
        pause(stepMillis);
        ledger.record("charge order=" + id + " amount=" + order.required("amount").raw());
        say("  card charged");
        return "{\"chargeId\":" + WireFormat.quote("ch_" + id) + "}";
    }

    String reserveStock(String inputJson) throws InterruptedException {
        JsonNode order = JsonNode.parse(inputJson);
        String id = orderId(order);
        say("reserving stock for order " + id + " ...");
        pause(stepMillis);
        ledger.record("reserve order=" + id + " item=" + order.required("item").asText());
        say("  stock reserved");
        return "{\"reservationId\":" + WireFormat.quote("rs_" + id) + "}";
    }

    /** The slow step. A process killed while it runs leaves "ship STARTED" without "ship DONE". */
    String ship(String inputJson) throws InterruptedException {
        String id = orderId(JsonNode.parse(inputJson));
        ledger.record("ship STARTED order=" + id);
        say("shipping order " + id + " (this takes a while) ...");
        for (int step = 1; step <= PACKING_STEPS; step++) {
            pause(stepMillis);
            say("  packing " + step + "/" + PACKING_STEPS);
        }
        ledger.record("ship DONE order=" + id);
        say("  parcel handed to the carrier");
        return "{\"tracking\":" + WireFormat.quote("TRK-" + id) + "}";
    }

    String sendReceipt(String inputJson) throws InterruptedException {
        // The workflow passes the shipment (with the tracking number) to this step.
        JsonNode shipment = JsonNode.parse(inputJson);
        String tracking = shipment.required("tracking").asText();
        say("emailing the receipt ...");
        pause(stepMillis);
        ledger.record("receipt tracking=" + tracking);
        say("  receipt sent");
        return "{\"sent\":true}";
    }

    private static String orderId(JsonNode order) {
        return order.required("orderId").asText();
    }

    private static void pause(long millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    private static void say(String message) {
        System.out.println(message);
        System.out.flush();
    }
}
