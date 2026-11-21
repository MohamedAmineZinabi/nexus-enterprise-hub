"""Tests for asynchronous document ingestion (document.ingest.requested -> document.ingest.completed)."""
import json
import os
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest
from qdrant_client import QdrantClient
from qdrant_client.http import models

import app.rag.ingestion as ingestion
from app.core.config import settings
from app.kafka.ingest_consumer import DocumentIngestConsumer
from app.mcp.tenant_tools import InvalidToolArgumentError, call_tenant_scoped_tool

TENANT_A = "11111111-1111-4111-8111-111111111111"
TENANT_B = "22222222-2222-4222-8222-222222222222"


class FakeEmbeddings:
    dimension = 4

    def embed_documents(self, texts):
        return [[0.1, 0.2, 0.3, 0.4] for _ in texts]


@pytest.fixture
def ingest_env(tmp_path, monkeypatch):
    shared = tmp_path / "shared-uploads"
    (shared / TENANT_A).mkdir(parents=True)
    (shared / TENANT_B).mkdir(parents=True)
    monkeypatch.setattr(settings, "shared_uploads_dir", str(shared))

    client = QdrantClient(":memory:")
    client.create_collection(
        settings.qdrant_collection_name,
        vectors_config=models.VectorParams(size=4, distance=models.Distance.COSINE),
    )
    monkeypatch.setattr(ingestion, "get_qdrant_client", lambda: client)
    monkeypatch.setattr(ingestion, "init_qdrant_collection", lambda name=None: None)
    monkeypatch.setattr(ingestion, "get_embedding_service", lambda: FakeEmbeddings())
    return shared, client


def _write_doc(shared, tenant, name, paragraphs=40):
    path = shared / tenant / name
    path.write_text("\n\n".join(f"Paragraph {i}: audit logs are retained for 417 days." for i in range(paragraphs)), encoding="utf-8")
    return path


def _count(client, tenant, doc):
    return client.count(
        settings.qdrant_collection_name,
        count_filter=ingestion.tenant_document_filter(tenant, doc),
        exact=True,
    ).count


def test_ingest_document_indexes_all_chunks_for_tenant(ingest_env):
    shared, client = ingest_env
    path = _write_doc(shared, TENANT_A, "doc-1_policy.txt")
    result = ingestion.ingest_document(TENANT_A, "doc-1", str(path), "text/plain", chunk_size=200, chunk_overlap=20)
    assert result.chunks_created > 1
    assert _count(client, TENANT_A, "doc-1") == result.chunks_created
    # idempotent re-ingestion (redelivery) does not duplicate vectors
    again = ingestion.ingest_document(TENANT_A, "doc-1", str(path), "text/plain", chunk_size=200, chunk_overlap=20)
    assert _count(client, TENANT_A, "doc-1") == again.chunks_created


def test_ingest_rejects_files_outside_tenant_directory(ingest_env):
    shared, _ = ingest_env
    other = _write_doc(shared, TENANT_B, "doc-2_secret.txt")
    with pytest.raises(ingestion.InvalidIngestRequest):
        ingestion.ingest_document(TENANT_A, "doc-2", str(other), "text/plain")
    with pytest.raises(ingestion.InvalidIngestRequest):
        ingestion.ingest_document(TENANT_A, "doc-2", f"{shared}/{TENANT_A}/../{TENANT_B}/doc-2_secret.txt", "text/plain")
    with pytest.raises(ingestion.InvalidIngestRequest):
        ingestion.ingest_document(TENANT_A, "doc-2", str(shared / TENANT_A / "x.txt"), "text/plain", collection="evil")


def test_ingest_missing_file(ingest_env):
    shared, _ = ingest_env
    with pytest.raises(ingestion.SourceFileMissing):
        ingestion.ingest_document(TENANT_A, "doc-3", str(shared / TENANT_A / "gone.txt"), "text/plain")


def test_document_deleted_during_ingest_purges_vectors(ingest_env, monkeypatch):
    shared, client = ingest_env
    path = _write_doc(shared, TENANT_A, "doc-4_policy.txt")

    class DeletingEmbeddings(FakeEmbeddings):
        def embed_documents(self, texts):
            if path.exists():
                os.remove(path)  # simulate DELETE /documents/{id} racing the ingestion
            return super().embed_documents(texts)

    monkeypatch.setattr(ingestion, "get_embedding_service", lambda: DeletingEmbeddings())
    with pytest.raises(ingestion.DocumentDeletedDuringIngest):
        ingestion.ingest_document(TENANT_A, "doc-4", str(path), "text/plain", chunk_size=200, chunk_overlap=20)
    assert _count(client, TENANT_A, "doc-4") == 0


# -----------------------------------------------------------------------------
# Kafka consumer
# -----------------------------------------------------------------------------
def _publisher():
    pub = MagicMock()
    pub.publish_ingest_completed = AsyncMock()
    pub.publish_dlq = AsyncMock()
    return pub


def _lock(acquired=True):
    @asynccontextmanager
    async def _cm(key, timeout_seconds=300):
        _cm.keys.append(key)
        yield SimpleNamespace(acquired=acquired)
    _cm.keys = []
    return _cm


def _request(**overrides):
    payload = {
        "documentId": "doc-9",
        "tenantId": TENANT_A,
        "traceId": "trace-9",
        "filePath": f"/shared-uploads/{TENANT_A}/doc-9_policy.txt",
        "fileName": "policy.txt",
        "mimeType": "text/plain",
        "chunkSize": None,
        "chunkOverlap": None,
        "vectorCollection": "enterprise_docs",
        "metadata": {"filename": "policy.txt"},
    }
    payload.update(overrides)
    return json.dumps(payload)


@pytest.mark.asyncio
async def test_consumer_publishes_indexed(monkeypatch):
    import app.kafka.ingest_consumer as mod

    lock = _lock()
    monkeypatch.setattr(mod, "distributed_lock", lock)
    monkeypatch.setattr(mod, "ingest_document", lambda **kw: ingestion.IngestResult("doc-9", TENANT_A, "enterprise_docs", 42))
    pub = _publisher()

    status = await DocumentIngestConsumer("localhost:9092").process_message(_request(), pub)

    assert status == "INDEXED"
    assert lock.keys == ["lock:doc:doc-9"]
    kwargs = pub.publish_ingest_completed.await_args.kwargs
    assert kwargs["status"] == "INDEXED" and kwargs["chunks_created"] == 42 and kwargs["tenant_id"] == TENANT_A
    assert kwargs["duration_ms"] >= 0
    pub.publish_dlq.assert_not_awaited()


@pytest.mark.asyncio
async def test_consumer_reports_failed_and_dlq(monkeypatch):
    import app.kafka.ingest_consumer as mod

    monkeypatch.setattr(mod, "distributed_lock", _lock())

    def boom(**kw):
        raise ingestion.SourceFileMissing("Document file not found: doc-9_policy.txt")

    monkeypatch.setattr(mod, "ingest_document", boom)
    pub = _publisher()
    status = await DocumentIngestConsumer("localhost:9092").process_message(_request(), pub, msg_meta={"topic": "t", "partition": 0, "offset": 7})

    assert status == "FAILED"
    kwargs = pub.publish_ingest_completed.await_args.kwargs
    assert kwargs["status"] == "FAILED" and "SourceFileMissing" in kwargs["error_details"]
    assert pub.publish_dlq.await_args.kwargs["offset"] == 7


@pytest.mark.asyncio
async def test_consumer_rejects_invalid_tenant_without_completion_event(monkeypatch):
    import app.kafka.ingest_consumer as mod

    monkeypatch.setattr(mod, "distributed_lock", _lock())
    pub = _publisher()
    status = await DocumentIngestConsumer("localhost:9092").process_message(_request(tenantId="../x"), pub)
    assert status == "FAILED"
    pub.publish_dlq.assert_awaited_once()
    pub.publish_ingest_completed.assert_not_awaited()  # cannot attribute the outcome to a valid tenant


@pytest.mark.asyncio
async def test_consumer_skips_duplicate_when_lock_held(monkeypatch):
    import app.kafka.ingest_consumer as mod

    monkeypatch.setattr(mod, "distributed_lock", _lock(acquired=False))
    monkeypatch.setattr(mod, "ingest_document", MagicMock())
    pub = _publisher()
    assert await DocumentIngestConsumer("localhost:9092").process_message(_request(), pub) is None
    mod.ingest_document.assert_not_called()
    pub.publish_ingest_completed.assert_not_awaited()


@pytest.mark.asyncio
async def test_consumer_handles_poison_pill(monkeypatch):
    pub = _publisher()
    assert await DocumentIngestConsumer("localhost:9092").process_message("{not json", pub) == "FAILED"
    pub.publish_dlq.assert_awaited_once()


# -----------------------------------------------------------------------------
# Path-argument validation -> HTTP 400 / INVALID_ARGUMENT
# -----------------------------------------------------------------------------
@pytest.mark.asyncio
@pytest.mark.parametrize("args", [
    {"filename": "../../etc/hostname"},
    {"filename": "/etc/passwd"},
    {"filename": "C:\\Windows\\win.ini"},
    {"subpath": ".."},
    {"subpath": "reports/../../x"},
])
async def test_filesystem_path_arguments_rejected_before_execution(args):
    manager = MagicMock()
    manager.call_tool = AsyncMock()
    tool = "read_shared_file_preview" if "filename" in args else "list_shared_files"
    with pytest.raises(InvalidToolArgumentError):
        await call_tenant_scoped_tool(manager, tool, args, TENANT_A)
    manager.call_tool.assert_not_awaited()


def test_rest_mcp_call_maps_path_traversal_to_400(monkeypatch):
    from fastapi.testclient import TestClient
    import app.main as main_module
    from app.security.auth import INTERNAL_API_KEY_HEADER

    client = TestClient(main_module.app)  # lifespan not started: no infrastructure needed
    resp = client.post(
        "/api/v1/mcp/call",
        headers={INTERNAL_API_KEY_HEADER: settings.internal_api_key},
        json={"tool_name": "read_shared_file_preview", "tenant_id": TENANT_A, "arguments": {"filename": "../../etc/hostname"}},
    )
    assert resp.status_code == 400
