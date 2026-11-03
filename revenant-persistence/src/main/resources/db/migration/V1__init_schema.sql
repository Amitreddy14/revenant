-- Revenant V1: core schema.
-- PostgreSQL is the single source of truth (ADR-0003). See docs/design.md, section 6.

-- One row per workflow execution.
CREATE TABLE workflow_execution (
    workflow_id    UUID        PRIMARY KEY,
    workflow_type  TEXT        NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'RUNNING',
    -- Sequence number of the newest history event. Doubles as the optimistic-lock
    -- version: an append only succeeds if the caller saw the current value.
    last_seq       BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at      TIMESTAMPTZ,
    CONSTRAINT chk_workflow_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT')),
    CONSTRAINT chk_workflow_last_seq CHECK (last_seq >= 0),
    -- A workflow is closed exactly when it is no longer RUNNING.
    CONSTRAINT chk_workflow_closed CHECK ((status = 'RUNNING') = (closed_at IS NULL))
);

-- Append-only event history. The primary key makes a duplicate or racing append fail.
CREATE TABLE history_event (
    workflow_id  UUID        NOT NULL REFERENCES workflow_execution (workflow_id),
    seq          BIGINT      NOT NULL,
    event_type   TEXT        NOT NULL,
    payload      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workflow_id, seq),
    CONSTRAINT chk_history_seq_positive CHECK (seq >= 1)
);

CREATE FUNCTION forbid_history_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'history_event is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER trg_history_append_only
    BEFORE UPDATE OR DELETE ON history_event
    FOR EACH ROW EXECUTE FUNCTION forbid_history_mutation();

-- Work queue for workflow tasks and activity tasks. Workers claim rows with
-- FOR UPDATE SKIP LOCKED and hold them under a time-limited lease.
CREATE TABLE task (
    task_id       BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id   UUID        NOT NULL REFERENCES workflow_execution (workflow_id),
    kind          TEXT        NOT NULL,
    payload       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- Incremented on every claim. Doubles as a fencing token: only the holder of the
    -- current attempt may complete the task.
    attempt       INT         NOT NULL DEFAULT 0,
    visible_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_by     TEXT,
    locked_until  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_task_kind CHECK (kind IN ('WORKFLOW_TASK', 'ACTIVITY_TASK')),
    CONSTRAINT chk_task_lock CHECK ((locked_by IS NULL) = (locked_until IS NULL))
);

CREATE INDEX idx_task_claimable ON task (kind, visible_at, task_id);
CREATE INDEX idx_task_workflow ON task (workflow_id);

-- Durable timers (sleeps that survive restarts). The timer service arrives in Phase 3.
CREATE TABLE timer (
    timer_id     BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id  UUID        NOT NULL REFERENCES workflow_execution (workflow_id),
    -- History sequence number of the TimerStarted event that created this timer.
    timer_seq    BIGINT      NOT NULL,
    fire_at      TIMESTAMPTZ NOT NULL,
    fired_at     TIMESTAMPTZ,
    CONSTRAINT uq_timer_workflow_seq UNIQUE (workflow_id, timer_seq)
);

CREATE INDEX idx_timer_due ON timer (fire_at) WHERE fired_at IS NULL;

-- Transactional outbox (ADR-0005): written in the same transaction as the history append,
-- relayed to Kafka later. Events of one workflow are written under that workflow's row
-- lock, so their event_id order matches their commit order.
CREATE TABLE outbox (
    event_id      BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id   UUID        NOT NULL REFERENCES workflow_execution (workflow_id),
    event_type    TEXT        NOT NULL,
    payload       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox (event_id) WHERE published_at IS NULL;
