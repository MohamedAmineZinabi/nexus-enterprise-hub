import time
import logging
import asyncio
import concurrent.futures
from typing import Dict, Any, Union, Optional, Type
from pydantic import create_model, Field, BaseModel
from langchain_core.tools import StructuredTool, BaseTool

from app.mcp.client_manager import MCPClientManager, get_mcp_client_manager

logger = logging.getLogger("nexus.mcp.tool_adapter")

# Type mapping from JSON Schema to Python types
JSON_TYPE_MAPPING = {
    "string": str,
    "integer": int,
    "number": float,
    "boolean": bool,
    "array": list,
    "object": dict
}


def _build_pydantic_schema(tool_name: str, input_schema: Dict[str, Any]) -> Type[BaseModel]:
    """Dynamically creates a Pydantic v2 model from a JSON Schema specification."""
    if not input_schema or not isinstance(input_schema, dict):
        return create_model(f"{tool_name}_Args")

    properties = input_schema.get("properties", {})
    required_fields = set(input_schema.get("required", []))

    field_definitions: Dict[str, Any] = {}
    for prop_name, prop_def in properties.items():
        if not isinstance(prop_def, dict):
            prop_def = {}

        json_type = prop_def.get("type", "string")
        py_type = JSON_TYPE_MAPPING.get(json_type, Any)
        description = prop_def.get("description", "")
        default_val = prop_def.get("default", None)

        if prop_name in required_fields:
            field_definitions[prop_name] = (py_type, Field(..., description=description))
        else:
            field_definitions[prop_name] = (Optional[py_type], Field(default=default_val, description=description))

    if not field_definitions:
        return create_model(f"{tool_name}_Args")

    return create_model(f"{tool_name}_Args", **field_definitions)


def convert_mcp_to_langchain_tool(
    mcp_tool: Union[Dict[str, Any], Any],
    client_manager: Optional[MCPClientManager] = None
) -> BaseTool:
    """
    Converts a dynamic MCP tool definition into a first-class LangChain BaseTool.

    Args:
        mcp_tool: Dictionary or MCP Tool object with name, description, and inputSchema.
        client_manager: MCPClientManager used to route the tool execution.

    Returns:
        StructuredTool ready for LangChain / LangGraph multi-agent execution.
    """
    mgr = client_manager or get_mcp_client_manager()

    # Normalize fields from dict or mcp.types.Tool
    if isinstance(mcp_tool, dict):
        name = mcp_tool.get("name", "unnamed_tool")
        description = mcp_tool.get("description", "")
        input_schema = mcp_tool.get("inputSchema") or mcp_tool.get("input_schema") or {}
        server_name = mcp_tool.get("server_name")
        server_origin = mcp_tool.get("server_origin", "mcp://external")
    else:
        name = getattr(mcp_tool, "name", "unnamed_tool")
        description = getattr(mcp_tool, "description", "")
        input_schema = getattr(mcp_tool, "input_schema", getattr(mcp_tool, "inputSchema", {}))
        server_name = getattr(mcp_tool, "server_name", None)
        server_origin = getattr(mcp_tool, "server_origin", "mcp://external")

    # Build validated dynamic Pydantic v2 schema
    args_schema = _build_pydantic_schema(name, input_schema)

    async def _arun(**kwargs) -> str:
        """Asynchronous execution hook with latency and telemetry tracking."""
        start_time = time.perf_counter()
        trace_id = kwargs.pop("_trace_id", None) or kwargs.get("trace_id", "local-trace")
        logger.info(
            f"[MCP Telemetry] START tool='{name}' server='{server_name}' "
            f"origin='{server_origin}' trace_id='{trace_id}' args={kwargs}"
        )

        try:
            result = await mgr.call_tool(
                server_name=server_name,
                tool_name=name,
                arguments=kwargs
            )
            latency_ms = (time.perf_counter() - start_time) * 1000
            logger.info(
                f"[MCP Telemetry] SUCCESS tool='{name}' latency={latency_ms:.2f}ms trace_id='{trace_id}'"
            )
            return result
        except Exception as exc:
            latency_ms = (time.perf_counter() - start_time) * 1000
            logger.error(
                f"[MCP Telemetry] FAILURE tool='{name}' latency={latency_ms:.2f}ms error={exc} trace_id='{trace_id}'",
                exc_info=True
            )
            return f"Execution error in MCP tool '{name}': {str(exc)}"

    def _run(**kwargs) -> str:
        """Synchronous execution wrapper when called outside of an active event loop."""
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            loop = None

        if loop and loop.is_running():
            raise NotImplementedError(
                f"MCP tool '{name}' is asynchronous. Inside an async event loop, use 'await tool.ainvoke(...)'. "
                f"Calling sync 'invoke()' inside an async event loop is not supported to prevent deadlocks."
            )
        return asyncio.run(_arun(**kwargs))

    return StructuredTool.from_function(
        func=_run,
        coroutine=_arun,
        name=name,
        description=f"{description} (Origin: {server_origin})",
        args_schema=args_schema
    )
