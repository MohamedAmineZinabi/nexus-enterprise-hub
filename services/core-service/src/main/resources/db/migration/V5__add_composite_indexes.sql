-- =============================================================================
-- V5: Composite indexes for tenant-scoped, newest-first listings
-- =============================================================================

CREATE INDEX IF NOT EXISTS idx_agent_tasks_tenant_created ON core.agent_tasks (tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_documents_tenant_created ON core.documents (tenant_id, created_at DESC);
