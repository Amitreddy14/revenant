plugins {
    `java-library`
}

description = "Pure engine logic: workflow history, replay and retry policy. No framework dependencies (ADR-0007)."

// Intentionally no dependencies here. If you are about to add Spring, JDBC, Kafka or
// Redis to this module, the code belongs in another module instead.
