import logging
from typing import Dict, Any, List

from app.agents.state import AgentWorkflowState
from app.rag.retriever import get_hybrid_retriever
from app.mcp.client_manager import get_mcp_client_manager
from app.mcp.tool_adapter import convert_mcp_to_langchain_tool

logger = logging.getLogger("nexus.agents.researcher")


async def researcher_node(state: AgentWorkflowState) -> Dict[str, Any]:
    """
    Researcher Node: Binds native RAG and MCP tools, executing iterative queries against
    Qdrant vector collections and shared storage tools to accumulate factual grounding evidence.
    """
    user_prompt = state.get("user_prompt", "")
    task_id = state.get("task_id", "")
    tenant_id = state.get("tenant_id", "")
    plan = state.get("plan", [])
    critique = state.get("critique", {})

    logger.info(
        f"[Researcher] Executing research for task_id='{task_id}', tenant='{tenant_id}', "
        f"plan_steps={len(plan)}"
    )

    existing_data: List[Dict[str, Any]] = list(state.get("research_data", []))
    seen_contents = {item.get("content", "").strip() for item in existing_data if item.get("content")}

    # 1. Native RAG Hybrid Search across Plan Sub-Tasks & User Prompt
    retriever = get_hybrid_retriever()
    queries_to_run = [user_prompt]
    for subtask in plan:
        clean_subtask = subtask.split("regarding")[-1].split("for")[-1].replace("'", "").strip()
        if clean_subtask and clean_subtask not in queries_to_run:
            queries_to_run.append(clean_subtask)

    if critique and not critique.get("is_valid", False):
        feedback = critique.get("feedback", "")
        if feedback:
            queries_to_run.append(feedback)

    for q in queries_to_run[:4]:
        try:
            hits = retriever.search(
                tenant_id=tenant_id or "default",
                query=q,
                top_k=3,
                alpha=0.7,
                rerank=True
            )
            for hit in hits:
                content_clean = hit.content.strip()
                if content_clean and content_clean not in seen_contents:
                    seen_contents.add(content_clean)
                    existing_data.append({
                        "source": "qdrant_hybrid_search",
                        "citation": hit.citation,
                        "content": content_clean,
                        "score": hit.score,
                        "rerank_score": hit.rerank_score,
                        "metadata": hit.metadata
                    })
        except Exception as exc:
            logger.warning(f"[Researcher] Error executing hybrid search for query '{q}': {exc}")

    # 2. Dynamic MCP Tool Invocations
    mcp_manager = get_mcp_client_manager()
    try:
        mcp_tools = await mcp_manager.list_all_tools()
        tool_names = {t.get("name") for t in mcp_tools}

        # If enterprise filesystem tool is available, inspect shared files
        if "list_shared_files" in tool_names:
            fs_tool = next(t for t in mcp_tools if t.get("name") == "list_shared_files")
            lc_tool = convert_mcp_to_langchain_tool(fs_tool, mcp_manager)
            try:
                files_output = await lc_tool.ainvoke({"subpath": tenant_id})
                if files_output and "No files found" not in files_output and files_output not in seen_contents:
                    seen_contents.add(files_output)
                    existing_data.append({
                        "source": "mcp_tool",
                        "tool_name": "list_shared_files",
                        "citation": "MCP::enterprise_filesystem/list_shared_files",
                        "content": files_output
                    })
            except Exception as e:
                logger.warning(f"[Researcher] Failed to invoke list_shared_files: {e}")
    except Exception as exc:
        logger.warning(f"[Researcher] Error querying MCP tools: {exc}")

    logger.info(f"[Researcher] Accumulated {len(existing_data)} total research evidence items.")

    return {
        "research_data": existing_data,
        "current_node": "researcher",
        "status": "RESEARCH_COMPLETED"
    }

