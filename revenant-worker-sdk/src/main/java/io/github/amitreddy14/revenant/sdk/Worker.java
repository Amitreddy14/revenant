package io.github.amitreddy14.revenant.sdk;

import io.github.amitreddy14.revenant.core.ActivityResult;
import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.Decider;
import io.github.amitreddy14.revenant.core.Decision;
import io.github.amitreddy14.revenant.core.HistoryEvent;
import io.github.amitreddy14.revenant.core.InvalidHistoryException;
import io.github.amitreddy14.revenant.core.JsonNode;
import io.github.amitreddy14.revenant.core.NonDeterminismException;
import io.github.amitreddy14.revenant.core.PolledTask;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WireFormat;
import io.github.amitreddy14.revenant.core.WorkflowDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs workflow and activity code for a Revenant server (ADR-0013).
 *
 * <p>The worker keeps no state that matters. Workflow tasks are answered by replaying the workflow
 * code on the history the server sends, and activity tasks by calling the registered function. If
 * the process dies at any moment, the server notices when the task's lease runs out and hands the
 * task to another worker.
 *
 * <p>A worker only knows the workflow types and activity names registered on it. A task for
 * something else is left alone (and handed out again after its lease expires), so run every
 * registered name on every worker of a deployment.
 */
public final class Worker implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Worker.class.getName());

    private final RevenantClient client;
    private final String workerId;
    private final Map<String, WorkflowDefinition> workflows;
    private final Map<String, ActivityFunction> activities;
    private final int workflowThreads;
    private final int activityThreads;
    private final Duration lease;
    private final Duration pollWait;
    private final Duration errorBackoff;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;
    private volatile boolean abandoned;

    private Worker(Builder b) {
        this.client = b.client;
        this.workerId = b.workerId;
        this.workflows = Map.copyOf(b.workflows);
        this.activities = Map.copyOf(b.activities);
        this.workflowThreads = b.workflowThreads;
        this.activityThreads = b.activityThreads;
        this.lease = b.lease;
        this.pollWait = b.pollWait;
        this.errorBackoff = b.errorBackoff;
    }

    public static Builder builder(RevenantClient client) {
        return new Builder(client);
    }

    public String workerId() {
        return workerId;
    }

    /** Starts the polling threads and returns. The threads keep the JVM alive until {@link #close()}. */
    public synchronized void start() {
        if (running || abandoned) {
            throw new IllegalStateException("worker was already started");
        }
        running = true;
        for (int i = 0; i < workflowThreads; i++) {
            spawn("workflow-" + i, TaskKind.WORKFLOW_TASK);
        }
        for (int i = 0; i < activityThreads; i++) {
            spawn("activity-" + i, TaskKind.ACTIVITY_TASK);
        }
        LOG.log(System.Logger.Level.INFO, "Worker {0} started: {1} workflow type(s), {2} activit(ies)",
                workerId, workflows.size(), activities.size());
    }

    private void spawn(String name, TaskKind kind) {
        Thread thread = new Thread(() -> pollLoop(kind), "revenant-" + workerId + "-" + name);
        threads.add(thread);
        thread.start();
    }

    /**
     * Stops polling and interrupts work in progress. Results of interrupted activities are not
     * reported, so those calls run again on another worker once their lease expires.
     */
    @Override
    public void close() {
        List<Thread> toJoin;
        synchronized (this) {
            running = false;
            toJoin = List.copyOf(threads);
        }
        toJoin.forEach(Thread::interrupt);
        for (Thread thread : toJoin) {
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Simulates a crash: stops at once, reports nothing more and does not wait. Whatever was in
     * flight is left for the server to hand to another worker when the lease runs out. Useful in
     * tests and demos; a real crash (such as {@code kill -9}) has the same effect.
     */
    public synchronized void abandon() {
        abandoned = true;
        running = false;
        threads.forEach(Thread::interrupt);
    }

    private void pollLoop(TaskKind kind) {
        while (running) {
            try {
                Optional<PolledTask> polled = client.poll(kind, workerId, lease, pollWait);
                if (polled.isPresent() && running) {
                    handle(polled.get());
                }
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                LOG.log(System.Logger.Level.WARNING, "Worker {0}: {1} (will retry)", workerId, e.toString());
                pause();
            }
        }
    }

    private void pause() {
        try {
            Thread.sleep(errorBackoff.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handle(PolledTask polled) {
        if (polled.task().kind() == TaskKind.WORKFLOW_TASK) {
            handleWorkflowTask(polled);
        } else {
            handleActivityTask(polled.task());
        }
    }

    private void handleWorkflowTask(PolledTask polled) {
        ClaimedTask task = polled.task();
        WorkflowDefinition definition = workflows.get(polled.workflowType());
        if (definition == null) {
            LOG.log(System.Logger.Level.WARNING, "Worker {0}: no workflow registered for type \"{1}\"; leaving task {2}",
                    workerId, polled.workflowType(), task.taskId());
            return;
        }
        List<HistoryEvent> history = polled.history();
        Decision decision;
        try {
            decision = Decider.decide(definition, history);
        } catch (NonDeterminismException | InvalidHistoryException e) {
            // The code no longer matches the history. Reporting nothing lets the lease run out so a
            // worker with corrected code can take the task; the workflow itself is not failed.
            LOG.log(System.Logger.Level.ERROR, "Worker {0}: workflow {1} cannot be replayed: {2}",
                    workerId, task.workflowId(), e.getMessage());
            return;
        }
        if (abandoned) {
            return;
        }
        long decidedAtSeq = history.get(history.size() - 1).seq();
        TaskOutcome outcome = client.completeWorkflowTask(task, decidedAtSeq, decision);
        LOG.log(System.Logger.Level.DEBUG, "Worker {0}: workflow task {1} -> {2}", workerId, task.taskId(), outcome);
    }

    private void handleActivityTask(ClaimedTask task) {
        ActivityFunction function = activities.get(task.name());
        if (function == null) {
            LOG.log(System.Logger.Level.WARNING, "Worker {0}: no activity registered as \"{1}\"; leaving task {2}",
                    workerId, task.name(), task.taskId());
            return;
        }
        ActivityResult result;
        try {
            String output = function.execute(task.payloadJson());
            result = validResult(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return; // being shut down: report nothing, the lease will run out
        } catch (NonRetryableException e) {
            result = ActivityResult.nonRetryableFailure(errorJson(e));
        } catch (Exception e) {
            result = ActivityResult.failure(errorJson(e));
        }
        if (abandoned || !running) {
            return;
        }
        TaskOutcome outcome = client.completeActivityTask(task, result);
        LOG.log(System.Logger.Level.DEBUG, "Worker {0}: activity task {1} -> {2}", workerId, task.taskId(), outcome);
    }

    private static ActivityResult validResult(String output) {
        try {
            JsonNode.parse(output);
            return ActivityResult.success(output);
        } catch (IllegalArgumentException e) {
            return ActivityResult.nonRetryableFailure("{\"type\":\"InvalidActivityResult\",\"message\":"
                    + WireFormat.quote("the activity must return valid JSON: " + e.getMessage()) + "}");
        }
    }

    private static String errorJson(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return "{\"type\":" + WireFormat.quote(e.getClass().getSimpleName()) + ",\"message\":"
                + WireFormat.quote(message) + "}";
    }

    /** Collects the settings of a worker. */
    public static final class Builder {
        private final RevenantClient client;
        private String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        private final Map<String, WorkflowDefinition> workflows = new ConcurrentHashMap<>();
        private final Map<String, ActivityFunction> activities = new ConcurrentHashMap<>();
        private int workflowThreads = 2;
        private int activityThreads = 4;
        private Duration lease = Duration.ofSeconds(30);
        private Duration pollWait = Duration.ofSeconds(20);
        private Duration errorBackoff = Duration.ofSeconds(1);

        private Builder(RevenantClient client) {
            this.client = Objects.requireNonNull(client, "client");
        }

        /** A name for logs and for the server's records of who holds a task. */
        public Builder workerId(String id) {
            this.workerId = Objects.requireNonNull(id, "id");
            return this;
        }

        public Builder workflow(String type, WorkflowDefinition definition) {
            workflows.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(definition, "definition"));
            return this;
        }

        public Builder activity(String name, ActivityFunction function) {
            activities.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(function, "function"));
            return this;
        }

        public Builder workflowThreads(int count) {
            this.workflowThreads = atLeastOne(count);
            return this;
        }

        public Builder activityThreads(int count) {
            this.activityThreads = atLeastOne(count);
            return this;
        }

        /**
         * How long this worker may hold a task before the server gives it to someone else. Choose
         * longer than your slowest activity unless the workflow sets a start-to-close timeout.
         */
        public Builder lease(Duration lease) {
            this.lease = Objects.requireNonNull(lease, "lease");
            return this;
        }

        /** How long one poll request waits on the server for a task to appear. */
        public Builder pollWait(Duration wait) {
            this.pollWait = Objects.requireNonNull(wait, "wait");
            return this;
        }

        /** The pause after a failed call to the server. */
        public Builder errorBackoff(Duration backoff) {
            this.errorBackoff = Objects.requireNonNull(backoff, "backoff");
            return this;
        }

        public Worker build() {
            return new Worker(this);
        }

        private static int atLeastOne(int count) {
            if (count < 1) {
                throw new IllegalArgumentException("thread count must be at least 1, was " + count);
            }
            return count;
        }
    }
}
