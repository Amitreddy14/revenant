# ADR-0002: Build a custom replay-based durable execution core

- Status: Accepted
- Date: 2026-11-03

## Context

The goal is a workflow engine that survives failures and resumes from the exact point of failure. Three approaches were considered:

1. **Use Temporal** and build a product on top of it.
2. **A DAG orchestrator** (Airflow-style): steps are declared as a graph and the scheduler tracks which nodes finished.
3. **A custom replay-based core**: record an event history and re-run deterministic workflow code against it (the model Temporal and Azure Durable Functions use).

## Decision

Build option 3: a small replay-based durable execution engine.

## Consequences

- Workflows are ordinary Java code with loops, branches and variables, which a DAG cannot express naturally.
- The project demonstrates the hard parts directly: event sourcing, determinism, leases, idempotency.
- Cost: we own correctness. Determinism bugs are subtle, so the replay core gets heavy testing in Phase 2 and a chaos demo in Phase 5.
- Scope is bounded by the non-goals in `docs/design.md`; we do not try to match Temporal's feature set.
