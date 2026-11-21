import copy
import time
import json
import logging
from typing import Dict, Any, AsyncGenerator, List

import grpc
from app.grpc.generated.ai.v1 import agent_service_pb2, agent_service_pb2_grpc
from app.mcp.client_manager import get_mcp_client_manager
from app.mcp.server import nexus_mcp_server
from app.mcp.tenant_tools import (
    ALLOWED_TENANT_TOOLS,
    InvalidToolArgumentError,
    ToolNotAllowedError,
    call_tenant_scoped_tool,
)
from app.agents.graph import get_agent_graph
from app.agents.state import build_initial_state
from app.security.auth import require_tenant_id, TenantValidationError

logger = logging.getLogger("nexus.grpc.agent_servicer")

# Mapping of LangGraph node names to Protocol Buffer NodeType enum values
NODE_TYPE_MAP = {
    "planner": agent_service_pb2.NODE_TYPE_PLANNER,
    "researcher": agent_service_pb2.NODE_TYPE_RESEARCHER,
    "synthesizer": agent_service_pb2.NODE_TYPE_SYNTHESIZER,
    "critic": agent_service_pb2.NODE_TYPE_CRITIC,
    "mcp_tool": agent_service_pb2.NODE_TYPE_MCP_TOOL,
}


async def _require_tenant(request, context) -> str:
    """Returns the trusted tenant_id from TenantContext or aborts the RPC with INVALID_ARGUMENT."""
    raw = request.context.tenant_id if request.HasField("context") else ""
    try:
        return require_tenant_id(raw)
    except TenantValidationError as exc:
        await context.abort(grpc.StatusCode.INVALID_ARGUMENT, f"TenantContext.{exc}")
        raise  # unreachable with a real context; keeps type-checkers and test doubles honest


def _public_schema(schema: Any) -> str:
    """Hides the server-injected tenant_id parameter from schemas shown to callers."""
    if not isinstance(schema, dict):
        return json.dumps(schema or {})
    cleaned = copy.deepcopy(schema)
    cleaned.get("properties", {}).pop("tenant_id", None)
    if isinstance(cleaned.get("required"), list):
        cleaned["required"] = [r for r in cleaned["required"] if r != "tenant_id"]
    return json.dumps(cleaned)


class AgentServiceServicer(agent_service_pb2_grpc.AgentServiceServicer):
    """gRPC servicer for multi-agent workflows, live streaming, and tenant-safe MCP tool discovery."""

    def __init__(self):
        self.mcp_manager = get_mcp_client_manager()
        self.graph = get_agent_graph()

    async def ListMcpTools(
        self,
        request: agent_service_pb2.ListMcpToolsRequest,
        context: grpc.aio.ServicerContext
    ) -> agent_service_pb2.ListMcpToolsResponse:
        """Returns the MCP tools a tenant may execute (native in-process tools + allowlisted external tools)."""
        tenant_id = await _require_tenant(request, context)
        logger.info(f"AgentService.ListMcpTools called for tenant: '{tenant_id}'")

        tools: Dict[str, agent_service_pb2.McpToolDefinition] = {}

        try:
            for t in await nexus_mcp_server.list_tools():
                if t.name not in ALLOWED_TENANT_TOOLS:
                    continue
                schema = getattr(t, "input_schema", None) or getattr(t, "inputSchema", None) or {}
                tools[t.name] = agent_service_pb2.McpToolDefinition(
                    name=t.name,
                    description=t.description or "",
                    input_schema_json=_public_schema(schema),
                    server_origin="mcp://nexus-hub-mcp",
                )
        except Exception as exc:
            logger.warning(f"Could not list native MCP tools: {exc}")

        try:
            for ext in await self.mcp_manager.list_all_tools():
                name = ext.get("name")
                if not name or name in tools or name not in ALLOWED_TENANT_TOOLS:
                    continue
                tools[name] = agent_service_pb2.McpToolDefinition(
                    name=name,
                    description=ext.get("description", ""),
                    input_schema_json=_public_schema(ext.get("inputSchema") or ext.get("input_schema")),
                    server_origin=str(ext.get("server_origin") or f"mcp://{ext.get('server_name', 'external')}"),
                )
        except Exception as exc:
            logger.warning(f"Could not discover external MCP tools: {exc}")

        result_tools = list(tools.values())
        return agent_service_pb2.ListMcpToolsResponse(tools=result_tools, total_tools=len(result_tools))

    async def ExecuteAgentSync(
        self,
        request: agent_service_pb2.AgentQueryRequest,
        context: grpc.aio.ServicerContext
    ) -> agent_service_pb2.AgentQueryResponse:
        """Executes an allowlisted MCP tool (tenant enforced server-side) or the full agent graph."""
        start_time = time.perf_counter()
        tenant_id = await _require_tenant(request, context)
        trace_id = request.context.trace_id or f"trace-{request.task_id}"
        session_params = dict(request.session_parameters)
        tool_name = session_params.get("tool_name")

        logger.info(f"AgentService.ExecuteAgentSync: task_id={request.task_id}, tenant={tenant_id}, tool={tool_name or '-'}")

        # 1. Direct tool execution (allowlist + optional per-request enablement list)
        if tool_name:
            enabled: List[str] = list(request.mcp_tools_enabled)
            if tool_name not in ALLOWED_TENANT_TOOLS or (enabled and tool_name not in enabled):
                await context.abort(grpc.StatusCode.PERMISSION_DENIED, f"MCP tool '{tool_name}' is not allowed")
                raise ToolNotAllowedError(tool_name)

            args_raw = session_params.get("tool_args")
            if args_raw:
                try:
                    args = json.loads(args_raw)
                    if not isinstance(args, dict):
                        raise ValueError("tool_args must be a JSON object")
                except Exception as exc:
                    await context.abort(grpc.StatusCode.INVALID_ARGUMENT, f"Invalid tool_args: {exc}")
                    raise
            else:
                args = {"query": request.user_prompt}

            try:
                tool_output = await call_tenant_scoped_tool(self.mcp_manager, tool_name, args, tenant_id)
            except InvalidToolArgumentError as exc:
                await context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(exc))
                raise
            except Exception as exc:
                tool_output = None
                tool_error = exc
            else:
                tool_error = None

            if tool_error is None:
                status_str = "COMPLETED"
                response_text = f"Tool '{tool_name}' Output:\n\n{tool_output}"
            else:
                logger.error(f"Failed to execute MCP tool '{tool_name}': {tool_error}", exc_info=tool_error)
                status_str = "FAILED"
                response_text = f"Error executing MCP tool '{tool_name}': {type(tool_error).__name__}"

            return agent_service_pb2.AgentQueryResponse(
                task_id=request.task_id,
                response_text=response_text,
                execution_status=status_str,
                total_tokens_used=0,  # direct tool calls do not consume LLM tokens
                latency_ms=int((time.perf_counter() - start_time) * 1000),
            )

        # 2. Full Multi-Agent Graph Execution
        initial_state = build_initial_state(
            task_id=request.task_id,
            tenant_id=tenant_id,
            user_prompt=request.user_prompt,
            trace_id=trace_id,
            metadata=session_params,
        )
        config = {"configurable": {"thread_id": request.task_id}}
        final_state = await self.graph.ainvoke(initial_state, config=config)

        response_text = final_state.get("draft_report", "")
        citations = [
            agent_service_pb2.AgentCitation(
                document_id=str(r.get("document_id", "")),
                title=r.get("citation", ""),
                snippet=r.get("content", "")[:200],
                confidence=float(r.get("rerank_score") or r.get("score") or 0.0),
            )
            for r in final_state.get("research_data", [])
        ]

        return agent_service_pb2.AgentQueryResponse(
            task_id=request.task_id,
            response_text=response_text,
            citations=citations,
            execution_status="COMPLETED" if response_text else "FAILED",
            total_tokens_used=int(final_state.get("token_usage", 0) or 0),
            latency_ms=int((time.perf_counter() - start_time) * 1000),
        )

    async def StreamAgentWorkflow(
        self,
        request: agent_service_pb2.AgentQueryRequest,
        context: grpc.aio.ServicerContext
    ) -> AsyncGenerator[agent_service_pb2.AgentWorkflowEvent, None]:
        """Server-streaming RPC emitting live LangGraph node transitions."""
        tenant_id = await _require_tenant(request, context)
        trace_id = request.context.trace_id or f"trace-{request.task_id}"
        logger.info(f"AgentService.StreamAgentWorkflow started: task_id={request.task_id}, tenant={tenant_id}")

        yield agent_service_pb2.AgentWorkflowEvent(
            task_id=request.task_id,
            trace_id=trace_id,
            node_type=agent_service_pb2.NODE_TYPE_PLANNER,
            phase=agent_service_pb2.EVENT_PHASE_STARTED,
            message="Initializing multi-agent pipeline...",
            timestamp_ms=int(time.time() * 1000)
        )

        initial_state = build_initial_state(
            task_id=request.task_id,
            tenant_id=tenant_id,
            user_prompt=request.user_prompt,
            trace_id=trace_id,
            metadata=dict(request.session_parameters),
        )
        config = {"configurable": {"thread_id": request.task_id}}

        try:
            async for step in self.graph.astream(initial_state, config=config):
                for node_name, node_output in step.items():
                    pb_node_type = NODE_TYPE_MAP.get(node_name, agent_service_pb2.NODE_TYPE_UNSPECIFIED)
                    node_status = node_output.get("status", "COMPLETED")
                    token_chunk = ""
                    msg_text = f"Node '{node_name}' finished: status={node_status}"

                    if node_name == "planner":
                        msg_text = f"Planner produced {len(node_output.get('plan', []))} search queries."
                    elif node_name == "researcher":
                        msg_text = f"Researcher holds {len(node_output.get('research_data', []))} evidence items."
                    elif node_name == "synthesizer":
                        token_chunk = node_output.get("draft_report", "")
                        msg_text = f"Synthesized report ({len(token_chunk)} characters)."
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

            yield agent_service_pb2.AgentWorkflowEvent(
                task_id=request.task_id,
                trace_id=trace_id,
                node_type=agent_service_pb2.NODE_TYPE_SYNTHESIZER,
                phase=agent_service_pb2.EVENT_PHASE_COMPLETED,
                message="Multi-agent workflow completed.",
                timestamp_ms=int(time.time() * 1000)
            )
        except Exception as exc:
            logger.error(f"StreamAgentWorkflow error for task '{request.task_id}': {exc}", exc_info=True)
            yield agent_service_pb2.AgentWorkflowEvent(
                task_id=request.task_id,
                trace_id=trace_id,
                node_type=agent_service_pb2.NODE_TYPE_UNSPECIFIED,
                phase=agent_service_pb2.EVENT_PHASE_FAILED,
                message=f"Agent workflow error: {type(exc).__name__}",
                timestamp_ms=int(time.time() * 1000)
            )
