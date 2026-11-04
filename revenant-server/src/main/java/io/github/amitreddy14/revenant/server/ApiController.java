package io.github.amitreddy14.revenant.server;

import io.github.amitreddy14.revenant.core.ActivityResult;
import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.Decision;
import io.github.amitreddy14.revenant.core.JsonNode;
import io.github.amitreddy14.revenant.core.PolledTask;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WireFormat;
import io.github.amitreddy14.revenant.core.WorkflowExecution;
import io.github.amitreddy14.revenant.core.WorkflowId;
import io.github.amitreddy14.revenant.persistence.CompletionOutcome;
import io.github.amitreddy14.revenant.persistence.WorkflowAlreadyExistsException;
import io.github.amitreddy14.revenant.persistence.WorkflowStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP API (ADR-0013). Request and response bodies are JSON text built with
 * {@link WireFormat}, so inputs and results pass through byte for byte.
 *
 * <p>A worker only sends the task id and attempt it was given. The task's contents are read from
 * the database, never taken from the request.
 */
@RestController
@RequestMapping("/api/v1")
class ApiController {

    private static final int MAX_TYPE_LENGTH = 200;

    private final WorkflowStore store;
    private final WorkerApi workers;

    ApiController(WorkflowStore store, WorkerApi workers) {
        this.store = store;
        this.workers = workers;
    }

    /** Starts a workflow. The body is the workflow input (JSON); an empty body means {@code {}}. */
    @PostMapping("/workflows")
    ResponseEntity<String> start(
            @RequestParam("type") String type,
            @RequestParam(value = "id", required = false) String id,
            @RequestBody(required = false) String body) {
        if (type.isBlank() || type.length() > MAX_TYPE_LENGTH) {
            return error(HttpStatus.BAD_REQUEST, "type must be 1 to " + MAX_TYPE_LENGTH + " characters");
        }
        String input = body == null || body.isBlank() ? "{}" : body.strip();
        WorkflowId workflowId;
        try {
            JsonNode.parse(input);
            workflowId = id == null ? WorkflowId.random() : WorkflowId.parse(id);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        try {
            store.start(workflowId, type, input);
        } catch (WorkflowAlreadyExistsException e) {
            return error(HttpStatus.CONFLICT, "workflow " + workflowId + " already exists");
        }
        return json(HttpStatus.CREATED, "{\"workflowId\":" + WireFormat.quote(workflowId.toString()) + "}");
    }

    @GetMapping("/workflows/{id}")
    ResponseEntity<String> get(@PathVariable("id") String id) {
        WorkflowId workflowId;
        try {
            workflowId = WorkflowId.parse(id);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "not a valid workflow id");
        }
        Optional<WorkflowExecution> workflow = store.find(workflowId);
        if (workflow.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "no such workflow");
        }
        WorkflowExecution found = workflow.get();
        return json(HttpStatus.OK, "{\"workflowId\":" + WireFormat.quote(found.workflowId().toString())
                + ",\"type\":" + WireFormat.quote(found.type())
                + ",\"status\":" + WireFormat.quote(found.status().name())
                + ",\"lastSeq\":" + found.lastSeq()
                + ",\"history\":" + WireFormat.encodeHistory(store.history(workflowId)) + "}");
    }

    /** Leases the next task of a kind; waits up to {@code waitMs} for one. 204 means nothing came. */
    @PostMapping("/tasks/poll")
    ResponseEntity<String> poll(
            @RequestParam("kind") String kind,
            @RequestParam("worker") String worker,
            @RequestParam(value = "leaseMs", defaultValue = "30000") long leaseMs,
            @RequestParam(value = "waitMs", defaultValue = "0") long waitMs) throws InterruptedException {
        TaskKind taskKind;
        try {
            taskKind = TaskKind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "kind must be WORKFLOW_TASK or ACTIVITY_TASK");
        }
        if (worker.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "worker must not be blank");
        }
        Optional<PolledTask> polled = workers.poll(
                taskKind, worker, Duration.ofMillis(leaseMs), Duration.ofMillis(waitMs));
        return polled.isPresent()
                ? json(HttpStatus.OK, WireFormat.encodePolledTask(polled.get()))
                : ResponseEntity.noContent().build();
    }

    /** Commits what a replay decided. Body: a decision (see {@link WireFormat#encodeDecision}). */
    @PostMapping("/tasks/{taskId}/complete-workflow")
    ResponseEntity<String> completeWorkflow(
            @PathVariable("taskId") long taskId,
            @RequestParam("attempt") int attempt,
            @RequestParam("decidedAtSeq") long decidedAtSeq,
            @RequestBody String body) {
        Decision decision;
        try {
            decision = WireFormat.decodeDecision(body);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        Optional<ClaimedTask> task = store.findLeasedTask(taskId, attempt);
        if (task.isEmpty()) {
            return outcome(CompletionOutcome.LEASE_LOST);
        }
        if (task.get().kind() != TaskKind.WORKFLOW_TASK) {
            return error(HttpStatus.BAD_REQUEST, "task " + taskId + " is not a workflow task");
        }
        return outcome(store.completeWorkflowTask(task.get(), decidedAtSeq, decision));
    }

    /** Commits the result of an activity attempt. Body: an activity result. */
    @PostMapping("/tasks/{taskId}/complete-activity")
    ResponseEntity<String> completeActivity(
            @PathVariable("taskId") long taskId,
            @RequestParam("attempt") int attempt,
            @RequestBody String body) {
        ActivityResult result;
        try {
            result = WireFormat.decodeActivityResult(body);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        Optional<ClaimedTask> task = store.findLeasedTask(taskId, attempt);
        if (task.isEmpty()) {
            return outcome(CompletionOutcome.LEASE_LOST);
        }
        if (task.get().kind() != TaskKind.ACTIVITY_TASK) {
            return error(HttpStatus.BAD_REQUEST, "task " + taskId + " is not an activity task");
        }
        return outcome(store.completeActivityTask(task.get(), result));
    }

    /** A lost lease is 409 (the worker must drop the result); every other outcome is 200. */
    private static ResponseEntity<String> outcome(CompletionOutcome outcome) {
        HttpStatus status = outcome == CompletionOutcome.LEASE_LOST ? HttpStatus.CONFLICT : HttpStatus.OK;
        return json(status, "{\"outcome\":\"" + outcome.name() + "\"}");
    }

    private static ResponseEntity<String> error(HttpStatus status, String message) {
        return json(status, "{\"error\":" + WireFormat.quote(message == null ? status.getReasonPhrase() : message) + "}");
    }

    private static ResponseEntity<String> json(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8)).body(body);
    }
}
