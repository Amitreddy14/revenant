# The kill -9 demo

Run `scripts/demo-kill-worker.ps1` (see the README). This page explains what happens inside.

## Timeline

| Time | Event |
|---|---|
| t = 0 | Worker A polls and gets the workflow task. It replays, returns the command "run `charge`". |
| t + ~1 s | A runs `charge`, then `reserveStock`. Each result is committed to the history (`ActivityCompleted`). |
| t + ~3 s | A starts `ship`; the ledger shows `ship STARTED`. |
| kill | The script kills A. No shutdown hook runs; nothing is reported. |
| kill + 10 s | The `ship` task lease expires. The scheduler's sweep makes the task claimable again. |
| then | Worker B claims it with a higher `attempt`, runs `ship` from the start, reports `ship DONE`. |
| end | The workflow task replays: `charge` and `reserveStock` come from history, not re-run. `sendReceipt` runs once. Status `COMPLETED`. |

## Why it is safe

- **History is the truth.** Every finished step is a committed row. A worker holds no state that matters.
- **Leases and fencing.** A claim lasts 10 s. If A had only been slow (not dead) and reported late, its `attempt` number would be stale and the server would discard that report (`STALE_DISCARDED`).
- **At-least-once, not exactly-once.** A step killed halfway can run again. Make activities idempotent: the demo uses ids derived from the order id (`ch_<id>`, `rs_<id>`, `TRK-<id>`).

## Limits

There is no heartbeat yet, so a dead worker is noticed only when the lease runs out (10 s here; set per worker). The API has no authentication; that is Phase 6.
