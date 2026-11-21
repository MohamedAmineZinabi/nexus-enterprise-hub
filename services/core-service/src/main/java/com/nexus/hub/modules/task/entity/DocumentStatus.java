package com.nexus.hub.modules.task.entity;

public enum DocumentStatus {
    /** Legacy (pre-async ingestion); kept so existing rows still map. */
    UPLOADED,
    /** Legacy (pre-async ingestion); kept so existing rows still map. */
    UPLOADED_PENDING_INDEX,
    /** Legacy (pre-async ingestion); kept so existing rows still map. */
    PROCESSING,
    /** File stored and row committed; document.ingest.requested published, awaiting ai-service. */
    INDEXING,
    INDEXED,
    FAILED
}
