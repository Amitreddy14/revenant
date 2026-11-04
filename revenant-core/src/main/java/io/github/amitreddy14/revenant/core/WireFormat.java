package io.github.amitreddy14.revenant.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The JSON messages between the server and workers (ADR-0013). One class holds both directions so
 * the two sides cannot drift apart, and a round-trip test pins each message.
 *
 * <p>Payloads (inputs, results, errors) are embedded as raw JSON and come back unchanged.
 * Decoding validates the shape and throws {@link IllegalArgumentException} on anything else.
 */
public final class WireFormat {

    private WireFormat() {}

    /** Text as a JSON string literal, for building small messages such as errors. */
    public static String quote(String text) {
        return JsonText.quote(text);
    }

    // ---- server -> worker ----

    /** The response to a poll that found a task. */
    public static String encodePolledTask(PolledTask polled) {
        ClaimedTask task = polled.task();
        StringBuilder out = new StringBuilder("{");
        out.append("\"taskId\":").append(task.taskId());
        out.append(",\"attempt\":").append(task.attempt());
        out.append(",\"workflowId\":").append(JsonText.quote(task.workflowId().toString()));
        out.append(",\"kind\":").append(JsonText.quote(task.kind().name()));
        if (task.commandId() != null) {
            out.append(",\"commandId\":").append(task.commandId());
        }
        if (task.name() != null) {
            out.append(",\"name\":").append(JsonText.quote(task.name()));
        }
        out.append(",\"payload\":").append(task.payloadJson());
        if (polled.workflowType() != null) {
            out.append(",\"workflowType\":").append(JsonText.quote(polled.workflowType()));
        }
        if (task.kind() == TaskKind.WORKFLOW_TASK) {
            out.append(",\"history\":").append(encodeHistory(polled.history()));
        }
        return out.append('}').toString();
    }

    public static PolledTask decodePolledTask(String json) {
        JsonNode node = JsonNode.parse(json);
        ClaimedTask task = new ClaimedTask(
                node.required("taskId").asLong(),
                WorkflowId.parse(node.required("workflowId").asText()),
                taskKind(node.required("kind").asText()),
                node.optLong("commandId"),
                node.optText("name"),
                node.required("payload").raw(),
                (int) node.required("attempt").asLong());
        List<HistoryEvent> history = new ArrayList<>();
        JsonNode events = node.field("history");
        if (events != null) {
            for (JsonNode event : events.items()) {
                history.add(decodeEvent(event));
            }
        }
        return new PolledTask(task, node.optText("workflowType"), history);
    }

    /** A history as a JSON array, oldest event first. */
    public static String encodeHistory(List<HistoryEvent> history) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(encodeEvent(history.get(i)));
        }
        return out.append(']').toString();
    }

    public static String encodeEvent(HistoryEvent event) {
        StringBuilder out = new StringBuilder("{");
        out.append("\"seq\":").append(event.seq());
        out.append(",\"type\":").append(JsonText.quote(event.type()));
        if (event.commandId() != null) {
            out.append(",\"commandId\":").append(event.commandId());
        }
        if (event.name() != null) {
            out.append(",\"name\":").append(JsonText.quote(event.name()));
        }
        out.append(",\"payload\":").append(event.payloadJson());
        out.append(",\"createdAt\":").append(JsonText.quote(event.createdAt().toString()));
        return out.append('}').toString();
    }

    private static HistoryEvent decodeEvent(JsonNode node) {
        return new HistoryEvent(
                node.required("seq").asLong(),
                node.required("type").asText(),
                node.optLong("commandId"),
                node.optText("name"),
                node.required("payload").raw(),
                Instant.parse(node.required("createdAt").asText()));
    }

    // ---- worker -> server ----

    /** The body of "complete this workflow task": what the replay decided. */
    public static String encodeDecision(Decision decision) {
        StringBuilder out = new StringBuilder("{\"commands\":[");
        boolean first = true;
        for (Command command : decision.commands()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            switch (command) {
                case Command.ScheduleActivity c -> {
                    RetryPolicy retry = c.options().retryPolicy();
                    out.append("{\"type\":\"ScheduleActivity\",\"commandId\":").append(c.commandId());
                    out.append(",\"name\":").append(JsonText.quote(c.name()));
                    out.append(",\"input\":").append(c.inputJson());
                    out.append(",\"maxAttempts\":").append(retry.maxAttempts());
                    out.append(",\"initialIntervalMs\":").append(retry.initialInterval().toMillis());
                    out.append(",\"backoffCoefficient\":").append(retry.backoffCoefficient());
                    out.append(",\"maxIntervalMs\":").append(retry.maxInterval().toMillis());
                    Duration timeout = c.options().startToCloseTimeout();
                    out.append(",\"startToCloseMs\":").append(timeout == null ? "null" : timeout.toMillis());
                    out.append('}');
                }
                case Command.StartTimer c -> out.append("{\"type\":\"StartTimer\",\"commandId\":")
                        .append(c.commandId()).append(",\"durationMs\":").append(c.durationMillis()).append('}');
                case Command.CompleteWorkflow c ->
                    out.append("{\"type\":\"CompleteWorkflow\",\"result\":").append(c.resultJson()).append('}');
                case Command.FailWorkflow c ->
                    out.append("{\"type\":\"FailWorkflow\",\"error\":").append(c.errorJson()).append('}');
            }
        }
        return out.append("]}").toString();
    }

    public static Decision decodeDecision(String json) {
        List<Command> commands = new ArrayList<>();
        for (JsonNode node : JsonNode.parse(json).required("commands").items()) {
            String type = node.required("type").asText();
            commands.add(switch (type) {
                case "ScheduleActivity" -> {
                    RetryPolicy retry = new RetryPolicy(
                            (int) node.required("maxAttempts").asLong(),
                            Duration.ofMillis(node.required("initialIntervalMs").asLong()),
                            node.required("backoffCoefficient").asDouble(),
                            Duration.ofMillis(node.required("maxIntervalMs").asLong()));
                    Long timeoutMs = node.optLong("startToCloseMs");
                    ActivityOptions options = new ActivityOptions(
                            retry, timeoutMs == null ? null : Duration.ofMillis(timeoutMs));
                    yield new Command.ScheduleActivity(
                            node.required("commandId").asLong(),
                            node.required("name").asText(),
                            node.required("input").raw(),
                            options);
                }
                case "StartTimer" -> new Command.StartTimer(
                        node.required("commandId").asLong(), node.required("durationMs").asLong());
                case "CompleteWorkflow" -> new Command.CompleteWorkflow(node.required("result").raw());
                case "FailWorkflow" -> new Command.FailWorkflow(node.required("error").raw());
                default -> throw new IllegalArgumentException("unknown command type \"" + type + "\"");
            });
        }
        return new Decision(commands);
    }

    /** The body of "complete this activity task". */
    public static String encodeActivityResult(ActivityResult result) {
        return "{\"succeeded\":" + result.succeeded() + ",\"retryable\":" + result.retryable()
                + ",\"payload\":" + result.payloadJson() + "}";
    }

    public static ActivityResult decodeActivityResult(String json) {
        JsonNode node = JsonNode.parse(json);
        boolean succeeded = node.required("succeeded").asBoolean();
        JsonNode retryable = node.field("retryable");
        boolean canRetry = retryable == null || retryable.isNull() ? !succeeded : retryable.asBoolean();
        return new ActivityResult(succeeded, node.required("payload").raw(), canRetry);
    }

    private static TaskKind taskKind(String text) {
        try {
            return TaskKind.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown task kind \"" + text + "\"");
        }
    }
}
