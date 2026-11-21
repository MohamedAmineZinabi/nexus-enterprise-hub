"""
Regression tests for the Sprint 1 security and correctness fixes:
  C1 internal API key (HTTP + gRPC) and tenant enforcement, C2 path traversal,
  C8 agent graph routing / honest deterministic output, real vector deletion counts.
"""
import os
import uuid
from types import SimpleNamespace

import grpc
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END
from qdrant_client import QdrantClient
from qdrant_client.http import models

from app.core.config import settings
from app.security.auth import (
    INTERNAL_API_KEY_HEADER,
    InternalApiKeyMiddleware,
    InternalApiKeyServerInterceptor,
    TenantValidationError,
    require_tenant_id,
)

TENANT_A = "11111111-1111-4111-8111-111111111111"
TENANT_B = "22222222-2222-4222-8222-222222222222"


# -----------------------------------------------------------------------------
# C1: internal API key on HTTP
# -----------------------------------------------------------------------------
def _protected_app() -> FastAPI:
    app = FastAPI()
    app.add_middleware(InternalApiKeyMiddleware)

    @app.get("/healthz")
    async def healthz():
        return {"status": "ok"}

    @app.post("/api/v1/rag/search")
    async def search():
        return {"ok": True}

    @app.get("/mcp/sse")
    async def mcp():
        return {"ok": True}

    return app


def test_http_requires_internal_api_key():
    client = TestClient(_protected_app())
    assert client.get("/healthz").status_code == 200
    assert client.post("/api/v1/rag/search").status_code == 401
    assert client.get("/mcp/sse").status_code == 401
    assert client.get("/docs").status_code == 401
    assert client.post("/api/v1/rag/search", headers={INTERNAL_API_KEY_HEADER: "wrong-key-wrong-key"}).status_code == 401
    ok = client.post("/api/v1/rag/search", headers={INTERNAL_API_KEY_HEADER: settings.internal_api_key})
    assert ok.status_code == 200


def test_require_tenant_id_rejects_missing_and_malformed():
    assert require_tenant_id(f"  {TENANT_A} ") == TENANT_A
    for bad in ("", "   ", None, "../etc", "a/b", "x" * 65, "default tenant"):
        with pytest.raises(TenantValidationError):
            require_tenant_id(bad)


def test_collection_allowlist():
    assert settings.resolve_collection(None) == settings.qdrant_collection_name
    assert settings.resolve_collection("") == settings.qdrant_collection_name
    with pytest.raises(ValueError):
        settings.resolve_collection("attacker_collection")


# -----------------------------------------------------------------------------
# C1: internal API key on gRPC
# -----------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_grpc_interceptor_rejects_calls_without_internal_key():
    from app.grpc.generated.ai.v1 import rag_service_pb2, rag_service_pb2_grpc

    class EchoRag(rag_service_pb2_grpc.RagServiceServicer):
        async def HybridSearch(self, request, context):
            return rag_service_pb2.HybridSearchResponse(query_interpreted=request.query)

    server = grpc.aio.server(interceptors=[InternalApiKeyServerInterceptor()])
    rag_service_pb2_grpc.add_RagServiceServicer_to_server(EchoRag(), server)
    port = server.add_insecure_port("127.0.0.1:0")
    await server.start()
    try:
        async with grpc.aio.insecure_channel(f"127.0.0.1:{port}") as channel:
            stub = rag_service_pb2_grpc.RagServiceStub(channel)
            req = rag_service_pb2.HybridSearchRequest(query="hello")

            with pytest.raises(grpc.aio.AioRpcError) as no_key:
                await stub.HybridSearch(req)
            assert no_key.value.code() == grpc.StatusCode.UNAUTHENTICATED

            with pytest.raises(grpc.aio.AioRpcError) as bad_key:
                await stub.HybridSearch(req, metadata=((INTERNAL_API_KEY_HEADER, "nope-nope-nope-nope"),))
            assert bad_key.value.code() == grpc.StatusCode.UNAUTHENTICATED

            ok = await stub.HybridSearch(req, metadata=((INTERNAL_API_KEY_HEADER, settings.internal_api_key),))
            assert ok.query_interpreted == "hello"
    finally:
        await server.stop(None)


# -----------------------------------------------------------------------------
# C2: filesystem MCP sandbox
# -----------------------------------------------------------------------------
@pytest.fixture
def fs_server(tmp_path, monkeypatch):
    import app.mcp.servers.filesystem_server as fs

    root = tmp_path / "shared-uploads"
    (root / TENANT_A / "reports").mkdir(parents=True)
    (root / TENANT_B).mkdir(parents=True)
    (root / TENANT_A / "reports" / "a.txt").write_text("tenant A report", encoding="utf-8")
    (root / TENANT_B / "b-secret.txt").write_text("tenant B secret", encoding="utf-8")
    (tmp_path / "outside.txt").write_text("host secret", encoding="utf-8")
    monkeypatch.setattr(fs, "SHARED_ROOT", os.path.realpath(str(root)))
    return fs, root, tmp_path


def test_list_shared_files_is_tenant_scoped(fs_server):
    fs, _, _ = fs_server
    listing = fs.list_shared_files(TENANT_A)
    assert "reports/a.txt" in listing
    assert "b-secret" not in listing
    assert "No files found" in fs.list_shared_files(TENANT_A, "does-not-exist")


@pytest.mark.parametrize("evil", [
    "../" + TENANT_B + "/b-secret.txt",
    "../../outside.txt",
    "reports/../../" + TENANT_B + "/b-secret.txt",
    "/etc/passwd",
    "..\\..\\outside.txt",
])
def test_read_preview_rejects_traversal(fs_server, evil):
    fs, _, _ = fs_server
    with pytest.raises(fs.PathAccessError):
        fs.read_shared_file_preview(TENANT_A, evil)


def test_listing_rejects_traversal_and_bad_tenant(fs_server):
    fs, _, _ = fs_server
    with pytest.raises(fs.PathAccessError):
        fs.list_shared_files(TENANT_A, "..")
    for bad_tenant in ("", "..", "../x", "a/b"):
        with pytest.raises(fs.PathAccessError):
            fs.list_shared_files(bad_tenant)


def test_symlink_escape_is_blocked(fs_server):
    fs, root, tmp_path = fs_server
    link = root / TENANT_A / "escape.txt"
    try:
        os.symlink(tmp_path / "outside.txt", link)
    except (OSError, NotImplementedError):
        pytest.skip("symlinks not supported on this platform")
    with pytest.raises(fs.PathAccessError):
        fs.read_shared_file_preview(TENANT_A, "escape.txt")
    assert "escape.txt" not in fs.list_shared_files(TENANT_A)


def test_reads_own_file(fs_server):
    fs, _, _ = fs_server
    assert "tenant A report" in fs.read_shared_file_preview(TENANT_A, "reports/a.txt")


@pytest.mark.asyncio
async def test_tenant_scoped_tool_allowlist_and_override():
    from app.mcp.tenant_tools import ToolNotAllowedError, call_tenant_scoped_tool

    calls = []

    class Manager:
        async def call_tool(self, server_name, tool_name, arguments):
            calls.append((server_name, tool_name, arguments))
            return "ok"

    await call_tenant_scoped_tool(Manager(), "read_shared_file_preview", {"filename": "x", "tenant_id": TENANT_B}, TENANT_A)
    assert calls[-1] == ("enterprise_filesystem", "read_shared_file_preview", {"filename": "x", "tenant_id": TENANT_A})

    with pytest.raises(ToolNotAllowedError):
        await call_tenant_scoped_tool(Manager(), "run_shell", {}, TENANT_A)
    with pytest.raises(TenantValidationError):
        await call_tenant_scoped_tool(Manager(), "list_shared_files", {}, "")


# -----------------------------------------------------------------------------
# C8: agent graph behaviour (deterministic mode)
# -----------------------------------------------------------------------------
class RecordingRetriever:
    def __init__(self, hits=None):
        self.queries = []
        self.hits = hits or []

    def search(self, tenant_id, query, **kwargs):
        self.queries.append((tenant_id, query))
        return list(self.hits)


class NoMcp:
    async def list_all_tools(self):
        return []


def _hit(content, citation="policy.txt (Page 1)", score=0.9):
    return SimpleNamespace(
        chunk_id=str(uuid.uuid4()), document_id="doc-1", content=content,
        score=score, rerank_score=score, metadata={}, citation=citation,
    )


@pytest.fixture
def graph_with(monkeypatch):
    import app.agents.nodes.researcher as researcher_module
    from app.agents.graph import create_agent_graph

    def _build(retriever):
        monkeypatch.setattr(researcher_module, "get_hybrid_retriever", lambda: retriever)
        monkeypatch.setattr(researcher_module, "get_mcp_client_manager", lambda: NoMcp())
        return create_agent_graph(checkpointer=MemorySaver())

    return _build


def test_critic_routes_rejections_to_planner_until_max_iterations(monkeypatch):
    from app.agents.graph import _should_continue_critic

    monkeypatch.setattr(settings, "max_agent_iterations", 3)
    rejected = {"critique": {"is_valid": False}}
    assert _should_continue_critic({**rejected, "iteration_count": 1}) == "planner"
    assert _should_continue_critic({**rejected, "iteration_count": 2}) == "planner"
    assert _should_continue_critic({**rejected, "iteration_count": 3}) == END
    assert _should_continue_critic({"critique": {"is_valid": True}, "iteration_count": 1}) == END


@pytest.mark.asyncio
async def test_loop_is_bounded_and_replans_when_no_evidence(graph_with, monkeypatch):
    from app.agents.state import build_initial_state

    monkeypatch.setattr(settings, "max_agent_iterations", 3)
    retriever = RecordingRetriever(hits=[])
    graph = graph_with(retriever)
    state = build_initial_state("t-1", TENANT_A, "audit log retention period", "trace-1")
    final = await graph.ainvoke(state, config={"configurable": {"thread_id": "t-1"}})

    assert final["iteration_count"] == 3
    assert final["critique"]["is_valid"] is False
    assert "No relevant content was found" in final["draft_report"]
    assert final["token_usage"] == 0
    assert final["llm_mode"] == "deterministic"
    # every search is tenant-scoped
    assert {t for t, _ in retriever.queries} == {TENANT_A}


@pytest.mark.asyncio
async def test_researcher_uses_planned_queries_verbatim(graph_with):
    from app.agents.state import build_initial_state

    retriever = RecordingRetriever(hits=[_hit("Data information is retained for 417 days.")])
    graph = graph_with(retriever)
    prompt = "data information retention for audits"
    final = await graph.ainvoke(
        build_initial_state("t-2", TENANT_A, prompt, "trace-2"),
        config={"configurable": {"thread_id": "t-2"}},
    )
    queries = [q for _, q in retriever.queries]
    assert prompt in queries
    # Regression: the old splitter turned "information" into "mation"
    assert all(not q.startswith("mation") and q.strip() for q in queries)
    assert final["critique"]["is_valid"] is True


@pytest.mark.asyncio
async def test_deterministic_report_is_honest_and_cited(graph_with):
    from app.agents.state import build_initial_state

    retriever = RecordingRetriever(hits=[_hit("Audit logs must be retained for 417 days.")])
    graph = graph_with(retriever)
    final = await graph.ainvoke(
        build_initial_state("t-3", TENANT_A, "How long are audit logs retained?", "trace-3"),
        config={"configurable": {"thread_id": "t-3"}},
    )
    report = final["draft_report"]
    assert "Deterministic mode" in report
    assert "417 days" in report and "[^1]" in report and "[^1]: **policy.txt (Page 1)**" in report
    for fabricated in ("zero-hallucination", "Cryptographic & Protocol Verification", "mandates"):
        assert fabricated not in report


@pytest.mark.asyncio
async def test_retrieval_errors_are_surfaced(graph_with):
    from app.agents.state import build_initial_state

    class Exploding:
        def search(self, **kwargs):
            raise RuntimeError("qdrant down")

    graph = graph_with(Exploding())
    final = await graph.ainvoke(
        build_initial_state("t-4", TENANT_A, "anything", "trace-4"),
        config={"configurable": {"thread_id": "t-4"}},
    )
    assert any("qdrant down" in e for e in final["errors"])
    assert "## Retrieval Issues" in final["draft_report"]


# -----------------------------------------------------------------------------
# Real vector deletion counts (tenant-scoped)
# -----------------------------------------------------------------------------
class _Ctx:
    def __init__(self):
        self.code = None
        self.details = None

    def set_code(self, code):
        self.code = code

    def set_details(self, details):
        self.details = details


def test_delete_document_vectors_reports_real_count_and_respects_tenant():
    from app.grpc.generated.ai import common_pb2
    from app.grpc.generated.ai.v1 import rag_service_pb2
    from app.grpc.servicers.rag_servicer import RagServiceServicer

    client = QdrantClient(":memory:")
    col = settings.qdrant_collection_name
    client.create_collection(col, vectors_config=models.VectorParams(size=4, distance=models.Distance.COSINE))
    points = [
        models.PointStruct(id=str(uuid.uuid4()), vector=[0.1, 0.2, 0.3, 0.4],
                           payload={"tenant_id": TENANT_A, "document_id": "doc-1"})
        for _ in range(3)
    ] + [
        models.PointStruct(id=str(uuid.uuid4()), vector=[0.1, 0.2, 0.3, 0.4],
                           payload={"tenant_id": TENANT_B, "document_id": "doc-1"})
    ]
    client.upsert(col, points=points)

    servicer = object.__new__(RagServiceServicer)
    servicer.qdrant_client = client

    resp = servicer.DeleteDocumentVectors(
        rag_service_pb2.DeleteDocumentRequest(
            context=common_pb2.TenantContext(tenant_id=TENANT_A), document_id="doc-1"
        ),
        _Ctx(),
    )
    assert resp.success is True
    assert resp.vectors_deleted == 3
    assert client.count(col, exact=True).count == 1  # tenant B's vector untouched

    ctx = _Ctx()
    missing_tenant = servicer.DeleteDocumentVectors(rag_service_pb2.DeleteDocumentRequest(document_id="doc-1"), ctx)
    assert missing_tenant.success is False and ctx.code == grpc.StatusCode.INVALID_ARGUMENT
