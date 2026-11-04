# ADR-0010: Retries, timers and timeouts

- Status: Accepted
- Date: 2026-11-05

## Context

Phase 2 treats every reported activity failure as final and has no way to wait. Real processes need three more things: try a flaky call again after a pause, wait for days without holding a thread, and give up on a call that hangs. Each has to survive a crash like everything else (ADR-0003, ADR-0009).

## Decision

**Retries live on the task row, not in the history.** A workflow's call may carry a `RetryPolicy` (maximum attempts including the first, initial interval, backoff coefficient, maximum interval) through `ActivityOptions`. The policy is copied onto the activity task. When an attempt fails and attempts remain, the store counts the failure, clears the lease and sets `visible_at` to the backoff. Nothing is appended to the history. Only the **final** outcome becomes `ActivityCompleted` or `ActivityFailed`, so a flapping activity does not bloat the history and the workflow code never sees individual attempts. The wait before retry *n* is `initial * coefficient^(n-1)`, capped at the maximum interval. The default policy is one attempt, which keeps Phase 2 behaviour for plain `callActivity(name, input)`.

**Two counters, two meanings.** `attempt` counts every claim and fences out stale workers (unchanged). The new `failures` column counts attempts that reported failure or timed out. A worker that merely crashed adds to `attempt` but not to `failures`, so a crash never uses up a retry. A late report from a worker whose task was put back for a retry is rejected: `complete` and `lockForFailure` both require the task to be currently leased.

**A failure can be marked non-retryable** (`ActivityResult.nonRetryableFailure`). It is recorded as `ActivityFailed` at once even if attempts remain. A list of non-retryable error *types* is deferred; the worker decides, because the engine never parses payloads.

**Start-to-close timeout replaces the lease.** If a call sets a timeout, the task is leased for exactly that long. Once the deadline passes, the task is **not** handed to another worker. It is failed by a sweep (`WorkflowStore.timeOutExpiredActivities`), which treats it like a reported failure (`ActivityTimeout`): retried if the policy allows, otherwise recorded as `ActivityFailed`. The worker that was running is fenced out and its late result is ignored. Without a timeout, an expired lease still means "the worker died, hand the task out again", as in Phase 2.

**Timers are commands.** `ctx.sleep(duration)` shares the numbering of activity calls (command 1, 2, 3, ... in call order). It records `TimerStarted` and inserts a row into the existing `timer` table, in the same transaction as the decision. The fire time is computed with the database clock. A sweep (`WorkflowStore.fireDueTimers`) appends `TimerFired` and queues a workflow task, using `FOR UPDATE SKIP LOCKED` so several servers can run it. Replay compares the *kind* of command (activity versus timer) and the activity name, as before; the duration is not compared.

**The sweeps are pulled, not pushed.** Both sweeps must be called regularly. Until the server's scheduler exists (a later phase), the tests and the worker loop call them. A timer or timeout fires on the next sweep after it is due, so accuracy equals the sweep interval.

## Alternatives considered

- **Record every attempt in the history.** Complete audit trail, but long-running flapping activities would grow the history without bound. Temporal also keeps attempts out of the history for this reason.
- **Count crashes as failures.** Simpler (one counter), but a deploy that restarts workers would burn retries and could fail healthy workflows.
- **Let a timed-out task be re-claimed directly.** No sweep needed, but then the timeout could not be counted as a failure or end in `ActivityFailed`; a hung activity would be retried forever.
- **A separate timer service holding timers in memory or Redis.** Violates "PostgreSQL is the source of truth" (ADR-0003, ADR-0004).

## Consequences

- Crash recovery stays one mechanism: all new state (failures, backoff, timer rows) changes in the same transaction as the step that causes it. The in-memory and database crash tests cover retries and timers.
- Retry delays are not in the history, so they are not replayed; they are an engine concern, not workflow logic. A workflow cannot observe or branch on the number of attempts.
- With a timeout set, a crashed worker's attempt counts as a failure. This is intentional, because the engine cannot tell "crashed" from "hung" until the deadline passes.
- Deferred: schedule-to-close and heartbeat timeouts, workflow run timeouts, jitter, retryable-error-type lists, cancelling a timer, and parallel timers and activities (which need futures).
