# Architecture Decision Records

Each ADR captures one significant decision: the context, what was decided, and the consequences. They are short on purpose. To change a decision, add a new ADR that supersedes the old one instead of editing history.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-custom-replay-based-core.md) | Build a custom replay-based durable execution core | Accepted |
| [0003](0003-postgresql-source-of-truth.md) | PostgreSQL is the single source of truth | Accepted |
| [0004](0004-redis-as-accelerator.md) | Redis is an accelerator, never state of record | Accepted |
| [0005](0005-kafka-via-transactional-outbox.md) | Kafka event stream via the transactional outbox | Accepted |
| [0006](0006-java-spring-boot-java-only-sdk.md) | Java 21, Spring Boot 4, Java-only worker SDK | Accepted |
| [0007](0007-gradle-multi-module-framework-free-core.md) | Gradle multi-module layout with a framework-free core | Accepted |
| [0008](0008-plain-jdbc-spring-free-persistence.md) | Plain JDBC with hand-written SQL in a Spring-free persistence module | Accepted |
| [0009](0009-replay-by-rerunning-workflow-code.md) | Replay by re-running workflow code from the top | Accepted |
