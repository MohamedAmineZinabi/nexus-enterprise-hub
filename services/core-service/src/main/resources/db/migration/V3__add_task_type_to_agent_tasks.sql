-- =============================================================================
-- V3: Add task_type and metadata to core.agent_tasks
-- =============================================================================

ALTER TABLE core.agent_tasks 
    ADD COLUMN IF NOT EXISTS task_type VARCHAR(50) NOT NULL DEFAULT 'DEEP_RESEARCH',
    ADD COLUMN IF NOT EXISTS metadata JSONB;

CREATE INDEX IF NOT EXISTS idx_agent_tasks_task_type ON core.agent_tasks(task_type);
