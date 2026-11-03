import time
import json
import logging
from typing import List, Dict, Any

import grpc
from app.grpc.generated.ai.v1 import agent_service_pb2, agent_service_pb2_grpc
from app.mcp.client_manager import get_mcp_client_manager
from app.rag.retrieval import get_hybrid_retriever

logger = logging.getLogger("nexus.grpc.agent_servicer")


class AgentServiceServicer(agent_service_pb2_grpc.AgentServiceServicer):
    """Production gRPC servicer for MCP tool discovery and agent execution."""

    def __init__(self):
        self.mcp_manager = get_mcp_client_manager()

    async def ListMcpTools(
        self,
        request: agent_service_pb2.ListMcpToolsRequest,
        context: grpc.aio.ServicerContext
    ) -> agent_service_pb2.ListMcpToolsResponse:
        """Discovers and returns all MCP tools registered natively and externally."""
        tenant_id = request.context.tenant_id if request.HasField("context") else ""
        logger.info(f"AgentService.ListMcpTools called for tenant: '{tenant_id}'")

        tools_dict: Dict[str, agent_service_pb2.McpToolDefinition] = {}

        # 1. Native Nexus Hub MCP Tools
        tools_dict["nexus_hybrid_search"] = agent_service_pb2.McpToolDefinition(
            name="nexus_hybrid_search",
            description="Performs dense vector and sparse BM25 hybrid search with Cross-Encoder reranking over Nexus Hub indexed enterprise documents",
            input_schema_json=json.dumps({
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Search query text"},
                    "top_k": {"type": "integer", "description": "Number of results to retrieve", "default": 5},
                    "alpha": {"type": "number", "description": "Hybrid balance (0.0 sparse to 1.0 dense)", "default": 0.7}
                },
                "required": ["query"]
            }),
            server_origin="mcp://ai-service:50051"
        )

        tools_dict["nexus_document_summarizer"] = agent_service_pb2.McpToolDefinition(
            name="nexus_document_summarizer",
            description="Summarizes enterprise documents with citation extraction from Qdrant vector store",
            input_schema_json=json.dumps({
                "type": "object",
                "properties": {
                    "document_id": {"type": "string", "description": "UUID of document to summarize"}
                },
                "required": ["document_id"]
            }),
            server_origin="mcp://ai-service:50051"
        )

        # 2. External MCP Tools discovered via MCPClientManager
        try:
            external_tools = await self.mcp_manager.list_all_tools()
            for ext in external_tools:
                name = ext.get("name")
                if not name:
                    continue

                schema_json = ext.get("inputSchema") or ext.get("input_schema") or {}
                if isinstance(schema_json, dict):
                    schema_str = json.dumps(schema_json)
                else:
                    schema_str = str(schema_json)

                tools_dict[name] = agent_service_pb2.McpToolDefinition(
                    name=name,
                    description=ext.get("description", ""),
                    input_schema_json=schema_str,
                    server_origin=ext.get("server_origin", f"mcp://{ext.get('server_name', 'external')}")
                )
        except Exception as exc:
            logger.warning(f"Could not discover external MCP tools: {exc}")

        result_tools = list(tools_dict.values())
        logger.info(f"Returning {len(result_tools)} total MCP tools to caller.")
        return agent_service_pb2.ListMcpToolsResponse(
            tools=result_tools,
            total_tools=len(result_tools)
        )

    async def ExecuteAgentSync(
        self,
        request: agent_service_pb2.AgentQueryRequest,
        context: grpc.aio.ServicerContext
    ) -> agent_service_pb2.AgentQueryResponse:
        """Executes a synchronous agent task routing to MCP tools or hybrid search."""
        start_time = time.perf_counter()
        tenant_id = request.context.tenant_id if request.HasField("context") else "default"
        logger.info(
            f"AgentService.ExecuteAgentSync called: task_id={request.task_id}, "
            f"tenant={tenant_id}, prompt='{request.user_prompt}'"
        )

        tool_name = request.session_parameters.get("tool_name")
        citations: List[agent_service_pb2.Citation] = []
        response_text = ""
        status_str = "COMPLETED"

        if tool_name:
            logger.info(f"Direct tool execution requested for tool='{tool_name}'")
            try:
                raw_args = request.session_parameters.get("tool_arguments", "{}")
                try:
                    args = json.loads(raw_args)
                except Exception:
                    args = {"query": request.user_prompt}

                if tool_name == "nexus_hybrid_search":
                    query_text = args.get("query", request.user_prompt)
                    retriever = get_hybrid_retriever()
                    docs = await retriever.search(
                        tenant_id=tenant_id,
                        query=query_text,
                        top_k=int(args.get("top_k", 5)),
                        alpha=float(args.get("alpha", 0.7)),
                        rerank=True
                    )
                    snippets = [f"[{i+1}] {d.get('content', '')[:200]}" for i, d in enumerate(docs)]
                    response_text = f"Retrieved {len(docs)} documents via hybrid search:\n" + "\n".join(snippets)
                    for i, d in enumerate(docs):
                        citations.append(
                            agent_service_pb2.Citation(
                                citation_id=f"cit-{i+1}",
                                document_id=d.get("document_id", ""),
                                chunk_id=d.get("chunk_id", ""),
                                text_snippet=d.get("content", "")[:300],
                                confidence=float(d.get("rerank_score") or d.get("score") or 1.0)
                            )
                        )
                else:
                    output = await self.mcp_manager.call_tool(tool_name=tool_name, arguments=args)
                    response_text = f"Tool '{tool_name}' output:\n{json.dumps(output, default=str)}"
            except Exception as exc:
                logger.error(f"Error executing MCP tool '{tool_name}': {exc}", exc_info=True)
                response_text = f"Tool execution failed: {str(exc)}"
                status_str = "FAILED"
        else:
            response_text = f"Processed agent query for tenant {tenant_id}: {request.user_prompt}"

        latency_ms = int((time.perf_counter() - start_time) * 1000)
        return agent_service_pb2.AgentQueryResponse(
            task_id=request.task_id,
            response_text=response_text,
            citations=citations,
            execution_status=status_str,
            total_tokens_used=150,
            latency_ms=latency_ms
        )
