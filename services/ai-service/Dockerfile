# ==============================================================================
# Dockerfile for NexusEnterprise Hub - AI Service (FastAPI + gRPC + LangChain)
# ==============================================================================
FROM python:3.11-slim

# Prevent Python from writing .pyc files and buffer stdout/stderr
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app

WORKDIR /app

# Install system utilities
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install PyTorch CPU first to prevent downloading large CUDA wheels
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu

# Install service dependencies
COPY services/ai-service/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Pre-cache HuggingFace SentenceTransformer and CrossEncoder models in image
RUN python -c "from sentence_transformers import SentenceTransformer, CrossEncoder; \
    SentenceTransformer('BAAI/bge-small-en-v1.5'); \
    CrossEncoder('cross-encoder/ms-marco-MiniLM-L-6-v2')"

# Copy application source code
COPY services/ai-service/app ./app

# Create shared uploads directory inside container
RUN mkdir -p /shared-uploads

# Expose FastAPI REST (8000) and gRPC (50051)
EXPOSE 8000
EXPOSE 50051

HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=30s \
    CMD curl -f http://localhost:8000/healthz || exit 1

ENTRYPOINT ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
