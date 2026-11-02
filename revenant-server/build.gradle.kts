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

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
