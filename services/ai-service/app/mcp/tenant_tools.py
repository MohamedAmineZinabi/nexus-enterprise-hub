"""
Tenant-scoped MCP tool execution.

All callers (REST, gRPC ExecuteAgentSync, LangGraph researcher) go through
``call_tenant_scoped_tool`` so that:
  * only explicitly allowlisted tools can be executed, and
  * ``tenant_id`` is always injected from trusted server-side context, overriding any
    caller-supplied value in the tool arguments.
"""
import logging
from typing import Any, Dict, Optional

from app.mcp.client_manager import MCPClientManager
from app.security.auth import require_tenant_id

logger = logging.getLogger("nexus.mcp.tenant_tools")

# tool name -> MCP server that owns it ("nexus-hub-mcp" = in-process native server)
ALLOWED_TENANT_TOOLS: Dict[str, str] = {
    "nexus_hybrid_search": "nexus-hub-mcp",
    "nexus_document_summarizer": "nexus-hub-mcp",
    "list_shared_files": "enterprise_filesystem",
    "read_shared_file_preview": "enterprise_filesystem",
}


class ToolNotAllowedError(PermissionError):
    """Raised when a caller requests an MCP tool outside the tenant-safe allowlist."""


class InvalidToolArgumentError(ValueError):
    """Raised when tool arguments are invalid (e.g. path traversal); maps to HTTP 400 / INVALID_ARGUMENT."""


# Arguments of the filesystem tools that are interpreted as paths inside the tenant sandbox
_PATH_ARGUMENTS = {"filename", "subpath"}


def _validate_path_argument(name: str, value: Any) -> None:
    """Rejects absolute paths and parent-directory traversal before the tool runs (the sandbox re-checks)."""
    if value is None:
        return
    if not isinstance(value, str):
        raise InvalidToolArgumentError(f"'{name}' must be a string")
    normalized = value.replace("\\", "/")
    if normalized.startswith("/") or (len(normalized) > 1 and normalized[1] == ":"):
        raise InvalidToolArgumentError(f"'{name}' must be a relative path")
    if any(part == ".." for part in normalized.split("/")):
        raise InvalidToolArgumentError(f"'{name}' must not contain parent directory references")
    if "\x00" in normalized:
        raise InvalidToolArgumentError(f"'{name}' contains invalid characters")


async def call_tenant_scoped_tool(
    manager: MCPClientManager,
    tool_name: str,
    arguments: Optional[Dict[str, Any]],
    tenant_id: str,
) -> str:
    """Executes an allowlisted MCP tool with a server-enforced tenant_id."""
    trusted_tenant = require_tenant_id(tenant_id)
    server_name = ALLOWED_TENANT_TOOLS.get(tool_name)
    if server_name is None:
        raise ToolNotAllowedError(f"MCP tool '{tool_name}' is not allowed")

    safe_args: Dict[str, Any] = dict(arguments or {})
    if server_name == "enterprise_filesystem":
        for arg_name in _PATH_ARGUMENTS & safe_args.keys():
            _validate_path_argument(arg_name, safe_args[arg_name])
    if safe_args.get("tenant_id") not in (None, "", trusted_tenant):
        logger.warning(
            "Overriding caller-supplied tenant_id for tool '%s' with trusted tenant '%s'",
            tool_name, trusted_tenant,
        )
    safe_args["tenant_id"] = trusted_tenant

    return await manager.call_tool(server_name=server_name, tool_name=tool_name, arguments=safe_args)
