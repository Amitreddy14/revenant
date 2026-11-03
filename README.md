# Revenant

> A crash-proof workflow engine for long-running processes. Kill a worker mid-flight, restart it, and every workflow resumes from the exact step where it stopped.

[![CI](https://github.com/Amitreddy14/revenant/actions/workflows/ci.yml/badge.svg)](https://github.com/Amitreddy14/revenant/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-brightgreen)
![License: MIT](https://img.shields.io/badge/License-MIT-yellow)

**Status: Phase 1 (storage).** The PostgreSQL schema, migrations and a tested storage layer are in place; the replay engine itself is built next (see the [roadmap](#roadmap)).

## Why it exists

Business processes such as order fulfilment, payment recovery and onboarding can last minutes to days and depend on unreliable systems. Normal code keeps its place only in memory, so a crash or a deploy loses it.

Revenant records every step of a workflow as an append-only history. After any failure, a worker re-runs the workflow code and the SDK answers already-completed steps from the history instead of doing them again, so execution continues at the first step that never finished. This is *durable execution*, the same idea behind Temporal and Azure Durable Functions, implemented from scratch here to show how it works.

## How it fits together

```mermaid
flowchart LR
    Client["Client<br/>(REST API)"] -->|start, signal, query| Server
    Worker["Worker<br/>(your code + SDK)"] -->|poll tasks, report results| Server
    Server["Revenant Server<br/>(Spring Boot, stateless)"] -->|history, tasks, timers, outbox| PG[("PostgreSQL<br/>source of truth")]
    Server -.->|wake-ups, rate limits| Redis[("Redis<br/>accelerator")]
    Server -->|outbox relay| Kafka[["Kafka<br/>event stream"]]
```

PostgreSQL holds all state that matters. Redis only makes things faster and Kafka only publishes events outward, so losing either never loses a workflow. Details are in [docs/design.md](docs/design.md).

## Quick start (Windows PowerShell)

Requirements: JDK 21, Docker Desktop (it must be running, the tests start their own PostgreSQL), Git.

```powershell
# 1. Start PostgreSQL, Redis and Kafka
docker compose up -d
docker compose ps            # wait until all three show "healthy"

# 2. Build and run the tests (starts a throwaway PostgreSQL container)
.\gradlew.bat build

# 3. Start the server (applies the database migrations on startup)
.\gradlew.bat :revenant-server:bootRun

# 4. In a second terminal, check it is alive
curl.exe http://localhost:8080/actuator/health
```

Expected health response: `{"status":"UP"}` (the exact JSON may include more fields).

Stop everything with `docker compose down` (add `-v` to also delete the data).

## Repository layout

```
revenant/
├── revenant-core/          pure engine logic: history, replay, retry policy (no frameworks)
├── revenant-persistence/   SQL migrations, JDBC repositories, transactions (no Spring)
├── revenant-worker-sdk/    the library workflow authors depend on
├── revenant-server/        Spring Boot application
├── docs/
│   ├── design.md           architecture, failure model, semantics
│   └── adr/                architecture decision records
├── docker-compose.yml      PostgreSQL, Redis, Kafka for local development
└── .github/workflows/      CI
```

## Roadmap

| Phase | Deliverable | Status |
|---|---|---|
| 0 | Design doc, ADRs, Gradle scaffold, Docker Compose, CI | Done |
| 1 | PostgreSQL schema, Flyway migrations, Testcontainers | Done |
| 2 | Event-sourced history and the replay core | Next |
| 3 | Retries, timers, timeouts, failure handling | Planned |
| 4 | Redis task wake-ups and Kafka event stream | Planned |
| 5 | Worker SDK and a demo workflow that survives `kill -9` | Planned |
| 6 | Free deployment, documentation, demo | Planned |

## Contributing and Git workflow

Short-lived branches named `phase-N-topic`, Conventional Commit messages, pull requests into `main`, and a tag at the end of each phase. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT. See [LICENSE](LICENSE).
