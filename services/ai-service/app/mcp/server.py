import logging
from typing import Optional, List, Dict, Any
from mcp.server.mcpserver import MCPServer
from mcp.server.transport_security import TransportSecuritySettings

from app.core.config import settings
from app.rag.retriever import get_hybrid_retriever
from app.db.qdrant import get_qdrant_client
from qdrant_client.http import models

logger = logging.getLogger("nexus.mcp.server")

# Instantiate Nexus Hub MCP Server
nexus_mcp_server = MCPServer(
    name="nexus-hub-mcp",
    description="NexusEnterprise Hub native MCP server exposing enterprise RAG and document intelligence tools"
)


@nexus_mcp_server.tool(
    name="nexus_hybrid_search",
    description="Executes a dense vector and sparse BM25 hybrid search across indexed enterprise documents with Cross-Encoder reranking."
)
def nexus_hybrid_search(
    query: str,
    top_k: int = 5,
    alpha: float = 0.7,
    tenant_id: str = ""
) -> str:
    """Executes hybrid retrieval and returns formatted citations with relevance scores."""
    logger.info(f"[MCP Server] nexus_hybrid_search called: query='{query}', top_k={top_k}, alpha={alpha}, tenant='{tenant_id}'")
    try:
        retriever = get_hybrid_retriever()
        results = retriever.search(
            tenant_id=tenant_id or "default",
            query=query,
            top_k=top_k,
            alpha=alpha,
            rerank=True
        )

        if not results:
            return f"No documents found matching query: '{query}'"

        formatted_chunks = []
        for i, hit in enumerate(results, 1):
            score_display = hit.rerank_score if hit.rerank_score is not None else hit.score
            chunk_text = (
                f"[{i}] Citation: {hit.citation} (Score: {score_display:.4f})\n"
                f"Content: {hit.content.strip()}"
            )
            formatted_chunks.append(chunk_text)

        return "\n\n---\n\n".join(formatted_chunks)
    except Exception as exc:
        logger.error(f"[MCP Server] nexus_hybrid_search error: {exc}", exc_info=True)
        return f"Error executing hybrid search: {exc}"


@nexus_mcp_server.tool(
    name="nexus_document_summarizer",
    description="Retrieves indexed chunks for a given document from the vector store and builds an executive summary."
)
def nexus_document_summarizer(document_id: str, tenant_id: str = "") -> str:
    """Summarizes a specific document using its indexed chunks."""
    logger.info(f"[MCP Server] nexus_document_summarizer called: document_id='{document_id}', tenant='{tenant_id}'")
    try:
        client = get_qdrant_client()
        must_conditions = [
            models.FieldCondition(key="document_id", match=models.MatchValue(value=document_id))
        ]
        if tenant_id:
            must_conditions.append(
                models.FieldCondition(key="tenant_id", match=models.MatchValue(value=tenant_id))
            )

        query_res = client.query_points(
            collection_name=settings.qdrant_collection_name,
            query_filter=models.Filter(must=must_conditions),
            limit=25,
            with_payload=True
        )

        points = query_res.points
        if not points:
            return f"No indexed content found for document '{document_id}'."

        # Sort chunks sequentially
        sorted_points = sorted(
            points,
            key=lambda p: int((p.payload or {}).get("metadata", {}).get("chunk_index", 0))
        )

        chunks_text = [(p.payload or {}).get("content", "") for p in sorted_points]
        total_chunks = len(chunks_text)
        preview_text = "\n\n".join(chunks_text[:4])
        if total_chunks > 4:
            preview_text += f"\n\n[... and {total_chunks - 4} additional chunks ...]"

        doc_name = (sorted_points[0].payload or {}).get("metadata", {}).get("filename", document_id)
        return (
            f"=== Executive Document Summary ===\n"
            f"Document: {doc_name}\n"
            f"Document ID: {document_id}\n"
            f"Total Chunks: {total_chunks}\n\n"
            f"{preview_text}"
        )
    except Exception as exc:
        logger.error(f"[MCP Server] nexus_document_summarizer error: {exc}", exc_info=True)
        return f"Error summarizing document {document_id}: {exc}"


def get_mcp_sse_app():
    """Returns the Starlette ASGI application for SSE MCP client connections."""
    security = TransportSecuritySettings(
        enable_dns_rebinding_protection=False
    )
    return nexus_mcp_server.sse_app(transport_security=security)

