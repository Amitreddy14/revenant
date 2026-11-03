plugins {
    `java-library`
}

description = "PostgreSQL storage: SQL migrations, repositories and transactions (Phase 1)."

dependencies {
    api(project(":revenant-core"))

    // No runtime dependencies on purpose: the repositories use plain JDBC (ADR-0008).
    // The migrations in src/main/resources/db/migration are applied by Flyway, which the
    // server gets from Spring Boot; here Flyway is only needed to prepare the test database.
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}
