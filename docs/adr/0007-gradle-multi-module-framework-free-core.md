# ADR-0007: Gradle multi-module layout with a framework-free core

- Status: Accepted
- Date: 2026-11-03

## Context

Replay logic is the hardest and most important code in the project. It must be easy to test quickly and must not depend on how data is stored or how requests arrive.

## Decision

Use a Gradle multi-module build:

- `revenant-core`: history, replay, retry policy. Plain Java, **no dependencies** other than test libraries.
- `revenant-persistence`: PostgreSQL storage, depends on core.
- `revenant-worker-sdk`: what workflow authors import, depends on core.
- `revenant-server`: Spring Boot application, depends on core and persistence.

Versions live in one place (`gradle/libs.versions.toml`), and Spring Boot's BOM pins the rest.

## Consequences

- Core tests run in milliseconds with no database, containers or Spring context.
- The module boundary stops framework types from leaking into the engine's logic.
- Slightly more build configuration than a single module, paid once.
