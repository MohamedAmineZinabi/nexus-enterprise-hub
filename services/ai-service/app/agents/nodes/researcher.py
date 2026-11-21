import asyncio
import logging
from typing import Dict, Any, List

from app.agents.state import AgentWorkflowState
from app.agents.text_utils import dedupe_queries
from app.rag.retriever import get_hybrid_retriever
from app.mcp.client_manager import get_mcp_client_manager
from app.mcp.tenant_tools import call_tenant_scoped_tool
from app.security.auth import require_tenant_id, TenantValidationError

logger = logging.getLogger("nexus.agents.researcher")

MAX_QUERIES_PER_ITERATION = 4
HITS_PER_QUERY = 3


async def researcher_node(state: AgentWorkflowState) -> Dict[str, Any]:
    """
    Researcher Node: runs every planned query through tenant-isolated hybrid search (off the event
    loop) and inspects the tenant's shared files via MCP. Failures are recorded in ``errors``
    instead of being silently swallowed.
    """
    user_prompt = state.get("user_prompt", "")
    task_id = state.get("task_id", "")
    plan = state.get("plan", []) or []
    errors: List[str] = list(state.get("errors", []))

    if state.get("status") == "SECURITY_BLOCKED" or state.get("metadata", {}).get("security_blocked"):
        logger.warning(f"[Researcher] Skipping retrieval for security-blocked task_id='{task_id}'.")
        return {"research_data": [], "current_node": "researcher", "status": "SECURITY_BLOCKED"}

    try:
        tenant_id = require_tenant_id(state.get("tenant_id"))
    except TenantValidationError as exc:
        errors.append(f"researcher: retrieval skipped ({exc})")
        logger.error(f"[Researcher] Refusing retrieval for task '{task_id}': {exc}")
        return {
            "research_data": list(state.get("research_data", [])),
            "errors": errors,
            "current_node": "researcher",
            "status": "RESEARCH_FAILED",
        }

    existing_data: List[Dict[str, Any]] = list(state.get("research_data", []))
    seen_contents = {item.get("content", "").strip() for item in existing_data if item.get("content")}

    # 1. Native hybrid search: the planned queries are used verbatim (no string surgery).
    queries = dedupe_queries(plan or [user_prompt], limit=MAX_QUERIES_PER_ITERATION)
    logger.info(f"[Researcher] task_id='{task_id}', tenant='{tenant_id}', queries={len(queries)}")

    retriever = get_hybrid_retriever()
    for q in queries:
        try:
            hits = await asyncio.to_thread(
                retriever.search,
                tenant_id=tenant_id,
                query=q,
                top_k=HITS_PER_QUERY,
                alpha=0.7,
                rerank=True,
            )
        except Exception as exc:
            errors.append(f"researcher: hybrid search failed for query '{q[:80]}' ({type(exc).__name__}: {exc})")
            logger.warning(f"[Researcher] Hybrid search failed for task '{task_id}': {exc}")
            continue

        for hit in hits:
            content_clean = hit.content.strip()
            if content_clean and content_clean not in seen_contents:
                seen_contents.add(content_clean)
                existing_data.append({
                    "source": "qdrant_hybrid_search",
                    "query": q,
                    "document_id": hit.document_id,
                    "citation": hit.citation,
                    "content": content_clean,
                    "score": hit.score,
                    "rerank_score": hit.rerank_score,
                    "metadata": hit.metadata,
                })

    # 2. Tenant-scoped MCP file inventory (tenant_id is injected server-side, never from the prompt)
    mcp_manager = get_mcp_client_manager()
    try:
        mcp_tools = await mcp_manager.list_all_tools()
        if any(t.get("name") == "list_shared_files" for t in mcp_tools):
            files_output = await call_tenant_scoped_tool(mcp_manager, "list_shared_files", {}, tenant_id)
            if files_output and "No files found" not in files_output and files_output not in seen_contents:
                seen_contents.add(files_output)
                existing_data.append({
                    "source": "mcp_tool",
                    "tool_name": "list_shared_files",
                    "citation": "MCP::enterprise_filesystem/list_shared_files",
                    "content": files_output,
                })
    except Exception as exc:
        errors.append(f"researcher: MCP file inventory failed ({type(exc).__name__}: {exc})")
        logger.warning(f"[Researcher] MCP tool invocation failed for task '{task_id}': {exc}")

    logger.info(f"[Researcher] Accumulated {len(existing_data)} evidence items ({len(errors)} errors so far).")
    return {
        "research_data": existing_data,
        "errors": errors,
        "current_node": "researcher",
        "status": "RESEARCH_COMPLETED",
    }
