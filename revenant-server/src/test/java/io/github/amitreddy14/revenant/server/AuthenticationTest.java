package io.github.amitreddy14.revenant.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amitreddy14.revenant.core.WorkflowId;
import io.github.amitreddy14.revenant.sdk.RevenantApiException;
import io.github.amitreddy14.revenant.sdk.RevenantClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The real server with API keys switched on (ADR-0014), called over HTTP: the API refuses requests
 * without a valid key, accepts either of two configured keys, and leaves the health probe open.
 * Docker must be running.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"revenant.kafka.enabled=false", "revenant.scheduler.enabled=false",
                "revenant.redis.enabled=false",
                "revenant.security.api-keys=first-test-key-0123456789,second-test-key-0123456789",
                "revenant.security.required=true"})
class AuthenticationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private int status(String method, String path, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void theApiRefusesARequestWithoutAKey() throws Exception {
        assertThat(status("GET", "/api/v1/workflows/" + WorkflowId.random(), null)).isEqualTo(401);
        assertThat(status("POST", "/api/v1/workflows?type=X", null)).isEqualTo(401);
        assertThat(status("POST", "/api/v1/tasks/poll?kind=ACTIVITY_TASK&worker=w", null)).isEqualTo(401);
    }

    @Test
    void theApiRefusesAWrongKey() throws Exception {
        assertThat(status("GET", "/api/v1/workflows/" + WorkflowId.random(), "Bearer not-the-right-key-000000"))
                .isEqualTo(401);
    }

    @Test
    void aValidKeyGetsThroughToTheController() throws Exception {
        // 404 (no such workflow) proves the request reached the controller; 401 would mean it did not.
        assertThat(status("GET", "/api/v1/workflows/" + WorkflowId.random(), "Bearer first-test-key-0123456789"))
                .isEqualTo(404);
        assertThat(status("GET", "/api/v1/workflows/" + WorkflowId.random(), "Bearer second-test-key-0123456789"))
                .isEqualTo(404);
    }

    @Test
    void theHealthProbeStaysOpenWithoutAKey() throws Exception {
        assertThat(status("GET", "/actuator/health", null)).isEqualTo(200);
    }

    @Test
    void theSdkClientWorksWithAKeyAndIsRefusedWithout() {
        String url = "http://localhost:" + port;

        WorkflowId id = new RevenantClient(url, "second-test-key-0123456789").startWorkflow("AuthFlow", "{}");
        assertThat(new RevenantClient(url, "second-test-key-0123456789").getWorkflow(id)).isPresent();

        assertThatThrownBy(() -> new RevenantClient(url).startWorkflow("AuthFlow", "{}"))
                .isInstanceOf(RevenantApiException.class)
                .hasMessageContaining("401");
    }
}
