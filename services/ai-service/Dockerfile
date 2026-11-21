# ==============================================================================
# Dockerfile for NexusEnterprise Hub - AI Service (FastAPI + gRPC + LangGraph)
#   target "runtime" (default, last stage): production image, runtime deps only
#   target "dev":                           runtime + test/eval tooling + tests
# ==============================================================================
FROM python:3.11-slim AS base

# Prevent Python from writing .pyc files and buffer stdout/stderr
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app \
    HF_HOME=/opt/hf-cache

WORKDIR /app

# Install system utilities and the unprivileged runtime user. UID/GID 1000 is shared with
# core-service so both containers can read/write the shared /shared-uploads volume.
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 1000 nexus \
    && useradd --uid 1000 --gid 1000 --create-home --shell /usr/sbin/nologin nexus

# Install PyTorch CPU first to prevent downloading large CUDA wheels
RUN pip install --no-cache-dir --upgrade pip uv && \
    pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu

# Production runtime dependencies (uv + BuildKit cache for fast, deterministic builds)
COPY services/ai-service/requirements.txt .
RUN --mount=type=cache,target=/root/.cache/uv \
    UV_HTTP_TIMEOUT=180 UV_CONCURRENT_DOWNLOADS=4 uv pip install --system -r requirements.txt

# Pre-cache HuggingFace SentenceTransformer and CrossEncoder models in image
RUN python -c "from sentence_transformers import SentenceTransformer, CrossEncoder; \
    SentenceTransformer('BAAI/bge-small-en-v1.5'); \
    CrossEncoder('cross-encoder/ms-marco-MiniLM-L-6-v2')" \
    && chown -R 1000:1000 /opt/hf-cache

# ------------------------------------------------------------------------------
# dev: tests, AI evals and linting (never deployed)
# ------------------------------------------------------------------------------
FROM base AS dev
COPY services/ai-service/requirements-dev.txt .
RUN --mount=type=cache,target=/root/.cache/uv \
    UV_HTTP_TIMEOUT=180 UV_CONCURRENT_DOWNLOADS=4 uv pip install --system -r requirements-dev.txt
COPY services/ai-service/app ./app
COPY services/ai-service/tests ./tests
COPY services/ai-service/pytest.ini .
ENTRYPOINT ["python", "-m", "pytest"]
CMD ["-q", "--ignore=tests/test_multi_agent_kafka.py"]

# ------------------------------------------------------------------------------
# runtime: production image
# ------------------------------------------------------------------------------
FROM base AS runtime

# Copy application source code
COPY services/ai-service/app ./app

# Shared uploads volume mount point, owned by the shared nexus UID/GID (1000)
RUN mkdir -p /shared-uploads && chown 1000:1000 /shared-uploads

USER nexus

# Expose FastAPI REST (8000) and gRPC (50051) - internal network only
EXPOSE 8000
EXPOSE 50051

HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=30s \
    CMD curl -f http://localhost:8000/healthz || exit 1

ENTRYPOINT ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
