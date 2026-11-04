package io.github.amitreddy14.revenant.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amitreddy14.revenant.core.WorkflowId;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class SchemaMigrationTest extends PostgresIntegrationTest {

    @Test
    void migrationCreatesAllCoreTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection connection = DATA_SOURCE.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }

        assertThat(tables)
                .containsExactlyInAnyOrder("workflow_execution", "history_event", "task", "timer", "outbox");
    }

    @Test
    void migratingAgainChangesNothing() {
        int executed = Flyway.configure().dataSource(DATA_SOURCE).load().migrate().migrationsExecuted;

        assertThat(executed).isZero();
    }

    @Test
    void historyCanNeitherBeUpdatedNorDeleted() {
        new WorkflowStore(DATA_SOURCE).start(WorkflowId.random(), "OrderFlow", "{}");

        assertThatThrownBy(() -> execute("UPDATE history_event SET event_type = 'Tampered'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM history_event")).hasMessageContaining("append-only");
    }
}
