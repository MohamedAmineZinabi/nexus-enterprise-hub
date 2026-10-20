-- =============================================================================
-- V2: Document Uploads, Multi-Agent Tasks & Audit Telemetry
-- =============================================================================

-- 1. Knowledge Base Documents (Shared Volume & Vector Status)
CREATE TABLE IF NOT EXISTS core.documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    filename VARCHAR(255) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_size BIGINT NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    vector_collection VARCHAR(100) NOT NULL DEFAULT 'enterprise_docs',
    status VARCHAR(30) NOT NULL DEFAULT 'UPLOADED',
    chunks_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_documents_tenant_id ON core.documents(tenant_id);
CREATE INDEX IF NOT EXISTS idx_documents_status ON core.documents(status);

-- 2. Multi-Agent Tasks (LangGraph / AI Orchestration)
CREATE TABLE IF NOT EXISTS core.agent_tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    prompt TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    result_summary TEXT,
    total_tokens_used BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_tasks_tenant_id ON core.agent_tasks(tenant_id);
CREATE INDEX IF NOT EXISTS idx_agent_tasks_status ON core.agent_tasks(status);

-- 3. Task Audit & Telemetry Events
CREATE TABLE IF NOT EXISTS core.task_audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES core.agent_tasks(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    node_type VARCHAR(50),
    message TEXT,
    payload JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_task_audit_events_task_id ON core.task_audit_events(task_id);
