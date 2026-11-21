-- =============================================================================
-- V6: Async document ingestion - failure reason for FAILED documents
-- (status INDEXING is a new value of the existing VARCHAR(30) status column; no CHECK constraint exists)
-- =============================================================================

ALTER TABLE core.documents
    ADD COLUMN IF NOT EXISTS error_details TEXT;
