-- Revenant V2: link history events and tasks to the workflow command (activity call) they belong to.
-- Commands are numbered 1, 2, 3, ... in the order the workflow code issues them (docs/design.md, section 8).

ALTER TABLE history_event
    ADD COLUMN command_id BIGINT,
    ADD COLUMN name       TEXT,
    ADD CONSTRAINT chk_history_command_id CHECK (command_id IS NULL OR command_id >= 1);

-- Database-level backstop against double scheduling or double completion: a command can have
-- at most one event of each type (one ActivityScheduled, one ActivityCompleted, ...).
CREATE UNIQUE INDEX uq_history_command_event
    ON history_event (workflow_id, command_id, event_type)
    WHERE command_id IS NOT NULL;

ALTER TABLE task
    ADD COLUMN command_id BIGINT,
    ADD COLUMN name       TEXT,
    -- An activity task must say which command and which activity it runs; a workflow task never does.
    ADD CONSTRAINT chk_task_command CHECK (
        (kind = 'ACTIVITY_TASK') = (command_id IS NOT NULL AND name IS NOT NULL)
    );
