package io.github.amitreddy14.revenant.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Runs a unit of work in one database transaction: commit if it returns, roll back if it throws.
 *
 * <p>This is how Revenant makes "append history + enqueue task + write outbox row" all-or-nothing
 * (ADR-0003). Repositories take a {@link Connection} so several of them can share one transaction.
 */
public final class Transactions {

    /** Work that returns a result. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Work that returns nothing. */
    @FunctionalInterface
    public interface VoidWork {
        void run(Connection connection) throws SQLException;
    }

    private final DataSource dataSource;

    public Transactions(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Runs {@code work} in a transaction and returns its result.
     *
     * @throws PersistenceException if the database fails; runtime exceptions from {@code work}
     *                              propagate unchanged after the rollback
     */
    public <T> T execute(Work<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection, e);
                if (e instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new PersistenceException("Transaction failed and was rolled back", e);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not obtain or close a database connection", e);
        }
    }

    /** Like {@link #execute(Work)} for work without a result. */
    public void executeVoid(VoidWork work) {
        execute(connection -> {
            work.run(connection);
            return null;
        });
    }

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
