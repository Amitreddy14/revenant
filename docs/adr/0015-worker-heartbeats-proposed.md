# ADR-0015: Worker heartbeats (proposed)

- Status: Proposed (not built)
- Date: 2026-11-05

## Context

The kill -9 demo shows the main cost of the current design. When a worker dies, the server learns of it only when the task's lease runs out. With a 10-second lease, the interrupted step waits up to 10 seconds. The lease is a trade-off the workflow author makes by guessing:

- **short lease**: fast recovery, but a long, healthy activity (a 5-minute video render) outlives its lease and is wrongly handed to a second worker. The first worker's late result is discarded (`LEASE_LOST`), so nothing is corrupted, but the work is done twice.
- **long lease**: no false hand-offs, but a crash costs the full lease before anyone retries.

## Proposal

Keep a **short lease** and let a live worker **renew** it: `POST /tasks/{id}/heartbeat?attempt=`, which pushes `lease_expires_at` forward only if the attempt still matches (the same fencing check as completion). The worker SDK sends it from a background thread every third of the lease while an activity runs. A dead worker stops renewing, so recovery takes about one short lease; a slow but healthy worker keeps its task.

A heartbeat reply of `LEASE_LOST` tells the worker to interrupt the activity, so a worker that was cut off stops wasting effort. A `details` field can carry progress, which a retry could resume from.

## Why it is not built yet

Nothing in the demo or the current examples needs it, and it adds a new endpoint, a new SDK thread, and a new failure mode (a heartbeat thread that keeps running while the activity thread is stuck, which would hide a hang; the activity's start-to-close timeout from ADR-0010 still bounds that). It is the first thing to build when real activities run longer than a few seconds.
