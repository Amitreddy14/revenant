# ADR-0009: Replay by re-running workflow code from the top

- Status: Accepted
- Date: 2026-11-03

## Context

ADR-0002 chose a replay-based core. Replay needs a concrete mechanism: how does workflow code that "waits" for an activity get paused, and how is it brought back to the same point after a crash?

Options considered:

1. **Keep a thread or continuation alive** per running workflow and park it while waiting. Fast, but the parked state lives in memory, so it is exactly what a crash destroys.
2. **Re-run the workflow method from the top** on every decision, answering calls that already have a result from the history, and pausing at the first call that does not.
3. **Model workflows as a state machine** written by the user. Durable, but not "ordinary Java code" (design goal 4).

## Decision

Use option 2, implemented as a pure function, `Decider.decide(definition, history)`.

- `WorkflowContext.callActivity` is matched against the history by call order. A recorded result is returned immediately. A scheduled activity with no result pauses the workflow. A call with no record becomes a new command and pauses the workflow.
- Pausing is done by throwing `WorkflowSuspendedError`, an `Error` rather than an `Exception`, so that ordinary `catch (Exception e)` in workflow code does not swallow it. It is stackless because it is control flow. No threads are involved.
- Commands are numbered `1, 2, 3, ...` in call order. The number and the activity name are stored in typed columns (`command_id`, `name`) so the core never has to parse JSON. Inputs, results and errors are opaque JSON text that the core passes through untouched.
- Only the activity **name** is compared against the history, not the input. PostgreSQL's JSONB normalises whitespace and key order, so the same input can come back as different text.
- After the run, the engine checks that the code made at least as many calls as the history records. It also re-raises any mismatch even if workflow code caught it. Mismatches raise `NonDeterminismException`, which fails the decision task, not the workflow.
- Activities run one at a time in this phase. Parallel activities need futures and are deferred.

Committing a decision (`WorkflowStore.completeWorkflowTask`) is one transaction that removes the workflow task, appends the events, writes outbox rows, queues activity tasks and closes the workflow if needed. It first takes the workflow row lock (`SELECT ... FOR UPDATE`) and compares `last_seq` with the sequence number the decision was computed from. If the history moved on, the decision is discarded. This is safe because of one invariant: **every event that needs a decision queues a workflow task in the same transaction**, so a fresher task always exists.

Activity results (`completeActivityTask`) follow the same pattern and always queue a workflow task. A task can only be completed by the claim that holds the current `attempt` number, which fences out workers whose lease expired.

## Consequences

- Crash recovery needs no special code path: a decision is a pure function of the history, so replaying after any crash gives the same answer. This is tested by crashing at every operation, and every pair of operations, in `CrashRecoveryTest`.
- The cost of a decision grows with history length, because the whole history is replayed. A "continue as new" mechanism for very long workflows is a later concern.
- Workflow code must not catch `Throwable`/`Error` around activity calls. The engine defends against it (it refuses further commands after the first pause) but cannot make such code correct.
- Non-determinism detection is deliberately modest: call order and activity names. Reading the clock or random numbers inside workflow code is not detected; the Phase 5 SDK supplies deterministic replacements.
- Parallel activities will require revisiting how a pause is represented. A new ADR will supersede this one for that part.
