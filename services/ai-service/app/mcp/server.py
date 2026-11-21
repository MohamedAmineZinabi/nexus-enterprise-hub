import asyncio
import logging
from mcp.server.mcpserver import MCPServer
from mcp.server.transport_security import TransportSecuritySettings

from app.core.config import settings
from app.rag.retriever import get_hybrid_retriever
from app.db.qdrant import get_qdrant_client
from app.security.auth import require_tenant_id
from qdrant_client.http import models

logger = logging.getLogger("nexus.mcp.server")

# Instantiate Nexus Hub MCP Server
nexus_mcp_server = MCPServer(
    name="nexus-hub-mcp",
    description="NexusEnterprise Hub native MCP server exposing enterprise RAG and document intelligence tools"
)


@nexus_mcp_server.tool(
    name="nexus_hybrid_search",
    description="Executes a dense vector and sparse BM25 hybrid search across the tenant's indexed documents with Cross-Encoder reranking."
)
async def nexus_hybrid_search(
    query: str,
    tenant_id: str,
    top_k: int = 5,
    alpha: float = 0.7,
) -> str:
    """Executes tenant-isolated hybrid retrieval and returns formatted citations with relevance scores."""
    trusted_tenant = require_tenant_id(tenant_id)
    logger.info(f"[MCP Server] nexus_hybrid_search called: top_k={top_k}, alpha={alpha}, tenant='{trusted_tenant}'")
    retriever = get_hybrid_retriever()
    results = await asyncio.to_thread(
        retriever.search,
        tenant_id=trusted_tenant,
        query=query,
        top_k=max(1, min(int(top_k), 50)),
        alpha=alpha,
        rerank=True,
    )

    if not results:
        return "No documents found matching the query."

    formatted_chunks = []
    for i, hit in enumerate(results, 1):
        score_display = hit.rerank_score if hit.rerank_score else hit.score
        formatted_chunks.append(
            f"[{i}] Citation: {hit.citation} (Score: {score_display:.4f})\n"
            f"Content: {hit.content.strip()}"
        )
    return "\n\n---\n\n".join(formatted_chunks)


def _summarize_document_sync(document_id: str, tenant_id: str) -> str:
    client = get_qdrant_client()
    query_res = client.query_points(
        collection_name=settings.qdrant_collection_name,
        query_filter=models.Filter(must=[
            models.FieldCondition(key="tenant_id", match=models.MatchValue(value=tenant_id)),
            models.FieldCondition(key="document_id", match=models.MatchValue(value=document_id)),
        ]),
        limit=25,
        with_payload=True
    )

    points = query_res.points
    if not points:
        return f"No indexed content found for document '{document_id}'."

    sorted_points = sorted(points, key=lambda p: int((p.payload or {}).get("chunk_index", 0) or 0))
    chunks_text = [(p.payload or {}).get("content", "") for p in sorted_points]
    total_chunks = len(chunks_text)
    preview_text = "\n\n".join(chunks_text[:4])
    if total_chunks > 4:
        preview_text += f"\n\n[... and {total_chunks - 4} additional chunks ...]"

    doc_name = (sorted_points[0].payload or {}).get("metadata", {}).get("filename", document_id)
    return (
        f"=== Document Summary (extractive) ===\n"
        f"Document: {doc_name}\n"
        f"Document ID: {document_id}\n"
        f"Total Chunks: {total_chunks}\n\n"
        f"{preview_text}"
    )


@nexus_mcp_server.tool(
    name="nexus_document_summarizer",
    description="Retrieves the tenant's indexed chunks for a given document and returns an extractive summary."
)
async def nexus_document_summarizer(document_id: str, tenant_id: str) -> str:
    """Summarizes a specific document using its indexed chunks (always tenant-filtered)."""
    trusted_tenant = require_tenant_id(tenant_id)
    if not (document_id or "").strip():
        raise ValueError("document_id is required")
    logger.info(f"[MCP Server] nexus_document_summarizer called: document_id='{document_id}', tenant='{trusted_tenant}'")
    return await asyncio.to_thread(_summarize_document_sync, document_id.strip(), trusted_tenant)


def get_mcp_sse_app():
    """Returns the Starlette ASGI application for SSE MCP client connections (DNS-rebinding protected)."""
    allowed_hosts = settings.mcp_allowed_host_list
    allowed_origins = [f"http://{h}" for h in allowed_hosts] + [f"https://{h}" for h in allowed_hosts]
    security = TransportSecuritySettings(
        enable_dns_rebinding_protection=True,
        allowed_hosts=allowed_hosts,
        allowed_origins=allowed_origins,
    )
    return nexus_mcp_server.sse_app(transport_security=security)
