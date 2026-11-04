# ADR-0013: HTTP API and worker protocol

- Status: Accepted
- Date: 2026-11-05

## Context

Until now the engine could only be driven by Java code that held a `WorkflowStore`. A workflow author's code lives in a separate process (the worker) and the clients that start workflows live elsewhere again. They need a network protocol, and the protocol has to keep the guarantees of ADR-0009: workers hold no state, and a crash at any point loses nothing.

## Decision

**Workers replay locally; the server only stores.** A workflow task sent to a worker carries the workflow type and the whole history. The worker runs `Decider.decide` on it and sends back the resulting commands. The server never runs user code. This keeps the server generic and means a deploy of new workflow code needs no server change.

**Long polling over plain HTTP and JSON** (`/api/v1`):

| Call | Purpose |
|---|---|
| `POST /workflows?type=&id=` (body: input JSON) | Start a workflow. 201, or 409 if the id exists, which makes a retried start safe. |
| `GET /workflows/{id}` | Status and full history. |
| `POST /tasks/poll?kind=&worker=&leaseMs=&waitMs=` | Lease the next task. Waits up to `waitMs` (at most 30 s). 200 with the task, or 204. |
| `POST /tasks/{id}/complete-workflow?attempt=&decidedAtSeq=` (body: commands) | Commit a decision. |
| `POST /tasks/{id}/complete-activity?attempt=` (body: result) | Commit an activity attempt. |

**The lease is the only credential a worker needs.** A completion sends just the task id and the `attempt` number it was given. The server loads the task from the database (`findLeasedTask`), so a worker cannot claim a different command id or name than the one stored. If the attempt no longer matches, the answer is 409 `LEASE_LOST` and the worker drops its result. This is the fencing token of ADR-0009 carried over HTTP.

**Waiting uses the wake-ups of ADR-0012.** A poll that finds nothing waits on `WakeUpSignals` for the task kind, but looks at the database at least once a second. A hint makes the poll answer in milliseconds; a lost hint costs at most one second.

**One wire format, one class.** `WireFormat` in the core module encodes and decodes every message, using a small strict JSON parser (`JsonNode`) that can hand back any value's exact original text. Inputs, results and errors therefore pass through unchanged (PostgreSQL's JSONB still normalises spacing when it stores them). Neither the core nor the SDK needs a JSON library, and a round-trip test pins each message.

**What a worker does when it cannot help.** If a replay raises `NonDeterminismException`, or a task names a workflow type or activity the worker does not know, the worker reports nothing. The lease runs out and the task goes to another worker, for example one running corrected code. The workflow is not failed. An activity that returns text that is not JSON is reported as a non-retryable failure.

## Consequences

- At-least-once activities stay as before: a worker that dies after doing the work but before reporting it causes a second execution. Activities should be idempotent.
- There is no task-queue or type filtering yet. A worker may lease a task it cannot run and sit on it until the lease expires. Run the same set of workflows and activities on every worker of a deployment; filtering is a later concern.
- A long poll cannot tell that its client has gone. If a worker stops while its poll is waiting, the server may still lease it a task and write the answer to a closed connection. The task then waits out that worker's lease before anyone else gets it. Nothing is lost, but the delay equals the lease, so choose leases of seconds, not minutes, for activities that are quick. The end-to-end tests use 3-second leases for this reason.
- Every poll holds one server thread for up to 30 seconds. That is fine for a handful of workers; a large fleet would want virtual threads or an async endpoint.
- The API has no authentication yet. It must not be exposed to an untrusted network until Phase 6 adds it.
- Heartbeats for long activities are not implemented. An activity longer than its lease (or start-to-close timeout) is retried by the server's rules.
