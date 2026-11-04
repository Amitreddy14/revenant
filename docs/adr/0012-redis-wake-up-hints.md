# ADR-0012: Redis wake-up hints

- Status: Accepted
- Date: 2026-11-05

## Context

Workers find work by polling PostgreSQL (`claimTask`). Polling is correct but slow to notice: a task queued just after a poll waits for the next one. ADR-0004 allows Redis as an accelerator as long as it is never the state of record. Phase 4b adds the accelerator.

## Decision

**A hint after commit.** `WorkflowStore` takes an optional `TaskNotifier`. After a transaction that may have made tasks ready has committed, the store calls `tasksMayBeReady(kind)`:

| Operation | Hint |
|---|---|
| `start` | workflow tasks |
| `completeWorkflowTask` that scheduled activities | activity tasks |
| `completeActivityTask` applied (not a retry) | workflow tasks |
| `fireDueTimers` / `timeOutExpiredActivities` that did something | workflow tasks (and activity tasks for timeouts, since a retry may be ready) |

The call happens after the commit, so a hint never announces work that does not exist yet. Exceptions from the notifier are swallowed: the data is already safe. A retry waiting for its backoff sends no hint; the poll timeout picks it up.

**Redis pub/sub carries the hint between server instances.** `RedisWakeUps` publishes to `revenant:wake:<KIND>` and subscribes to the same channels. Pub/sub is fire-and-forget on purpose: a missed message costs only speed. Nothing is stored in Redis, so there is nothing to lose or to repair.

**Pollers wait on `WakeUpSignals`, with a timeout.** `WakeUpSignals.await(kind, maxWait)` returns early on a signal and otherwise returns when the timeout is up. The caller then queries PostgreSQL either way. The timeout, not the signal, is the guarantee. Several signals count as one look.

**Redis being down is normal.** The server starts without Redis; a background thread keeps trying to connect. While it is not connected, hints are signalled in this instance only and everything else polls. `revenant.redis.enabled=false` switches Redis off completely.

## Consequences

- Latency to notice new work drops from "up to one poll interval" to a few milliseconds when Redis works, and stays at one poll interval when it does not.
- Duplicate and spurious wake-ups are possible (a server also hears its own hints). A woken poller that finds nothing just waits again.
- The HTTP endpoints that workers poll are built in Phase 5; they will call `WakeUpSignals.await`. Until then the signals are produced but nothing waits on them.
- Redis stays out of correctness: lease, fencing and ordering still come only from PostgreSQL (ADR-0003, ADR-0009).
