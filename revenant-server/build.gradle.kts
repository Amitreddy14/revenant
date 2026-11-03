plugins {
    java
    alias(libs.plugins.spring.boot)
}

description = "Spring Boot application: HTTP API, task dispatcher and timer service."

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

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}
