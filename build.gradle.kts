plugins {
    // Declared here so every module sees the same plugin version; applied only where needed.
    alias(libs.plugins.spring.boot) apply false
}

val springBootVersion = libs.versions.spring.boot.get()

allprojects {
    group = "io.github.amitreddy14.revenant"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    // Shared conventions, applied to every module that uses the Java plugin.
    pluginManager.withPlugin("java") {
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(21)
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
        }

        dependencies {
            // Spring Boot's BOM pins consistent versions for ALL modules, including
            // framework-free ones like revenant-core (which still needs JUnit/AssertJ for tests).
            add("implementation", platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))

            add("testImplementation", "org.junit.jupiter:junit-jupiter")
            add("testImplementation", "org.assertj:assertj-core")
            add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "skipped", "failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}
