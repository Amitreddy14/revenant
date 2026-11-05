package io.github.amitreddy14.revenant.demo;

import io.github.amitreddy14.revenant.core.ActivityOptions;
import io.github.amitreddy14.revenant.core.RetryPolicy;
import io.github.amitreddy14.revenant.core.WorkflowContext;
import io.github.amitreddy14.revenant.core.WorkflowDefinition;
import java.time.Duration;

/**
 * Order fulfilment: charge, reserve stock, ship, send a receipt.
 *
 * <p>This is ordinary Java. Nothing in it says "save my place": after a crash the engine runs it
 * again from the top and answers the calls that already finished from the recorded history, so the
 * code resumes at the first call that never completed.
 */
public final class OrderWorkflow implements WorkflowDefinition {

    public static final String TYPE = "OrderFulfilment";

    private static final ActivityOptions RETRY_A_FEW_TIMES =
            ActivityOptions.retrying(RetryPolicy.exponential(3, Duration.ofSeconds(1)));

    @Override
    public String execute(WorkflowContext ctx, String orderJson) {
        String charge = ctx.callActivity("charge", orderJson, RETRY_A_FEW_TIMES);
        String reservation = ctx.callActivity("reserveStock", orderJson, RETRY_A_FEW_TIMES);
        String shipment = ctx.callActivity("ship", orderJson);
        String receipt = ctx.callActivity("sendReceipt", shipment);
        return "{\"charge\":" + charge + ",\"reservation\":" + reservation
                + ",\"shipment\":" + shipment + ",\"receipt\":" + receipt + "}";
    }
}
