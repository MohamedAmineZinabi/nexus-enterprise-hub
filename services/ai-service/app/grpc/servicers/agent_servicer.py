import time
import json
import logging
import asyncio
from typing import List, Dict, Any, AsyncGenerator

import grpc
from app.grpc.generated.ai.v1 import agent_service_pb2, agent_service_pb2_grpc
from app.mcp.client_manager import get_mcp_client_manager
from app.agents.graph import get_agent_graph
from app.agents.state import AgentWorkflowState

logger = logging.getLogger("nexus.grpc.agent_servicer")

# Mapping of LangGraph node names to Protocol Buffer NodeType enum values
NODE_TYPE_MAP = {
    "planner": agent_service_pb2.NODE_TYPE_PLANNER,
    "researcher": agent_service_pb2.NODE_TYPE_RESEARCHER,
    "synthesizer": agent_service_pb2.NODE_TYPE_SYNTHESIZER,
    "critic": agent_service_pb2.NODE_TYPE_CRITIC,
    "mcp_tool": agent_service_pb2.NODE_TYPE_MCP_TOOL,
}


class AgentServiceServicer(agent_service_pb2_grpc.AgentServiceServicer):
    """Production gRPC servicer for multi-agent workflows, live streaming, and MCP discovery."""

    def __init__(self):
        self.mcp_manager = get_mcp_client_manager()
        self.graph = get_agent_graph()

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
        """Executes a synchronous agent task, routing to a single MCP tool or running the full graph."""
        start_time = time.perf_counter()
        tenant_id = request.context.tenant_id if request.HasField("context") else "default"
        trace_id = request.context.trace_id if request.HasField("context") else f"trace-{request.task_id}"

        logger.info(
            f"AgentService.ExecuteAgentSync: task_id={request.task_id}, "
            f"tenant={tenant_id}, prompt='{request.user_prompt}', trace_id={trace_id}"
        )

        session_params = dict(request.session_parameters)
        tool_name = session_params.get("tool_name")

        # 1. Direct tool execution routing
        if tool_name:
            args_raw = session_params.get("tool_args")
            if args_raw:
                try:
                    args = json.loads(args_raw)
                except Exception:
                    args = {"input": args_raw}
            else:
                args = {"query": request.user_prompt, "tenant_id": tenant_id}

            try:
                tool_output = await self.mcp_manager.call_tool(
                    server_name=session_params.get("server_name"),
                    tool_name=tool_name,
                    arguments=args
                )
                status_str = "COMPLETED"
                response_text = f"Tool '{tool_name}' Output:\n\n{tool_output}"
            except Exception as exc:
                logger.error(f"Failed to execute MCP tool '{tool_name}': {exc}", exc_info=True)
                status_str = "FAILED"
                response_text = f"Error executing MCP tool '{tool_name}': {str(exc)}"

            latency_ms = int((time.perf_counter() - start_time) * 1000)
            return agent_service_pb2.AgentQueryResponse(
                task_id=request.task_id,
                response_text=response_text,
                execution_status=status_str,
                total_tokens_used=120,
                latency_ms=latency_ms
            )

        # 2. Full Multi-Agent Graph Execution
        initial_state: AgentWorkflowState = {
            "task_id": request.task_id,
            "tenant_id": tenant_id,
            "user_prompt": request.user_prompt,
            "trace_id": trace_id,
            "document_ids": [],
            "metadata": session_params,
            "plan": [],
            "research_data": [],
            "draft_report": "",
            "critique": {},
            "iteration_count": 0,
            "current_node": "START",
            "status": "PROCESSING"
        }

        config = {"configurable": {"thread_id": request.task_id}}
        final_state = await self.graph.ainvoke(initial_state, config=config)

        response_text = final_state.get("draft_report", "")
        status_str = "COMPLETED" if response_text else "FAILED"
        latency_ms = int((time.perf_counter() - start_time) * 1000)

        # Build citations
        citations = []
        for r in final_state.get("research_data", []):
            citations.append(
                agent_service_pb2.AgentCitation(
                    document_id=r.get("metadata", {}).get("document_id", ""),
                    title=r.get("citation", ""),
                    snippet=r.get("content", "")[:200],
                    confidence=float(r.get("rerank_score") or r.get("score") or 1.0)
                )
            )

        return agent_service_pb2.AgentQueryResponse(
            task_id=request.task_id,
            response_text=response_text,
            citations=citations,
            execution_status=status_str,
            total_tokens_used=350,
            latency_ms=latency_ms
        )

    async def StreamAgentWorkflow(
        self,
        request: agent_service_pb2.AgentQueryRequest,
        context: grpc.aio.ServicerContext
    ) -> AsyncGenerator[agent_service_pb2.AgentWorkflowEvent, None]:
        """
        Server-streaming RPC: Streams live LangGraph node transitions (PLANNER, RESEARCHER,
        SYNTHESIZER, CRITIC), MCP tool calls, and tokens.
        """
        tenant_id = request.context.tenant_id if request.HasField("context") else "default"
        trace_id = request.context.trace_id if request.HasField("context") else f"trace-{request.task_id}"

        logger.info(
            f"AgentService.StreamAgentWorkflow started: task_id={request.task_id}, "
            f"tenant={tenant_id}, prompt='{request.user_prompt}'"
        )

        # Emit initial STARTED event
        yield agent_service_pb2.AgentWorkflowEvent(
            task_id=request.task_id,
            trace_id=trace_id,
            node_type=agent_service_pb2.NODE_TYPE_PLANNER,
            phase=agent_service_pb2.EVENT_PHASE_STARTED,
            message="Initializing autonomous multi-agent pipeline...",
            timestamp_ms=int(time.time() * 1000)
        )

        initial_state: AgentWorkflowState = {
            "task_id": request.task_id,
            "tenant_id": tenant_id,
            "user_prompt": request.user_prompt,
            "trace_id": trace_id,
            "document_ids": [],
            "metadata": dict(request.session_parameters),
            "plan": [],
            "research_data": [],
            "draft_report": "",
            "critique": {},
            "iteration_count": 0,
            "current_node": "START",
            "status": "PROCESSING"
        }

        config = {"configurable": {"thread_id": request.task_id}}

        try:
            async for step in self.graph.astream(initial_state, config=config):
                for node_name, node_output in step.items():
                    pb_node_type = NODE_TYPE_MAP.get(node_name, agent_service_pb2.NODE_TYPE_UNSPECIFIED)
                    node_status = node_output.get("status", "COMPLETED")
                    token_chunk = ""
                    msg_text = f"Node '{node_name}' finished: status={node_status}"

                    if node_name == "planner":
                        plan_steps = node_output.get("plan", [])
                        msg_text = f"Planner decomposed prompt into {len(plan_steps)} sub-tasks."
                    elif node_name == "researcher":
                        r_data = node_output.get("research_data", [])
                        msg_text = f"Researcher retrieved {len(r_data)} factual context citations."
                    elif node_name == "synthesizer":
                        token_chunk = node_output.get("draft_report", "")
                        msg_text = f"Synthesized executive report ({len(token_chunk)} characters)."
                    elif node_name == "critic":
                        crit = node_output.get("critique", {})
                        msg_text = f"Critic evaluation: valid={crit.get('is_valid')}, score={crit.get('hallucination_score')}"

                    yield agent_service_pb2.AgentWorkflowEvent(
                        task_id=request.task_id,
                        trace_id=trace_id,
                        node_type=pb_node_type,
                        phase=agent_service_pb2.EVENT_PHASE_COMPLETED,
                        token_chunk=token_chunk,
                        message=msg_text,
                        timestamp_ms=int(time.time() * 1000)
                    )

            # Emit final workflow completion event
            yield agent_service_pb2.AgentWorkflowEvent(
                task_id=request.task_id,
                trace_id=trace_id,
                node_type=agent_service_pb2.NODE_TYPE_SYNTHESIZER,
                phase=agent_service_pb2.EVENT_PHASE_COMPLETED,
                message="Multi-agent workflow successfully completed.",
                timestamp_ms=int(time.time() * 1000)
            )
        except Exception as exc:
            logger.error(f"StreamAgentWorkflow error for task '{request.task_id}': {exc}", exc_info=True)
            yield agent_service_pb2.AgentWorkflowEvent(
                task_id=request.task_id,
                trace_id=trace_id,
                node_type=agent_service_pb2.NODE_TYPE_UNSPECIFIED,
                phase=agent_service_pb2.EVENT_PHASE_FAILED,
                message=f"Agent workflow error: {str(exc)}",
                timestamp_ms=int(time.time() * 1000)
            )
