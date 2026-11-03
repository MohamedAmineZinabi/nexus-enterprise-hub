import os
import asyncio
import logging
from mcp.server.mcpserver import MCPServer

logger = logging.getLogger("mcp.filesystem_server")

server = MCPServer("enterprise-filesystem-mcp")

ALLOWED_DIR = os.environ.get("ALLOWED_DIRS", "/shared-uploads")


@server.tool(
    name="list_shared_files",
    description="List all files available in the enterprise shared uploads volume."
)
def list_shared_files(subpath: str = "") -> str:
    """Lists files within the authorized shared uploads volume."""
    target_dir = os.path.join(ALLOWED_DIR, subpath.lstrip("/\\"))
    if not os.path.exists(target_dir):
        return f"Directory does not exist: {target_dir}"

    entries = []
    for root, dirs, files in os.walk(target_dir):
        for f in files:
            full_path = os.path.join(root, f)
            rel_path = os.path.relpath(full_path, ALLOWED_DIR)
            try:
                size_kb = os.path.getsize(full_path) / 1024
                entries.append(f"{rel_path} ({size_kb:.1f} KB)")
            except Exception:
                entries.append(rel_path)

    if not entries:
        return f"No files found in directory: {target_dir}"
    return "Available Shared Files:\n- " + "\n- ".join(entries)


@server.tool(
    name="read_shared_file_preview",
    description="Reads a preview (first 1000 characters) of a shared enterprise file."
)
def read_shared_file_preview(filename: str) -> str:
    """Reads the initial content of a file stored in the shared volume."""
    target_path = os.path.join(ALLOWED_DIR, filename.lstrip("/\\"))
    if not os.path.exists(target_path):
        return f"File not found: {filename}"

    try:
        with open(target_path, "r", encoding="utf-8", errors="ignore") as f:
            content = f.read(1000)
        return f"Preview of {filename}:\n\n{content}"
    except Exception as e:
        return f"Error reading file {filename}: {e}"


if __name__ == "__main__":
    asyncio.run(server.run_stdio_async())

