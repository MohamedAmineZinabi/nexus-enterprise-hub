import os
import re
import asyncio
import logging
from mcp.server.mcpserver import MCPServer

logger = logging.getLogger("mcp.filesystem_server")

server = MCPServer("enterprise-filesystem-mcp")

# Root of the shared uploads volume. Each tenant owns exactly one sub-directory: <root>/<tenant_id>/
SHARED_ROOT = os.path.realpath(
    os.environ.get("SHARED_UPLOADS_DIR") or os.environ.get("ALLOWED_DIRS") or "/shared-uploads"
)

_TENANT_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
MAX_PREVIEW_CHARS = 1000


class PathAccessError(PermissionError):
    """Raised when a requested path escapes the tenant's sandbox."""


def _tenant_root(tenant_id: str) -> str:
    """Returns the canonical sandbox directory for a validated tenant_id."""
    value = (tenant_id or "").strip()
    if not value or not _TENANT_ID_PATTERN.match(value):
        raise PathAccessError("A valid tenant_id is required")
    return os.path.realpath(os.path.join(SHARED_ROOT, value))


def _is_within(root: str, candidate: str) -> bool:
    try:
        return os.path.commonpath([root, candidate]) == root
    except ValueError:  # different drives on Windows
        return False


def resolve_tenant_path(tenant_id: str, relative_path: str = "") -> str:
    """
    Resolves ``relative_path`` inside the tenant sandbox, rejecting absolute paths, '..' segments,
    and symlink escapes. Returns the canonical absolute path.
    """
    root = _tenant_root(tenant_id)
    rel = (relative_path or "").replace("\\", "/")
    if rel.startswith("/") or re.match(r"^[A-Za-z]:", rel):
        raise PathAccessError("Absolute paths are not allowed")
    if any(part == ".." for part in rel.split("/")):
        raise PathAccessError("Parent directory references are not allowed")

    resolved = os.path.realpath(os.path.join(root, rel))
    if not _is_within(root, resolved):
        raise PathAccessError("Path escapes the tenant sandbox")
    return resolved


@server.tool(
    name="list_shared_files",
    description="List files in the calling tenant's shared uploads directory."
)
def list_shared_files(tenant_id: str, subpath: str = "") -> str:
    """Lists files strictly inside <shared-root>/<tenant_id>/[subpath]."""
    root = _tenant_root(tenant_id)
    target_dir = resolve_tenant_path(tenant_id, subpath)
    if not os.path.isdir(target_dir):
        return "No files found in the requested directory."

    entries = []
    for current, dirs, files in os.walk(target_dir, followlinks=False):
        # Never descend into symlinked directories
        dirs[:] = [d for d in dirs if not os.path.islink(os.path.join(current, d))]
        for f in files:
            full_path = os.path.join(current, f)
            real_path = os.path.realpath(full_path)
            if not _is_within(root, real_path):
                continue  # symlink pointing outside the tenant sandbox
            rel_path = os.path.relpath(full_path, root).replace("\\", "/")
            try:
                size_kb = os.path.getsize(real_path) / 1024
                entries.append(f"{rel_path} ({size_kb:.1f} KB)")
            except OSError:
                entries.append(rel_path)

    if not entries:
        return "No files found in the requested directory."
    return "Available Shared Files:\n- " + "\n- ".join(sorted(entries))


@server.tool(
    name="read_shared_file_preview",
    description="Reads a preview (first 1000 characters) of a file in the calling tenant's shared directory."
)
def read_shared_file_preview(tenant_id: str, filename: str) -> str:
    """Reads the initial content of a file stored in the tenant's sandbox."""
    target_path = resolve_tenant_path(tenant_id, filename)
    if not os.path.isfile(target_path):
        return f"File not found: {filename}"

    with open(target_path, "r", encoding="utf-8", errors="ignore") as f:
        content = f.read(MAX_PREVIEW_CHARS)
    return f"Preview of {filename}:\n\n{content}"


if __name__ == "__main__":
    asyncio.run(server.run_stdio_async())
