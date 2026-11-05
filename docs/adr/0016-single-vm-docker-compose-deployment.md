# ADR-0016: Single-VM deployment with Docker Compose and Caddy

- Status: Accepted
- Date: 2026-11-05

## Context

Revenant should be runnable by someone who is not on the author's laptop, at no cost, with the security of ADR-0014 in force. The target is a free Oracle Cloud Always Free VM.

## Decision

- **One VM, Docker Compose.** PostgreSQL, Redis, the server and a reverse proxy run as containers from `deploy/docker-compose.prod.yml`. Compose is the smallest tool that gives restarts, health-ordered startup and one-command updates. Kubernetes would be far more machinery than one machine justifies.
- **Multi-stage Dockerfile, multi-architecture.** Gradle and the JDK exist only in the build stage; the images contain a JRE and the jars. Oracle's free ARM machines and ordinary x86 machines both work from the same file. The server runs as a non-root user.
- **Caddy terminates TLS.** It obtains and renews certificates by itself, which removes a whole class of manual work, and it exposes only `/api/*` and `/actuator/health`. This is also what makes the bearer keys of ADR-0014 safe to send. Only ports 80 and 443 are published; PostgreSQL and Redis are reachable only inside the Compose network.
- **Fail closed.** The compose file sets `REVENANT_SECURITY_REQUIRED=true` and refuses to start without `POSTGRES_PASSWORD`, `REVENANT_API_KEY` and `REVENANT_DOMAIN`. A deployment cannot come up open or with a default password by omission.
- **Kafka is optional.** It is behind a Compose profile because it needs about 1 GB of RAM. With it off, the outbox keeps the events (ADR-0005) and nothing is lost; turning it on later publishes the backlog in order.
- **The demo worker is a separate profile with `restart: "no"`.** A killed worker must stay dead until the demo starts another one.

## Consequences

- No high availability: one VM, one database. Backups are manual `pg_dump`.
- Secrets are environment variables in a git-ignored `.env` file.
- CI builds both images and validates the Compose file, so the files cannot rot silently; a real deployment still has to be run once by hand.
