-- =============================================================================
-- V4: Add Telemetry & Live Streaming Fields to Agent Tasks and Audit Events
-- =============================================================================

ALTER TABLE core.agent_tasks
    ADD COLUMN IF NOT EXISTS last_active_node VARCHAR(50),
    ADD COLUMN IF NOT EXISTS result_payload JSONB,
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE core.task_audit_events
    ADD COLUMN IF NOT EXISTS duration_ms BIGINT;

CREATE INDEX IF NOT EXISTS idx_agent_tasks_last_active_node ON core.agent_tasks(last_active_node);

