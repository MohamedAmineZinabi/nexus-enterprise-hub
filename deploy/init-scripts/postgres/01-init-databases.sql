-- =============================================================================
-- NexusEnterprise Hub - PostgreSQL Initialization Script
-- =============================================================================

-- 1. Enable Cryptographic & UUID Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 2. Create Explicit Domain Schemas for Clean Isolation
CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS audit;

-- Set default search path for subsequent queries
SET search_path TO core, public;

-- 3. Initial Metadata / Schema Verification Table
CREATE TABLE IF NOT EXISTS core.system_metadata (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    config_key VARCHAR(100) UNIQUE NOT NULL,
    config_value TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Seed metadata
INSERT INTO core.system_metadata (config_key, config_value)
VALUES 
    ('platform_name', 'NexusEnterprise Hub'),
    ('environment', 'local-development'),
    ('schema_version', '1.0.0')
ON CONFLICT (config_key) DO UPDATE 
SET config_value = EXCLUDED.config_value,
    updated_at = CURRENT_TIMESTAMP;

-- 4. Grant Schema Privileges
GRANT ALL ON SCHEMA core TO PUBLIC;
GRANT ALL ON SCHEMA audit TO PUBLIC;
