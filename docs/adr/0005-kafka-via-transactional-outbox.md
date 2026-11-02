# ADR-0005: Kafka event stream via the transactional outbox

- Status: Accepted
- Date: 2026-11-03

## Context

Other systems want to react to workflow events (started, activity completed, failed). Publishing to Kafka directly from the code that writes history is a **dual write**: if the process dies between the database commit and the Kafka send, the event is lost or the database and stream disagree.

## Decision

Write each outgoing event to an `outbox` table in the **same transaction** as the history append. A separate relay reads unpublished rows and sends them to Kafka, then marks them published. Messages use `workflow_id` as the key so one workflow's events stay ordered. Internal task dispatch does **not** use Kafka; it stays in PostgreSQL (ADR-0003).

## Consequences

- No lost events: if Kafka is down, rows wait in the outbox.
- Delivery to Kafka is at least once, so consumers deduplicate by `event_id`.
- Kafka is optional for the engine to function, which keeps it off the critical path.
- Local Kafka data is deliberately ephemeral; the outbox can re-publish what is missing.
