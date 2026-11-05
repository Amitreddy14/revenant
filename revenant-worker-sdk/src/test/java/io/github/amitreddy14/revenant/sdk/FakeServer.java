package io.github.amitreddy14.revenant.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A scripted stand-in for the Revenant server, so the SDK can be tested without a database. */
final class FakeServer implements AutoCloseable {

    record Request(String method, String path, String query, String body, String authorization) {}

    private final HttpServer http;
    private final ConcurrentLinkedQueue<String> workflowTasks = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> activityTasks = new ConcurrentLinkedQueue<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Request, int[]> statusFor = request -> new int[] {200};
    private volatile String completionBody = "{\"outcome\":\"APPLIED\"}";

    FakeServer() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", this::handle);
        http.start();
    }

    String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    void offerWorkflowTask(String json) {
        workflowTasks.add(json);
    }

    void offerActivityTask(String json) {
        activityTasks.add(json);
    }

    /** Status code the server answers to completion calls (default 200 with outcome APPLIED). */
    void answerCompletionsWith(int status, String body) {
        statusFor = request -> new int[] {status};
        completionBody = body;
    }

    List<Request> requests() {
        return requests;
    }

    List<Request> completions() {
        return requests.stream().filter(r -> r.path().contains("/complete-")).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), body,
                exchange.getRequestHeaders().getFirst("Authorization"));
        requests.add(request);
        String path = request.path();
        int status;
        String response = "";
        if (path.equals("/api/v1/tasks/poll")) {
            String task = request.query().contains("kind=WORKFLOW_TASK") ? workflowTasks.poll() : activityTasks.poll();
            if (task == null) {
                sleep(30);
                status = 204;
            } else {
                status = 200;
                response = task;
            }
        } else if (path.contains("/complete-")) {
            status = statusFor.apply(request)[0];
            response = completionBody;
        } else if (path.equals("/api/v1/workflows") && request.method().equals("POST")) {
            status = 201;
            response = "{\"workflowId\":\"0d6a3f0e-1c7e-4a52-9a52-2d3d6c1f6b11\"}";
        } else {
            status = 404;
            response = "{\"error\":\"not found\"}";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (status == 204) {
            exchange.sendResponseHeaders(204, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        http.stop(0);
    }
}
