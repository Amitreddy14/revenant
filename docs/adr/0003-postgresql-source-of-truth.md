# ADR-0003: PostgreSQL is the single source of truth

- Status: Accepted
- Date: 2026-11-03

## Context

Appending a history event, enqueueing the next task, and writing an outbox row must succeed or fail **together**. If they live in different systems, a crash between writes leaves the workflow inconsistent.

## Decision

Store history, task queue, timers, workflow metadata and the outbox in PostgreSQL. The queue uses `SELECT ... FOR UPDATE SKIP LOCKED` with a lease column. All state changes for one step happen in one transaction.

## Consequences

- Atomicity for free: one ACID transaction per step.
- One system to back up and reason about during failures.
- Queue throughput is bounded by PostgreSQL (thousands of tasks per second is realistic; millions is not). Acceptable for the stated target.
- Needs care with table bloat on the task table (frequent updates and deletes); handled with indexes and routine vacuuming, revisited in Phase 3.
