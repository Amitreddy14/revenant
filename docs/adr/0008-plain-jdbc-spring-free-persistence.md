# ADR-0008: Plain JDBC with hand-written SQL in a Spring-free persistence module

- Status: Accepted
- Date: 2026-11-03

## Context

The storage layer must give precise control over transactions and over a few unusual statements: an optimistic `UPDATE ... WHERE last_seq = ?`, and a queue claim built on `FOR UPDATE SKIP LOCKED` with `RETURNING`. It must also stay usable from the worker side and from tests without starting Spring.

## Decision

- `revenant-persistence` uses plain JDBC (`java.sql`, `javax.sql.DataSource`) and has no runtime dependencies besides `revenant-core`.
- SQL is written by hand as named constants in each repository, so every statement can be read, reviewed and run directly against PostgreSQL.
- Repositories take a `Connection`. A small `Transactions` helper (commit on success, roll back on any exception) lets several repositories share one transaction.
- Schema changes are SQL migrations applied by Flyway. The server gets Flyway from Spring Boot; the persistence module only ships the SQL files.
- Integration tests run against a real PostgreSQL started by Testcontainers, never against an in-memory substitute.

## Consequences

- The queue and locking behaviour is exactly the SQL we wrote, with no ORM in between.
- Persistence tests start in a plain JUnit run, with no Spring context.
- More boilerplate than an ORM or `JdbcClient`; accepted because the module is small and the statements are few.
- PostgreSQL-specific SQL (`SKIP LOCKED`, `JSONB`, partial indexes) is used freely. Portability to other databases is a non-goal.
- Testing against a real database means Docker must be running for `./gradlew build`.
