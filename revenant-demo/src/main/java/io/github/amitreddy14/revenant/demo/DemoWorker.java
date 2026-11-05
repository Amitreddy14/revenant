package io.github.amitreddy14.revenant.demo;

import io.github.amitreddy14.revenant.sdk.RevenantClient;
import io.github.amitreddy14.revenant.sdk.Worker;
import java.nio.file.Path;
import java.time.Duration;

/**
 * A worker process for the demo. It runs until it is stopped, and it is meant to be killed:
 * there is no shutdown hook that reports anything, so {@code kill -9} and a clean exit leave the
 * server in the same situation.
 *
 * <pre>
 * java -cp "lib/*" io.github.amitreddy14.revenant.demo.DemoWorker [serverUrl] [ledgerFile]
 * </pre>
 */
public final class DemoWorker {

    private DemoWorker() {}

    public static void main(String[] args) throws InterruptedException {
        String serverUrl = args.length > 0 ? args[0] : "http://localhost:8080";
        Path ledgerFile = Path.of(args.length > 1 ? args[1] : "demo-logs/ledger.txt");

        Ledger ledger = new Ledger(ledgerFile);
        // 1 second per step: the slow "ship" step lasts about six seconds, long enough to kill.
        OrderActivities activities = new OrderActivities(ledger, 1_000);

        Worker worker = activities.registerOn(Worker.builder(new RevenantClient(serverUrl))
                        .workflow(OrderWorkflow.TYPE, new OrderWorkflow())
                        // Short on purpose: after a crash the server waits this long before it hands
                        // the interrupted step to another worker.
                        .lease(Duration.ofSeconds(10))
                        .workflowThreads(1)
                        .activityThreads(1)
                        .pollWait(Duration.ofSeconds(5)))
                .build();
        worker.start();
        System.out.println("worker " + worker.workerId() + " is running against " + serverUrl
                + " (ledger: " + ledgerFile.toAbsolutePath() + ")");
        System.out.flush();
        Thread.currentThread().join(); // the polling threads do the work; keep main parked
    }
}
