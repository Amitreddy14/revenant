# ADR-0011: Outbox relay and background scheduler

- Status: Accepted
- Date: 2026-11-05

## Context

ADR-0005 chose Kafka through a transactional outbox, and Phase 3 left two things that must be called regularly: the timer and timeout sweeps. Phase 4a adds the pieces that call them and that move outbox rows to Kafka. Both must be safe with several server instances running and must never lose or reorder a workflow's events.

## Decision

**The relay is one transaction.** `OutboxRelay.relayBatch(max)` does, in one database transaction: take a transaction-level advisory lock with `pg_try_advisory_xact_lock`, read the oldest unpublished rows ordered by `event_id`, send them to an `EventSink` one at a time, and mark exactly the rows that were sent as published. If the lock is taken by another instance, the batch is skipped, not queued. The lock disappears on commit or rollback, so a crashed relay cannot leave it stuck.

**Stop at the first failure.** If sending event *n* fails, events after it are not sent, and only the events before it are marked published. The next batch starts at *n*. This keeps the order of events for each workflow, which a "skip the failed one and carry on" design would break.

**Delivery is at least once.** A crash after Kafka accepted an event but before the mark commits means the event is sent again. Consumers must dedupe by `event_id`, which is sent as a header. The record key is the `workflow_id`, so Kafka keeps one workflow's events in order within a partition. The producer uses idempotence and `acks=all` and short timeouts, so a down broker makes the relay fail fast and try again on the next tick instead of hanging.

**Kafka is optional.** `revenant.kafka.enabled=false` removes the relay completely. The engine itself never reads from Kafka, so losing it loses nothing (ADR-0003, ADR-0005).

**The scheduler pulls the sweeps.** `SweepScheduler` calls `fireDueTimers` and `timeOutExpiredActivities` every second. `RelayScheduler` calls the relay every half second and keeps draining while batches come back full. Both catch and log exceptions, so one bad tick never stops the loop. Intervals and batch sizes are properties under `revenant.*`. Several instances may run the sweeps at once, because both sweeps already use `FOR UPDATE SKIP LOCKED`.

## Consequences

- Event latency to Kafka is bounded by the relay interval (about half a second), not by a database trigger. Phase 4b adds Redis only as a way to wake things sooner; polling stays as the fallback.
- A poison event (one Kafka permanently rejects) blocks the relay behind it. This is the price of strict ordering. A dead-letter table is a later concern.
- The advisory lock key is a fixed constant, so two Revenant databases never conflict (locks are per database) but two relays on the same database always do, by design.
- Unit tests for the Kafka sender use Kafka's `MockProducer`. Real broker behaviour is checked by hand in the README.
