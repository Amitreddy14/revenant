plugins {
    java
    alias(libs.plugins.spring.boot)
}

description = "Spring Boot application: HTTP API, background scheduler and outbox relay."

dependencies {
    implementation(project(":revenant-core"))
    implementation(project(":revenant-persistence"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Database: Spring Boot creates the DataSource and runs the Flyway migrations that
    // live in revenant-persistence (src/main/resources/db/migration) at startup.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Kafka: the outbox relay publishes with the plain producer client. The version is managed
    // by Spring Boot's dependency management.
    implementation("org.apache.kafka:kafka-clients")

    // Redis: only task wake-up hints go through it (ADR-0012). Plain Lettuce client, version
    // managed by Spring Boot.
    implementation("io.lettuce:lettuce-core")

    // The end-to-end tests run a real worker from the SDK against this server.
    testImplementation(project(":revenant-worker-sdk"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}
