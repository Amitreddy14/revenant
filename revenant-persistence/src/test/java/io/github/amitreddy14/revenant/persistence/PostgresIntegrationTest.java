package io.github.amitreddy14.revenant.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that need a real PostgreSQL.
 *
 * <p>One container is started per test JVM and shared by every subclass (the "singleton container"
 * pattern); Testcontainers removes it when the JVM exits. Docker must be running.
 */
abstract class PostgresIntegrationTest {

    static final DataSource DATA_SOURCE;

    static {
        // Same image as docker-compose.yml, so tests run against what developers run.
        PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        Flyway.configure().dataSource(dataSource).load().migrate();
        DATA_SOURCE = dataSource;
    }

    @BeforeEach
    void emptyAllTables() throws SQLException {
        // TRUNCATE is not blocked by the append-only trigger (it fires per row).
        execute("TRUNCATE workflow_execution, history_event, task, timer, outbox CASCADE");
    }

    static void execute(String sql) throws SQLException {
        try (Connection connection = DATA_SOURCE.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static long count(String sql) throws SQLException {
        try (Connection connection = DATA_SOURCE.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** Simulates workers that died: every lease is already in the past. */
    static void expireAllLeases() throws SQLException {
        execute("UPDATE task SET locked_until = now() - interval '1 second' WHERE locked_until IS NOT NULL");
    }
}
