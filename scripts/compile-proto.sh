#!/usr/bin/env bash
set -e

# Prevent Git Bash / MSYS from translating Linux container paths (e.g. /workspace)
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

# ==============================================================================
# NexusEnterprise Hub - Protocol Buffer Compilation Tool
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROTO_DIR="${PROJECT_ROOT}/proto"
PYTHON_OUT_DIR="${PROJECT_ROOT}/services/ai-service/app/grpc/generated"

echo "=========================================================="
echo "NexusEnterprise Hub - Protocol Buffer Compiler"
echo "Proto Directory: ${PROTO_DIR}"
echo "Python Target:   ${PYTHON_OUT_DIR}"
echo "=========================================================="

# Ensure target directories exist
mkdir -p "${PYTHON_OUT_DIR}/ai/v1"

# Compile via containerized protoc runner (guarantees identical compiler version)
echo "Compiling .proto definitions using python:3.11-slim + grpcio-tools..."
docker run --rm -v "${PROJECT_ROOT}:/workspace" -w /workspace python:3.11-slim sh -c \
  "pip install --quiet grpcio-tools && python -m grpc_tools.protoc -I proto --python_out=services/ai-service/app/grpc/generated --grpc_python_out=services/ai-service/app/grpc/generated --pyi_out=services/ai-service/app/grpc/generated proto/ai/common.proto proto/ai/v1/rag_service.proto proto/ai/v1/agent_service.proto"

# ------------------------------------------------------------------------------
# Create package __init__.py files
# ------------------------------------------------------------------------------
echo "Initializing Python package exports..."

cat << 'EOF' > "${PYTHON_OUT_DIR}/__init__.py"
import os
import sys

# Ensure this directory is in sys.path for direct imports
_pkg_dir = os.path.dirname(os.path.abspath(__file__))
if _pkg_dir not in sys.path:
    sys.path.insert(0, _pkg_dir)
EOF

touch "${PYTHON_OUT_DIR}/ai/__init__.py"
touch "${PYTHON_OUT_DIR}/ai/v1/__init__.py"

# ------------------------------------------------------------------------------
# Patch imports for clean resolution inside FastAPI app package
# ------------------------------------------------------------------------------
echo "Patching gRPC imports for FastAPI package compatibility..."
docker run --rm -v "${PROJECT_ROOT}:/workspace" -w /workspace python:3.11-slim python -c "
import glob, re

for f in glob.glob('services/ai-service/app/grpc/generated/**/*.py', recursive=True):
    with open(f, 'r', encoding='utf-8') as fp:
        content = fp.read()
    new_content = re.sub(r'from ai import', 'from app.grpc.generated.ai import', content)
    new_content = re.sub(r'from ai\.v1 import', 'from app.grpc.generated.ai.v1 import', new_content)
    with open(f, 'w', encoding='utf-8') as fp:
        fp.write(new_content)
"

echo "=========================================================="
echo "Protocol Buffer Compilation Completed Successfully!"
echo "=========================================================="
