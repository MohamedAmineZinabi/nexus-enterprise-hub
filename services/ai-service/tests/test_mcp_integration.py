import os
import json
import asyncio
import pytest
from pydantic import ValidationError

from app.mcp.client_manager import MCPClientManager, ServerConnection
from app.mcp.tool_adapter import convert_mcp_to_langchain_tool
from app.mcp.server import nexus_mcp_server
from app.grpc.servicers.agent_servicer import AgentServiceServicer
from app.grpc.generated.ai.v1 import agent_service_pb2


# Mock in-process stdio server code for testing
MOCK_SERVER_CODE = """
import asyncio
from mcp.server.mcpserver import MCPServer

srv = MCPServer("test-integration-server")

@srv.tool(name="calculate_enterprise_metric", description="Calculates financial compliance ratios.")
def calculate_enterprise_metric(ratio_type: str, value: float) -> str:
    return f"Metric '{ratio_type}' calculated for value {value}: ratio=0.95"

@srv.tool(name="fetch_tenant_audit_log", description="Retrieves recent tenant audit logs.")
def fetch_tenant_audit_log(tenant_id: str, limit: int = 5) -> str:
    return f"Audit log for {tenant_id}: retrieved {limit} records"

if __name__ == "__main__":
    asyncio.run(srv.run_stdio_async())
"""


@pytest.fixture
def mock_mcp_manager():
    """Creates an MCPClientManager configured with a mock stdio server."""
    mgr = MCPClientManager()
    mgr.servers["mock_server"] = ServerConnection(
        name="mock_server",
        config={
            "transport": "stdio",
            "command": "python",
            "args": ["-c", MOCK_SERVER_CODE]
        }
    )
    mgr._initialized = True
    return mgr


TEST_TENANT = "d5569b60-2545-4c7b-be8a-4aa7b787de67"


class _AbortError(Exception):
    pass


class FakeServicerContext:
    """Minimal grpc.aio.ServicerContext double that records abort() calls."""

    def __init__(self):
        self.code = None
        self.details = None

    async def abort(self, code, details):
        self.code, self.details = code, details
        raise _AbortError(details)


def _tenant_ctx(tenant_id: str = TEST_TENANT):
    from app.grpc.generated.ai import common_pb2
    return common_pb2.TenantContext(tenant_id=tenant_id, user_id="u-1", trace_id="trace-test")


class _StubRetriever:
    def search(self, **kwargs):
        return []


@pytest.mark.asyncio
async def test_native_mcp_server_tools(monkeypatch):
    """Native MCP tools are registered, execute for a tenant, and refuse calls without a tenant."""
    import app.mcp.server as mcp_server_module
    monkeypatch.setattr(mcp_server_module, "get_hybrid_retriever", lambda: _StubRetriever())

    tools = await nexus_mcp_server.list_tools()
    tool_names = [t.name for t in tools]
    assert "nexus_hybrid_search" in tool_names
    assert "nexus_document_summarizer" in tool_names

    result = await nexus_mcp_server.call_tool(
        "nexus_hybrid_search", {"query": "security policy", "top_k": 2, "tenant_id": TEST_TENANT}
    )
    assert "No documents found" in result.content[0].text

    # tenant_id is mandatory: a call without it must fail instead of searching a shared/default tenant
    with pytest.raises(Exception):
        await nexus_mcp_server.call_tool("nexus_hybrid_search", {"query": "security policy"})
    with pytest.raises(Exception):
        await nexus_mcp_server.call_tool("nexus_document_summarizer", {"document_id": "doc-1", "tenant_id": ""})


@pytest.mark.asyncio
async def test_mcp_client_manager_stdio_discovery_and_execution(mock_mcp_manager):
    """Verifies stdio connection, dynamic tool discovery, and tool execution."""
    try:
        tools = await mock_mcp_manager.list_all_tools()
        names = [t["name"] for t in tools]
        assert "calculate_enterprise_metric" in names
        assert "fetch_tenant_audit_log" in names

        # Verify tool schema structure
        calc_tool = next(t for t in tools if t["name"] == "calculate_enterprise_metric")
        assert calc_tool["server_name"] == "mock_server"
        schema = calc_tool["inputSchema"]
        assert "properties" in schema
        assert "ratio_type" in schema["properties"]
        assert "value" in schema["properties"]

        # Call the tool through client manager
        output = await mock_mcp_manager.call_tool(
            server_name="mock_server",
            tool_name="calculate_enterprise_metric",
            arguments={"ratio_type": "liquidity", "value": 150000.0}
        )
        assert "liquidity" in output
        assert "0.95" in output
    finally:
        await mock_mcp_manager.close()


@pytest.mark.asyncio
async def test_convert_mcp_to_langchain_tool_adapter(mock_mcp_manager):
    """Verifies conversion of dynamic MCP tools to first-class LangChain BaseTool instances."""
    try:
        tools = await mock_mcp_manager.list_all_tools()
        calc_tool_def = next(t for t in tools if t["name"] == "calculate_enterprise_metric")

        # Convert to LangChain tool
        lc_tool = convert_mcp_to_langchain_tool(calc_tool_def, mock_mcp_manager)
        assert lc_tool.name == "calculate_enterprise_metric"
        assert "liquidity" not in lc_tool.description
        assert "ratio_type" in lc_tool.args
        assert "value" in lc_tool.args

        # 1. Async invocation
        async_res = await lc_tool.ainvoke({"ratio_type": "solvency", "value": 250.5})
        assert "solvency" in async_res
        assert "ratio=0.95" in async_res

        # 2. Schema validation error handling (missing required argument)
        with pytest.raises(ValidationError):
            await lc_tool.ainvoke({"invalid_arg": 123})
    finally:
        await mock_mcp_manager.close()


@pytest.mark.asyncio
async def test_grpc_agent_servicer_list_and_exec(monkeypatch):
    """ListMcpTools / ExecuteAgentSync enforce tenant context, the tool allowlist and server-side tenant_id."""
    servicer = AgentServiceServicer()
    servicer.mcp_manager = MCPClientManager()
    servicer.mcp_manager._initialized = True  # no external servers in unit tests

    # ListMcpTools: requires a tenant; returns only allowlisted tools; hides the injected tenant_id param
    with pytest.raises(_AbortError):
        await servicer.ListMcpTools(agent_service_pb2.ListMcpToolsRequest(), FakeServicerContext())

    resp = await servicer.ListMcpTools(
        agent_service_pb2.ListMcpToolsRequest(context=_tenant_ctx()), FakeServicerContext()
    )
    tool_names = [t.name for t in resp.tools]
    assert {"nexus_hybrid_search", "nexus_document_summarizer"} <= set(tool_names)
    for tool in resp.tools:
        assert "tenant_id" not in json.loads(tool.input_schema_json).get("properties", {})

    # ExecuteAgentSync: caller-supplied tenant_id is overridden with the trusted TenantContext tenant
    captured = {}

    async def fake_call_tool(server_name, tool_name, arguments):
        captured.update(server_name=server_name, tool_name=tool_name, arguments=arguments)
        return "ok"

    monkeypatch.setattr(servicer.mcp_manager, "call_tool", fake_call_tool)
    query_req = agent_service_pb2.AgentQueryRequest(
        context=_tenant_ctx(),
        task_id="test-task-100",
        user_prompt="Run vector search for enterprise compliance",
        mcp_tools_enabled=["nexus_hybrid_search"],
        session_parameters={
            "tool_name": "nexus_hybrid_search",
            "tool_args": json.dumps({"query": "compliance", "top_k": 2, "tenant_id": "attacker-tenant"}),
        },
    )
    query_resp = await servicer.ExecuteAgentSync(query_req, FakeServicerContext())
    assert query_resp.execution_status == "COMPLETED"
    assert query_resp.total_tokens_used == 0
    assert captured["arguments"]["tenant_id"] == TEST_TENANT
    assert captured["server_name"] == "nexus-hub-mcp"

    # Non-allowlisted tool is refused with PERMISSION_DENIED
    ctx = FakeServicerContext()
    bad_req = agent_service_pb2.AgentQueryRequest(
        context=_tenant_ctx(), task_id="t-2", user_prompt="x",
        session_parameters={"tool_name": "calculate_enterprise_metric"},
    )
    with pytest.raises(_AbortError):
        await servicer.ExecuteAgentSync(bad_req, ctx)
    import grpc
    assert ctx.code == grpc.StatusCode.PERMISSION_DENIED

    # Missing tenant context is refused with INVALID_ARGUMENT
    ctx = FakeServicerContext()
    with pytest.raises(_AbortError):
        await servicer.ExecuteAgentSync(agent_service_pb2.AgentQueryRequest(task_id="t-3", user_prompt="x"), ctx)
    assert ctx.code == grpc.StatusCode.INVALID_ARGUMENT
