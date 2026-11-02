# ADR-0004: Redis is an accelerator, never state of record

- Status: Accepted
- Date: 2026-11-03

## Context

Workers that poll PostgreSQL in a tight loop waste load and add latency. Redis is good at fast notifications and counters. But Redis can lose data on restart or failover, and it cannot join a PostgreSQL transaction.

## Decision

Use Redis only for things whose loss costs speed, not correctness: wake-up notifications for idle workers, rate limiting, and short-lived caches. Any information needed for correctness (tasks, leases, history) lives in PostgreSQL. If Redis is unavailable, workers fall back to polling PostgreSQL.

## Consequences

- Losing or flushing Redis makes the system slower, never wrong.
- Every Redis-assisted path needs a PostgreSQL fallback, which we test in Phase 4 by stopping the Redis container.
- Redis runs with `noeviction` so it fails loudly under memory pressure instead of silently dropping keys.
