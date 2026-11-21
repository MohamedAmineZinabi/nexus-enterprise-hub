"""
Document ingestion pipeline shared by the asynchronous Kafka consumer (document.ingest.requested)
and the legacy synchronous gRPC IngestDocument RPC.

All functions here are synchronous and CPU/IO heavy (parsing, embedding, Qdrant upserts):
async callers must run them via ``asyncio.to_thread``.
"""
import logging
import os
import uuid
from dataclasses import dataclass
from typing import Dict, Optional

from qdrant_client.http import models

from app.core.config import settings
from app.db.qdrant import get_qdrant_client, init_qdrant_collection
from app.rag.embeddings import get_embedding_service
from app.rag.parser import DocumentParser
from app.security.auth import require_tenant_id

logger = logging.getLogger("nexus.rag.ingestion")

UPSERT_BATCH_SIZE = 100
EMBED_BATCH_SIZE = 256


class IngestionError(Exception):
    """Base class for ingestion failures that must be reported back to core-service."""


class InvalidIngestRequest(IngestionError, ValueError):
    """The request itself is invalid (tenant, path, collection) and must never be retried."""


class SourceFileMissing(IngestionError):
    """The uploaded file does not exist (never written, or deleted before indexing)."""


class DocumentDeletedDuringIngest(IngestionError):
    """The document was deleted while it was being indexed; its vectors were purged."""


@dataclass
class IngestResult:
    document_id: str
    tenant_id: str
    collection: str
    chunks_created: int


def _resolve_file_path(path: str) -> str:
    """Resolves a container path or a path relative to the shared uploads directory."""
    if os.path.isabs(path or "") or os.path.exists(path or ""):
        return path
    normalized = (path or "").replace("\\", "/").lstrip("/")
    if normalized.startswith("shared-uploads/"):
        normalized = normalized[len("shared-uploads/"):]
    return os.path.join(settings.shared_uploads_dir, normalized)


def resolve_tenant_file(tenant_id: str, file_path: str) -> str:
    """Returns the canonical file path, guaranteeing it lives inside <shared-uploads>/<tenant_id>/."""
    tenant_root = os.path.realpath(os.path.join(settings.shared_uploads_dir, tenant_id))
    real_path = os.path.realpath(_resolve_file_path(file_path))
    try:
        inside = os.path.commonpath([tenant_root, real_path]) == tenant_root
    except ValueError:
        inside = False
    if not inside or real_path == tenant_root:
        raise InvalidIngestRequest("file_path is outside the tenant's upload directory")
    return real_path


def tenant_document_filter(tenant_id: str, document_id: str) -> models.Filter:
    return models.Filter(must=[
        models.FieldCondition(key="tenant_id", match=models.MatchValue(value=tenant_id)),
        models.FieldCondition(key="document_id", match=models.MatchValue(value=document_id)),
    ])


def purge_document_vectors(tenant_id: str, document_id: str, collection: str) -> None:
    client = get_qdrant_client()
    client.delete(
        collection_name=collection,
        points_selector=models.FilterSelector(filter=tenant_document_filter(tenant_id, document_id)),
        wait=True,
    )


def ingest_document(
    tenant_id: str,
    document_id: str,
    file_path: str,
    mime_type: str = "",
    chunk_size: Optional[int] = None,
    chunk_overlap: Optional[int] = None,
    collection: Optional[str] = None,
    metadata: Optional[Dict[str, str]] = None,
) -> IngestResult:
    """Parses, embeds and upserts one document into Qdrant (tenant-scoped, idempotent point ids)."""
    trusted_tenant = require_tenant_id(tenant_id)
    document_id = (document_id or "").strip()
    if not document_id:
        raise InvalidIngestRequest("document_id is required")
    try:
        collection_name = settings.resolve_collection(collection)
    except ValueError as exc:
        raise InvalidIngestRequest(str(exc)) from exc

    resolved_path = resolve_tenant_file(trusted_tenant, file_path)
    if not os.path.isfile(resolved_path):
        raise SourceFileMissing(f"Document file not found: {os.path.basename(resolved_path)}")

    init_qdrant_collection(collection_name)

    size = chunk_size if chunk_size and chunk_size > 0 else settings.default_chunk_size
    overlap = chunk_overlap if chunk_overlap is not None and chunk_overlap >= 0 else settings.default_chunk_overlap
    parser = DocumentParser(chunk_size=size, chunk_overlap=overlap)
    chunks = parser.parse_and_chunk(
        file_path=resolved_path,
        document_id=document_id,
        mime_type=mime_type or "",
        base_metadata={str(k): str(v) for k, v in (metadata or {}).items()},
    )
    if not chunks:
        logger.warning(f"No textual content extracted from document {document_id}")
        return IngestResult(document_id, trusted_tenant, collection_name, 0)

    client = get_qdrant_client()
    embedding_service = get_embedding_service()
    created = 0

    # Embed + upsert in bounded batches so memory stays flat for large files
    for start in range(0, len(chunks), EMBED_BATCH_SIZE):
        batch = chunks[start:start + EMBED_BATCH_SIZE]
        vectors = embedding_service.embed_documents([c.content for c in batch])
        points = [
            models.PointStruct(
                id=str(uuid.uuid5(uuid.NAMESPACE_DNS, f"{trusted_tenant}:{chunk.chunk_id}")),
                vector=vector,
                payload={
                    "tenant_id": trusted_tenant,
                    "document_id": document_id,
                    "chunk_id": chunk.chunk_id,
                    "content": chunk.content,
                    "chunk_index": chunk.chunk_index,
                    "page_number": chunk.page_number,
                    "citation": chunk.citation,
                    "metadata": chunk.metadata,
                },
            )
            for chunk, vector in zip(batch, vectors)
        ]
        for i in range(0, len(points), UPSERT_BATCH_SIZE):
            client.upsert(collection_name=collection_name, points=points[i:i + UPSERT_BATCH_SIZE], wait=True)
        created += len(points)

        # The document may be deleted while a long ingestion is running: stop and clean up.
        if not os.path.exists(resolved_path):
            purge_document_vectors(trusted_tenant, document_id, collection_name)
            raise DocumentDeletedDuringIngest(f"Document {document_id} was deleted during indexing")

    if not os.path.exists(resolved_path):
        purge_document_vectors(trusted_tenant, document_id, collection_name)
        raise DocumentDeletedDuringIngest(f"Document {document_id} was deleted during indexing")

    logger.info(f"Indexed {created} chunks for document {document_id} (tenant {trusted_tenant}) into '{collection_name}'")
    return IngestResult(document_id, trusted_tenant, collection_name, created)
