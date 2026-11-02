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
| `revenant-persistence` | Flyway migrations, PostgreSQL repositories. | core |
| `revenant-worker-sdk` | What workflow authors import. | core |
| `revenant-server` | Spring Boot app: API, dispatcher, timers, outbox relay. | core, persistence |

## 6. Data model (sketch, finalised in Phase 1)

| Table | Key columns | Notes |
|---|---|---|
| `workflow_execution` | `workflow_id`, `type`, `status`, `version` | `version` enables optimistic locking. |
| `history_event` | `(workflow_id, seq)` primary key, `type`, `payload`, `created_at` | Append-only. The primary key makes duplicate appends fail. |
| `task` | `task_id`, `kind`, `workflow_id`, `visible_at`, `locked_until`, `attempt` | Claimed with `FOR UPDATE SKIP LOCKED`. |
| `timer` | `timer_id`, `workflow_id`, `fire_at` | Indexed on `fire_at`. |
| `outbox` | `event_id`, `workflow_id`, `payload`, `published_at` | Written in the same transaction as the history append. |

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
| Worker dies during an activity | Lease expires without heartbeat, task becomes visible again, another worker retries it. | The task row lives in PostgreSQL. |
| Worker dies during a workflow task | Lease expires, task is reassigned, the new worker replays the history. | History is authoritative; workers hold no state. |
| Server instance dies | Other instances keep serving. The restarted one reads PostgreSQL. | Server is stateless. |
| Redis is lost | Workers fall back to polling PostgreSQL. Slower, still correct. | Redis never holds state of record. |
| Kafka is down | Outbox rows accumulate; the relay catches up when Kafka returns. | The outbox is written in the same transaction as the history. |
| PostgreSQL is down | Engine is unavailable and makes no progress. Nothing is corrupted. Resumes when the database returns. | ACID transactions. |
| A result is reported twice (network retry) | The second report is rejected or ignored. | `(workflow_id, seq)` is unique; task completion is idempotent. |

## 8. Semantics and rules

**Delivery.** Activities run **at least once**: a crash after the side effect but before the result is recorded causes a retry. Workflow *state* changes happen **exactly once**, because history appends are transactional and idempotent. Activity authors should use an idempotency key, for example `workflowId + activityId`, when calling external systems.

**Determinism.** Workflow code runs again on every replay, so it must make the same decisions every time. Inside a workflow, do not read the system clock, generate random numbers, start threads, or call external systems directly. The SDK provides approved replacements (workflow time, workflow random, durable sleep) *(Phase 5)*. During replay the SDK compares the commands the code produces with the recorded history; a mismatch raises a non-determinism error that fails the workflow *task* (and is fixable by deploying corrected code), not the workflow.

**Concurrency control.** A history append writes `seq = last_seq + 1`. If two writers race, one violates the primary key and retries after re-reading.

**Retries.** Each activity has a retry policy: initial interval, backoff coefficient, maximum interval, maximum attempts, and a list of non-retryable error types. Timeouts: schedule-to-close, start-to-close and heartbeat. Attempt counters live on the task row, not in the history, so a flapping activity does not bloat the history.

**Ordering.** Kafka events use `workflow_id` as the message key, so consumers see one workflow's events in order. Delivery is at least once; consumers deduplicate by `event_id`.

## 9. Roadmap

| Phase | Deliverable | Proves |
|---|---|---|
| 0 | Design doc, ADRs, Gradle scaffold, Docker Compose, CI | The project builds and the infrastructure starts. |
| 1 | PostgreSQL schema, Flyway migrations, Testcontainers | Storage works against a real database. |
| 2 | Event-sourced history and the replay core | Deterministic replay, resume from the exact step. |
| 3 | Retries, timers, timeouts, failure handling | Failures are survivable, not just detectable. |
| 4 | Redis task wake-ups and Kafka event stream | Acceleration and integration without new points of failure. |
| 5 | Worker SDK and a demo workflow that survives `kill -9` | End-to-end crash recovery, demonstrated. |
| 6 | Free deployment, documentation, demo | A running system others can try. |

## 10. Open questions

- **Free deployment target (Phase 6).** Candidate: an Oracle Cloud Always Free VM running Docker Compose. Fallback: local Docker plus a recorded demo. To be decided in Phase 6.
- **Payload serialization.** JSON through Jackson 3 (bundled with Spring Boot 4) is the default. Revisit if payload size becomes an issue.
- **History size limit.** Long-lived workflows need a "continue as new" mechanism. Out of scope until Phase 3 or later.
