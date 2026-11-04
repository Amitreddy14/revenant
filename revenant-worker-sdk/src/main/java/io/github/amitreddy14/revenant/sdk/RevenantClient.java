package io.github.amitreddy14.revenant.sdk;

import io.github.amitreddy14.revenant.core.ActivityResult;
import io.github.amitreddy14.revenant.core.ClaimedTask;
import io.github.amitreddy14.revenant.core.Decision;
import io.github.amitreddy14.revenant.core.HistoryEvent;
import io.github.amitreddy14.revenant.core.JsonNode;
import io.github.amitreddy14.revenant.core.PolledTask;
import io.github.amitreddy14.revenant.core.TaskKind;
import io.github.amitreddy14.revenant.core.WireFormat;
import io.github.amitreddy14.revenant.core.WorkflowId;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A thin HTTP client for the Revenant server's API (ADR-0013). Thread-safe. */
public final class RevenantClient {

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** @param baseUrl for example {@code http://localhost:8080} */
    public RevenantClient(String baseUrl) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
    }

    /** Starts a workflow with a new random id. */
    public WorkflowId startWorkflow(String type, String inputJson) {
        return startWorkflow(null, type, inputJson);
    }

    /**
     * Starts a workflow with the given id. Starting an id twice is refused with status 409, which
     * makes a retried start safe.
     */
    public WorkflowId startWorkflow(WorkflowId id, String type, String inputJson) {
        String query = "type=" + enc(type) + (id == null ? "" : "&id=" + enc(id.toString()));
        HttpResponse<String> response = send(request("/api/v1/workflows?" + query, Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(inputJson, StandardCharsets.UTF_8)).build());
        expect(response, 201);
        return WorkflowId.parse(JsonNode.parse(response.body()).required("workflowId").asText());
    }

    public Optional<WorkflowDetails> getWorkflow(WorkflowId id) {
        HttpResponse<String> response = send(request("/api/v1/workflows/" + id, Duration.ofSeconds(30)).GET().build());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        expect(response, 200);
        JsonNode node = JsonNode.parse(response.body());
        List<HistoryEvent> history = new ArrayList<>();
        for (JsonNode event : node.required("history").items()) {
            history.add(new HistoryEvent(
                    event.required("seq").asLong(),
                    event.required("type").asText(),
                    event.optLong("commandId"),
                    event.optText("name"),
                    event.required("payload").raw(),
                    Instant.parse(event.required("createdAt").asText())));
        }
        return Optional.of(new WorkflowDetails(
                WorkflowId.parse(node.required("workflowId").asText()),
                node.required("type").asText(),
                node.required("status").asText(),
                history));
    }

    /**
     * Asks for a task of the given kind, waiting up to {@code maxWait} for one to appear.
     *
     * @param lease how long this worker may hold the task before the server hands it to someone else
     */
    public Optional<PolledTask> poll(TaskKind kind, String workerId, Duration lease, Duration maxWait) {
        String query = "kind=" + kind.name() + "&worker=" + enc(workerId) + "&leaseMs=" + lease.toMillis()
                + "&waitMs=" + maxWait.toMillis();
        HttpResponse<String> response = send(request("/api/v1/tasks/poll?" + query, maxWait.plusSeconds(15))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        if (response.statusCode() == 204) {
            return Optional.empty();
        }
        expect(response, 200);
        return Optional.of(WireFormat.decodePolledTask(response.body()));
    }

    /**
     * Reports what a replay decided.
     *
     * @param decidedAtSeq the sequence number of the newest event in the history the decision used
     */
    public TaskOutcome completeWorkflowTask(ClaimedTask task, long decidedAtSeq, Decision decision) {
        String path = "/api/v1/tasks/" + task.taskId() + "/complete-workflow?attempt=" + task.attempt()
                + "&decidedAtSeq=" + decidedAtSeq;
        return outcome(send(request(path, Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(WireFormat.encodeDecision(decision), StandardCharsets.UTF_8))
                .build()));
    }

    /** Reports the result of one activity attempt. */
    public TaskOutcome completeActivityTask(ClaimedTask task, ActivityResult result) {
        String path = "/api/v1/tasks/" + task.taskId() + "/complete-activity?attempt=" + task.attempt();
        return outcome(send(request(path, Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(
                        WireFormat.encodeActivityResult(result), StandardCharsets.UTF_8))
                .build()));
    }

    private TaskOutcome outcome(HttpResponse<String> response) {
        if (response.statusCode() == 409) {
            return TaskOutcome.LEASE_LOST;
        }
        expect(response, 200);
        return TaskOutcome.valueOf(JsonNode.parse(response.body()).required("outcome").asText());
    }

    private HttpRequest.Builder request(String pathAndQuery, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(base + pathAndQuery))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=UTF-8");
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedServerException("cannot reach the Revenant server at " + base, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedServerException("interrupted while talking to the Revenant server", e);
        }
    }

    private static void expect(HttpResponse<String> response, int status) {
        if (response.statusCode() != status) {
            throw new RevenantApiException(response.statusCode(), response.body());
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The server could not be reached or the call was interrupted. */
    public static final class UncheckedServerException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UncheckedServerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
