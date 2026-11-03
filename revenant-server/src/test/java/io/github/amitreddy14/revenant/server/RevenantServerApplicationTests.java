package io.github.amitreddy14.revenant.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amitreddy14.revenant.core.WorkflowId;
import io.github.amitreddy14.revenant.persistence.WorkflowStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the whole application against a real PostgreSQL. Spring Boot runs the Flyway migrations
 * on startup, so a passing test proves the schema applies and the store works end to end.
 * Docker must be running.
 */
@Testcontainers
@SpringBootTest
class RevenantServerApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    WorkflowStore store;

    @Test
    void contextLoadsAndTheStoreWorksAgainstTheMigratedSchema() {
        WorkflowId id = WorkflowId.random();

        store.start(id, "SmokeTestFlow", "{}");

        assertThat(store.history(id)).hasSize(1);
    }
}
