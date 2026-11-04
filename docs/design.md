# Revenant: Design Document

Status: Phase 0 draft. Details marked *(Phase N)* are finalised in that phase and may change; the decisions in `docs/adr/` are locked unless a new ADR supersedes them.

## 1. Problem

A long-running process (an order that waits for payment, a refund that retries for days, an onboarding that pauses for human approval) outlives any single program run. Servers restart, deploys happen, networks drop. Ordinary code keeps its place only in memory, so when the process dies, the work is lost or half-done.

Revenant is a workflow engine that makes such processes **durable**: a workflow can be interrupted at any instruction, on any machine, and later continue from the exact step where it stopped, without repeating completed work.

## 2. Goals and non-goals

**Goals**

1. Survive crashes of workers, of engine instances, and of Redis and Kafka, without losing or corrupting workflow state.
2. Resume from the exact point of failure: completed steps are never re-executed.
3. First-class retries with backoff, timeouts and durable timers (sleeps that last days).
4. Workflows written as ordinary Java code, not as a diagram or YAML.
5. Observable and operable: health checks, metrics, event stream, clear failure reasons.

**Non-goals**

- Multi-tenancy, authentication and a web UI (possible later, not part of the core).
- A second language SDK (ADR-0006).
- Competing with Temporal on scale. Target is thousands of tasks per second on one PostgreSQL, not millions.

## 3. Core idea: record decisions, replay them

Think of a video game save file. Instead of freezing the whole game, Revenant keeps an append-only **history** of everything that happened in a workflow: it started, activity A was scheduled, A returned `42`, a timer was set, and so on.

To resume, a worker runs the workflow code **from the top**, but whenever the code reaches a step that already appears in the history, the SDK returns the recorded result instead of doing the work again. When the code reaches the first step that is *not* in the history, that is the point of failure: real execution continues from there.

This is called **replay**, and it requires one rule: workflow code must be **deterministic** (section 8).

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Server
    participant DB as PostgreSQL
    participant W as Worker
    C->>S: start workflow
    S->>DB: append WorkflowStarted + enqueue workflow task (one transaction)
    W->>S: poll workflow task
    S-->>W: task + full history
    W->>W: replay workflow code against history
    W->>S: commands (schedule activity A)
    S->>DB: append ActivityScheduled + enqueue activity task
    W->>S: poll activity task, run A, report result
    S->>DB: append ActivityCompleted + enqueue workflow task
    Note over W,S: If the worker dies here, another worker replays the history and continues
```

## 4. Vocabulary

| Term | Meaning |
|---|---|
| Workflow | Deterministic Java code describing the process. Its progress is stored as history. |
| Activity | A side-effecting step (call an API, charge a card). May fail and be retried. |
| History | Append-only list of events for one workflow. The single source of truth for its state. |
| Replay | Re-running workflow code against the history to rebuild its in-memory state. |
| Workflow task | "Your workflow has new events, decide what to do next." Handled by a worker. |
| Activity task | "Run this activity." Handled by a worker. |
| Lease | Time-limited ownership of a task by one worker. Expires if the worker goes silent. |
| Outbox | Table of events to publish to Kafka, written in the same transaction as the history. |

## 5. Architecture

```mermaid
flowchart LR
    Client["Client<br/>(REST API)"] -->|start, signal, query| Server
    Worker["Worker<br/>(your workflow and activity code + SDK)"] -->|poll tasks, report results| Server
    Server["Revenant Server<br/>(Spring Boot, stateless)"] -->|history, tasks, timers, outbox| PG[("PostgreSQL<br/>source of truth")]
    Server -.->|wake-ups, rate limits| Redis[("Redis<br/>accelerator")]
    Server -->|outbox relay| Kafka[["Kafka<br/>workflow event stream"]]
```

| Component | Responsibility | Holds state of record? |
|---|---|---|
| Revenant Server | API, task dispatch, timer service, outbox relay. Stateless: any instance can serve any request. | No |
| PostgreSQL | History, task queue, timers, outbox, workflow metadata. | **Yes** (ADR-0003) |
| Redis | Wake-up notifications so workers do not poll constantly; rate limiting; short-lived caches. | **No** (ADR-0004) |
| Kafka | Outbound stream of workflow events for other systems. | No (ADR-0005) |
| Worker | Runs user code via the SDK. Stateless between tasks. | No |

### Modules (ADR-0007)

| Module | Purpose | Depends on |
|---|---|---|
| `revenant-core` | History, replay, retry policy. Pure Java, no frameworks. | nothing |
| `revenant-persistence` | SQL migrations, plain-JDBC repositories, transactions. No Spring (ADR-0008). | core |
| `revenant-worker-sdk` | What workflow authors import. | core |
| `revenant-server` | Spring Boot app: API, dispatcher, timers, outbox relay. | core, persistence |

## 6. Data model (implemented in Phase 1)

The schema lives in `revenant-persistence/src/main/resources/db/migration/` (`V1__init_schema.sql`, `V2__command_correlation.sql`) and is applied by Flyway.

```mermaid
erDiagram
    WORKFLOW_EXECUTION ||--o{ HISTORY_EVENT : "has history"
    WORKFLOW_EXECUTION ||--o{ TASK : "has tasks"
    WORKFLOW_EXECUTION ||--o{ TIMER : "has timers"
    WORKFLOW_EXECUTION ||--o{ OUTBOX : "emits events"
```

| Table | Key columns | Notes |
|---|---|---|
| `workflow_execution` | `workflow_id`, `workflow_type`, `status`, `last_seq`, `closed_at` | `last_seq` doubles as the optimistic-lock version. A check constraint ties `closed_at` to `status`. |
| `history_event` | `(workflow_id, seq)` primary key, `event_type`, `command_id`, `name`, `payload` (JSONB) | Append-only: a trigger rejects `UPDATE` and `DELETE`. The primary key makes duplicate appends fail. A unique index on `(workflow_id, command_id, event_type)` stops a command being scheduled or completed twice. |
| `task` | `task_id`, `workflow_id`, `kind`, `command_id`, `name`, `payload`, `attempt`, `visible_at`, `locked_by`, `locked_until`, plus for activities `failures`, `max_attempts`, `initial_interval_ms`, `backoff_coefficient`, `max_interval_ms`, `start_to_close_ms`, `last_error` | Claimed with `FOR UPDATE SKIP LOCKED` under a lease. `attempt` is a fencing token. An activity task must carry `command_id` and `name`; a workflow task must not. The retry columns are copied from the workflow's call (V3). |
| `timer` | `timer_id`, `workflow_id`, `timer_seq`, `command_id`, `fire_at`, `fired_at` | Partial index on due timers. Fire time uses the database clock. One timer per workflow command. |
| `outbox` | `event_id`, `workflow_id`, `event_type`, `payload`, `published_at` | Written in the same transaction as the history append. Published to Kafka by the relay (Phase 4a). |

### Three mechanisms worth knowing

**Optimistic append.** To append, a writer states the newest sequence number it has seen. The store runs `UPDATE workflow_execution SET last_seq = new WHERE workflow_id = ? AND last_seq = expected AND status = 'RUNNING'`. If another writer got there first, zero rows match and the append fails with `AppendConflictException`, so the caller re-reads and decides again. The primary key on `(workflow_id, seq)` is the second line of defence.

**Leases and fencing.** A worker claims a task by setting `locked_until` in the future and incrementing `attempt`. If the worker dies, the lease runs out and the task becomes claimable again. When a worker reports completion it must present the `attempt` it was given. A worker that lost its lease still holds the old number, so its late report is rejected instead of corrupting the new owner's work.

**One transaction per step.** `WorkflowStore.start` creates the workflow, appends `WorkflowStarted`, writes the outbox row and enqueues the first task in a single transaction. A crash at any point leaves either all four or none.

Workflow status moves one way:

```mermaid
stateDiagram-v2
    [*] --> RUNNING
    RUNNING --> COMPLETED
    RUNNING --> FAILED
    RUNNING --> CANCELLED
    RUNNING --> TIMED_OUT
```

## 7. Failure model

What happens when each part dies, and why nothing is lost:

| Failure | Behaviour | Why it is safe |
|---|---|---|
| Worker dies during an activity | Lease expires without heartbeat, task becomes visible again, another worker retries it. With a start-to-close timeout the sweep fails the attempt instead, and the retry policy decides what happens next. | The task row lives in PostgreSQL. |
| An activity hangs | At the start-to-close deadline the attempt is failed (`ActivityTimeout`) and retried or recorded as failed. Its late result is ignored. | The deadline and the fence are columns on the task row. |
| Server is down when a timer is due | The timer fires on the first sweep after the server is back. | The timer is a row with a database-clock fire time, not memory. |
| Worker dies during a workflow task | Lease expires, task is reassigned, the new worker replays the history. | History is authoritative; workers hold no state. |
| Server instance dies | Other instances keep serving. The restarted one reads PostgreSQL. | Server is stateless. |
| Redis is lost | Hints stop; pollers wake on their timeout and claim from PostgreSQL. Slower, still correct. The server keeps retrying the connection. | Redis never holds state of record. |
| Kafka is down | Outbox rows accumulate; the relay fails fast, retries every tick, and catches up in order when Kafka returns. | The outbox is written in the same transaction as the history. |
| Two servers run the relay | One gets the advisory lock; the other skips that tick. | `pg_try_advisory_xact_lock` is released on commit or crash. |
| Relay crashes after Kafka accepted an event | The event is sent again. Consumers dedupe by `event_id`. | At-least-once delivery. |
| PostgreSQL is down | Engine is unavailable and makes no progress. Nothing is corrupted. Resumes when the database returns. | ACID transactions. |
| A result is reported twice (network retry) | The second report is rejected or ignored. | `(workflow_id, seq)` is unique; task completion is idempotent. |

## 8. Semantics and rules

**Delivery.** Activities run **at least once**: a crash after the side effect but before the result is recorded causes a retry. Workflow *state* changes happen **exactly once**, because history appends are transactional and idempotent. Activity authors should use an idempotency key, for example `workflowId + activityId`, when calling external systems.

**Determinism.** Workflow code runs again on every replay, so it must make the same decisions every time. Inside a workflow, do not read the system clock, generate random numbers, start threads, or call external systems directly. The SDK provides approved replacements (workflow time, workflow random, durable sleep) *(Phase 5)*. During replay the engine compares the activity calls the code makes with the recorded history, by order and by activity name. A mismatch, or code that stops before reaching a recorded call, raises `NonDeterminismException`, which fails the decision *task* (fixable by deploying corrected code), not the workflow. Inputs are deliberately not compared, because PostgreSQL's JSONB rewrites whitespace and key order (ADR-0009).

**Concurrency control.** A history append writes `seq = last_seq + 1`. If two writers race, one violates the primary key and retries after re-reading.

**Retries and timeouts** (Phase 3, ADR-0010). A call may carry a retry policy (maximum attempts including the first, initial interval, backoff coefficient, maximum interval) and a start-to-close timeout. Failure counters live on the task row, not in the history, so a flapping activity does not bloat the history: only the final outcome is recorded. A worker may mark a failure non-retryable. Not yet implemented: schedule-to-close and heartbeat timeouts, and lists of non-retryable error types.

**Ordering.** Kafka events use `workflow_id` as the message key, so consumers see one workflow's events in order. Delivery is at least once; consumers deduplicate by `event_id`.

### How a decision is made (implemented in Phase 2)

`Decider.decide(definition, history)` is a pure function: no database, clock or threads. A worker loads the history, calls it, and hands the result to `WorkflowStore.completeWorkflowTask`.

```mermaid
flowchart TD
    A[Load history] --> B[Validate history]
    B --> C{Already closed?}
    C -->|yes| Z[Nothing to do]
    C -->|no| D[Run workflow code from the top]
    D --> E{What does the code do next?}
    E -->|calls an activity with a result in history| F[Return the recorded result]
    F --> E
    E -->|calls an activity still running| G[Pause, nothing new to record]
    E -->|calls an activity not in history| H[Record a new command, pause]
    E -->|returns| I[Record WorkflowCompleted]
    E -->|throws| J[Record WorkflowFailed]
    G --> K[Commit in one transaction]
    H --> K
    I --> K
    J --> K
```

Two storage operations commit every step, each in a single transaction:

| Operation | Removes | Appends | Queues |
|---|---|---|---|
| `completeWorkflowTask` | the workflow task | the decision's events (and an outbox row for each) | one activity task per new command; closes the workflow if the decision ends it |
| `completeActivityTask` | the activity task | `ActivityCompleted` or `ActivityFailed` (and an outbox row) | a workflow task, so the code can continue |

Both return one of three outcomes. `APPLIED`: recorded. `LEASE_LOST`: the worker's lease expired and the task belongs to someone else, so nothing was written. `STALE_DISCARDED`: the history moved on (or the workflow closed) while the worker was busy, so the result is dropped. That last case is safe because every event that needs a decision queues a workflow task in its own transaction, so a fresher task already exists.

### Retries, timers and timeouts (implemented in Phase 3)

The life of an activity task:

```mermaid
stateDiagram-v2
    [*] --> Queued: decision schedules the activity
    Queued --> Leased: worker claims (attempt + 1)
    Leased --> Done: success, recorded as ActivityCompleted
    Leased --> Queued: worker crashed, lease expired (no timeout set), handed out again
    Leased --> Backoff: failure or timeout, attempts left (failures + 1)
    Backoff --> Queued: backoff elapsed
    Leased --> Done: attempts used up or non-retryable, recorded as ActivityFailed
    Done --> [*]
```

Operations, each a single transaction:

| Operation | Effect |
|---|---|
| `completeActivityTask` | Success: as in Phase 2. Retryable failure with attempts left: count it, unlock the task, delay it by the backoff, history untouched (`RETRY_SCHEDULED`). Otherwise: record the final result and wake the workflow. |
| `timeOutExpiredActivities(max)` | Finds leased activities past their start-to-close deadline and fails them like a reported failure (`ActivityTimeout`). The late worker is fenced out. |
| `fireDueTimers(max)` | For each due timer: append `TimerFired`, queue a workflow task, mark it fired. A timer of a closed workflow is only marked. |

`ctx.sleep(duration)` is a command like an activity call and shares its numbering. Replay answers a sleep whose `TimerFired` is recorded immediately, and pauses otherwise. Both sweeps are pulled: something must call them regularly, so a timer fires on the first sweep after it is due.

### Background scheduler and outbox relay (implemented in Phase 4a)

```mermaid
flowchart LR
    S1["SweepScheduler<br/>every 1 s"] -->|fireDueTimers, timeOutExpiredActivities| PG[("PostgreSQL")]
    R1["RelayScheduler<br/>every 0.5 s"] -->|relayBatch| Relay["OutboxRelay<br/>one transaction"]
    Relay -->|1. try advisory lock<br/>2. read oldest unpublished<br/>5. mark sent rows published| PG
    Relay -->|3. send in order, key = workflow_id| Kafka[["Kafka topic<br/>revenant.workflow-events"]]
    Kafka -.->|4. failure: stop, mark only the sent prefix| Relay
```

| Piece | Behaviour |
|---|---|
| `OutboxRelay.relayBatch(max)` | Lock, read, send in `event_id` order, stop at the first failure, mark the sent prefix. Returns published count, skipped flag or the failure. |
| `KafkaEventSink` | One record per event: key `workflow_id`, value the JSON payload, headers `event_id`, `event_type`, `created_at`. Waits up to 15 s for the broker acknowledgement. |
| `SweepScheduler` / `RelayScheduler` | Spring `@Scheduled` loops. Exceptions are logged, never fatal. Switch off with `revenant.scheduler.enabled=false` and `revenant.kafka.enabled=false`. |

See ADR-0011 for the reasoning.

### Redis wake-up hints (implemented in Phase 4b)

```mermaid
sequenceDiagram
    participant W as Writer (any server)
    participant PG as PostgreSQL
    participant R as Redis pub/sub
    participant P as Poller (any server)
    W->>PG: commit (task row is queued)
    W-->>R: publish wake-up (fire and forget)
    R-->>P: hint arrives, poller wakes early
    P->>PG: claimTask (the real check)
    Note over P,PG: No hint? The wait times out and the poller claims anyway.
```

| Piece | Behaviour |
|---|---|
| `TaskNotifier` (persistence) | Called by `WorkflowStore` after a commit that may have queued tasks. Failures are ignored. |
| `RedisWakeUps` (server) | Publishes hints to `revenant:wake:<KIND>`, subscribes to the same channels, connects in the background and retries. |
| `WakeUpSignals` (server) | `await(kind, maxWait)` returns on a hint or at the timeout. Many hints equal one look. |

See ADR-0012. Redis holds no state, so losing it only makes workers a little slower to notice work.

### HTTP API and workers (implemented in Phase 5a)

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Server
    participant W as Worker (your code + SDK)
    participant PG as PostgreSQL
    C->>S: POST /workflows (type, input)
    S->>PG: workflow + history + first workflow task
    W->>S: POST /tasks/poll (WORKFLOW_TASK), waits
    S->>PG: claim task (lease)
    S-->>W: task + whole history
    Note over W: replays workflow code, decides
    W->>S: complete-workflow (attempt, commands)
    S->>PG: one transaction: events, outbox, activity tasks
    W->>S: POST /tasks/poll (ACTIVITY_TASK)
    S-->>W: activity name + input
    Note over W: runs your function
    W->>S: complete-activity (attempt, result)
    S->>PG: result recorded, workflow task queued
```

Everything the worker holds is disposable: kill it at any point and the lease runs out, the task is handed to another worker, and the replay continues from the history. See ADR-0013 for the endpoints and the rules for what a worker does when it cannot help.

## 9. Roadmap

| Phase | Deliverable | Proves |
|---|---|---|
| 0 | Design doc, ADRs, Gradle scaffold, Docker Compose, CI | The project builds and the infrastructure starts. |
| 1 | PostgreSQL schema, Flyway migrations, Testcontainers | Storage works against a real database. |
| 2 | Event-sourced history and the replay core | Deterministic replay, resume from the exact step. |
| 3 | Retries, timers, timeouts, failure handling | Failures are survivable, not just detectable. |
| 4a | Background scheduler and Kafka outbox relay | Timers and events move without anyone calling by hand. |
| 4b | Redis task wake-ups | Acceleration without a new point of failure. |
| 5a | HTTP API and worker SDK | Real programs can run workflows against the server. |
| 5b | Demo workflow that survives `kill -9` | End-to-end crash recovery, demonstrated. |
| 6 | Free deployment, documentation, demo | A running system others can try. |

## 10. Open questions

- **Free deployment target (Phase 6).** Candidate: an Oracle Cloud Always Free VM running Docker Compose. Fallback: local Docker plus a recorded demo. To be decided in Phase 6.
- **Payload serialization.** JSON through Jackson 3 (bundled with Spring Boot 4) is the default. Revisit if payload size becomes an issue.
- **History size limit.** Long-lived workflows need a "continue as new" mechanism. Out of scope until later.
