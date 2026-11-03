import os
import json
import asyncio
import logging
from typing import Dict, List, Any, Optional
from contextlib import asynccontextmanager

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from mcp.client.sse import sse_client
from app.core.config import settings

logger = logging.getLogger("nexus.mcp.client_manager")


class ServerConnection:
    """Manages sessions and tool execution for an MCP server (stdio or sse)."""

    def __init__(self, name: str, config: Dict[str, Any]):
        self.name = name
        self.config = config
        self.transport = config.get("transport", "stdio").lower()
        self.cached_tools: List[Dict[str, Any]] = []

    @asynccontextmanager
    async def get_session(self, timeout: float = 10.0):
        """Yields an initialized ClientSession within the caller's active task scope."""
        if self.transport == "stdio":
            cmd = self.config.get("command", "python")
            args = self.config.get("args", [])
            env_override = self.config.get("env")
            env = os.environ.copy()
            if env_override:
                env.update({str(k): str(v) for k, v in env_override.items()})

            params = StdioServerParameters(
                command=cmd,
                args=args,
                env=env
            )
            async with stdio_client(params) as (read_stream, write_stream):
                async with ClientSession(read_stream, write_stream) as session:
                    await asyncio.wait_for(session.initialize(), timeout=timeout)
                    yield session
        elif self.transport == "sse":
            url = self.config.get("url")
            if not url:
                raise ValueError(f"SSE transport requires 'url' in server config: {self.name}")
            headers = self.config.get("headers", {})
            async with sse_client(url, headers=headers) as (read_stream, write_stream):
                async with ClientSession(read_stream, write_stream) as session:
                    await asyncio.wait_for(session.initialize(), timeout=timeout)
                    yield session
        else:
            raise ValueError(f"Unsupported transport '{self.transport}' for MCP server '{self.name}'")

    async def list_tools(self, timeout: float = 10.0) -> List[Dict[str, Any]]:
        """Lists tools registered on this MCP server."""
        try:
            async with self.get_session(timeout=timeout) as session:
                result = await asyncio.wait_for(session.list_tools(), timeout=timeout)
                discovered = []
                for t in result.tools:
                    t_dict = {
                        "name": t.name,
                        "description": t.description or "",
                        "inputSchema": t.input_schema if hasattr(t, "input_schema") else getattr(t, "inputSchema", {}),
                        "server_name": self.name,
                        "server_origin": self.config.get("url", f"mcp://{self.name}")
                    }
                    discovered.append(t_dict)
                self.cached_tools = discovered
                return discovered
        except Exception as exc:
            logger.warning(f"Failed to list tools from MCP server '{self.name}': {exc}")
            return self.cached_tools

    async def call_tool(
        self,
        tool_name: str,
        arguments: Dict[str, Any],
        timeout: float = 15.0
    ) -> str:
        """Executes a tool on this server."""
        async with self.get_session(timeout=timeout) as session:
            res = await asyncio.wait_for(
                session.call_tool(tool_name, arguments),
                timeout=timeout
            )

        if getattr(res, "is_error", False):
            err_text = self._extract_text(res)
            raise RuntimeError(f"MCP tool error ({tool_name}): {err_text}")

        return self._extract_text(res)

    async def fetch_resource(self, uri: str, timeout: float = 10.0) -> str:
        """Reads a resource by URI from this MCP server."""
        async with self.get_session(timeout=timeout) as session:
            result = await asyncio.wait_for(session.read_resource(uri), timeout=timeout)
            contents = getattr(result, "contents", [])
            extracted = []
            for c in contents:
                if hasattr(c, "text"):
                    extracted.append(c.text)
                elif hasattr(c, "blob"):
                    extracted.append(str(c.blob))
            return "\n".join(extracted)

    @staticmethod
    def _extract_text(call_result: Any) -> str:
        contents = getattr(call_result, "content", [])
        if not contents:
            structured = getattr(call_result, "structured_content", None)
            return json.dumps(structured) if structured else ""

        texts = []
        for c in contents:
            if hasattr(c, "text"):
                texts.append(c.text)
            else:
                texts.append(str(c))
        return "\n".join(texts)


class MCPClientManager:
    """Enterprise MCP Client Manager maintaining client connections and tool dispatching."""

    def __init__(self, config_path: Optional[str] = None):
        self.config_path = config_path or settings.mcp_config_path
        self.servers: Dict[str, ServerConnection] = {}
        self._tool_to_server: Dict[str, str] = {}
        self._initialized = False

    def load_configurations(self):
        """Loads MCP server configurations from JSON config file."""
        candidates = [
            self.config_path,
            os.path.join(os.path.dirname(__file__), "configs", "mcp_servers.json"),
            os.path.join(os.getcwd(), "app", "mcp", "configs", "mcp_servers.json"),
            os.path.join(os.getcwd(), "services", "ai-service", "app", "mcp", "configs", "mcp_servers.json"),
        ]

        found_path = None
        for path in candidates:
            if path and os.path.exists(path):
                found_path = path
                break

        if not found_path:
            logger.warning(f"No MCP server configuration found at candidates: {candidates}. Using empty server list.")
            return

        try:
            with open(found_path, "r", encoding="utf-8") as f:
                data = json.load(f)

            server_configs = data.get("mcpServers", {})
            for name, cfg in server_configs.items():
                self.servers[name] = ServerConnection(name, cfg)

            logger.info(f"Loaded {len(self.servers)} MCP server configurations from {found_path}: {list(self.servers.keys())}")
        except Exception as exc:
            logger.error(f"Failed to parse MCP server configuration at {found_path}: {exc}", exc_info=True)

    async def initialize(self):
        """Initializes client manager and discovers configured tools."""
        if self._initialized:
            return
        self.load_configurations()
        self._initialized = True
        # Pre-populate tool registry
        try:
            await self.list_all_tools()
        except Exception as e:
            logger.warning(f"Error during initial tool discovery: {e}")

    async def close(self):
        """Cleans up MCP server connections and caches."""
        logger.info("Closing all MCP server sessions...")
        self.servers.clear()
        self._tool_to_server.clear()
        self._initialized = False

    async def list_all_tools(self) -> List[Dict[str, Any]]:
        """Aggregates all tools discovered across configured external MCP servers."""
        if not self._initialized:
            self.load_configurations()
            self._initialized = True

        all_tools: List[Dict[str, Any]] = []

        # Query all configured servers concurrently
        tasks = [srv.list_tools() for srv in self.servers.values()]
        if tasks:
            results = await asyncio.gather(*tasks, return_exceptions=True)
            for srv_name, res in zip(self.servers.keys(), results):
                if isinstance(res, list):
                    for tool in res:
                        all_tools.append(tool)
                        self._tool_to_server[tool["name"]] = srv_name
                else:
                    logger.warning(f"Error querying tools from server '{srv_name}': {res}")

        return all_tools

    async def call_tool(
        self,
        server_name: Optional[str],
        tool_name: str,
        arguments: Dict[str, Any]
    ) -> str:
        """Executes a tool on the designated server, or infers server from tool registry."""
        if not self._initialized:
            self.load_configurations()
            self._initialized = True

        # Inferred routing if server_name is omitted
        if not server_name:
            server_name = self._tool_to_server.get(tool_name)

        # Check if it corresponds to our internal MCP server
        from app.mcp.server import nexus_mcp_server
        if server_name in ("nexus-hub-mcp", "internal", None):
            try:
                res = await nexus_mcp_server.call_tool(tool_name, arguments)
                contents = getattr(res, "content", [])
                texts = [c.text for c in contents if hasattr(c, "text")]
                return "\n".join(texts)
            except Exception as e:
                # If specifically requested internal server, raise
                if server_name in ("nexus-hub-mcp", "internal"):
                    raise

        if not server_name or server_name not in self.servers:
            raise ValueError(f"Target MCP server not found for tool '{tool_name}' (specified: '{server_name}')")

        return await self.servers[server_name].call_tool(tool_name, arguments)

    async def fetch_resource(self, server_name: str, uri: str) -> str:
        """Retrieves contextual resource content from an MCP server."""
        if not self._initialized:
            self.load_configurations()
            self._initialized = True

        if server_name not in self.servers:
            raise ValueError(f"MCP server '{server_name}' not configured.")

        return await self.servers[server_name].fetch_resource(uri)


# Global singleton instance
_mcp_client_manager: Optional[MCPClientManager] = None


def get_mcp_client_manager() -> MCPClientManager:
    """Returns the singleton instance of MCPClientManager."""
    global _mcp_client_manager
    if _mcp_client_manager is None:
        _mcp_client_manager = MCPClientManager()
    return _mcp_client_manager
