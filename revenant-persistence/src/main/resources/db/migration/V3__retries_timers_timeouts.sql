-- Revenant V3: activity retries with backoff, start-to-close timeouts, and durable timers.
-- See docs/adr/0010-retries-timers-and-timeouts.md.

-- Retry policy and timeout travel with the activity task, copied from the workflow's call.
-- The defaults reproduce Phase 2 behaviour: one attempt, no retries, no timeout.
ALTER TABLE task
    -- Attempts that reported failure (or timed out). Unlike "attempt", which counts every
    -- claim and fences stale workers, a worker that merely crashed does not add a failure:
    -- the task is simply handed out again.
    ADD COLUMN failures            INT              NOT NULL DEFAULT 0,
    ADD COLUMN max_attempts        INT              NOT NULL DEFAULT 1,
    ADD COLUMN initial_interval_ms BIGINT           NOT NULL DEFAULT 1000,
    ADD COLUMN backoff_coefficient DOUBLE PRECISION NOT NULL DEFAULT 2.0,
    ADD COLUMN max_interval_ms     BIGINT           NOT NULL DEFAULT 1000,
    -- NULL means no timeout. When set, it replaces the claim lease for this task, and an attempt
    -- that outlives it is failed by the timeout sweep instead of being quietly handed out again.
    ADD COLUMN start_to_close_ms   BIGINT,
    -- The error from the most recent failed attempt, kept for diagnosis while retries continue.
    ADD COLUMN last_error          JSONB,
    ADD CONSTRAINT chk_task_failures CHECK (failures >= 0),
    ADD CONSTRAINT chk_task_retry CHECK (
        max_attempts >= 1
        AND initial_interval_ms >= 0
        AND backoff_coefficient >= 1.0
        AND max_interval_ms >= initial_interval_ms),
    ADD CONSTRAINT chk_task_timeout CHECK (start_to_close_ms IS NULL OR start_to_close_ms > 0);

-- The timeout sweep looks for leased activities whose deadline has passed.
CREATE INDEX idx_task_timeout ON task (locked_until)
    WHERE kind = 'ACTIVITY_TASK' AND start_to_close_ms IS NOT NULL AND locked_by IS NOT NULL;

-- Durable timers (the table exists since V1). A timer is tied to the workflow command that
-- started it, so that firing it appends the matching TimerFired event.
ALTER TABLE timer
    ADD COLUMN command_id BIGINT NOT NULL,
    ADD CONSTRAINT chk_timer_command CHECK (command_id >= 1),
    ADD CONSTRAINT uq_timer_workflow_command UNIQUE (workflow_id, command_id);
