import asyncio
import logging
from contextlib import asynccontextmanager
from typing import Dict, Any

from fastapi import FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from app.core.config import settings
from app.db.qdrant import get_qdrant_client, init_qdrant_collection
from app.rag.embeddings import get_embedding_service
from app.rag.reranker import get_reranker
from app.rag.retriever import get_hybrid_retriever
from app.grpc.server import start_grpc_server, stop_grpc_server

logging.basicConfig(
    level=logging.INFO if not settings.debug else logging.DEBUG,
    format="%(asctime)s [%(levelname)s] [%(name)s] %(message)s"
)
logger = logging.getLogger("nexus.ai_service")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan manager initializing DB, models, and gRPC background server."""
    logger.info("=== Initializing NexusEnterprise AI Service ===")

    # 1. Warm up vector collection in Qdrant
    try:
        init_qdrant_collection()
        logger.info(f"Qdrant collection '{settings.qdrant_collection_name}' initialized.")
    except Exception as e:
        logger.warning(f"Could not connect to Qdrant during startup: {e}. Will retry on request.")

    # 2. Warm up neural models in background / worker memory
    logger.info("Pre-warming Embedding and CrossEncoder neural models...")
    get_embedding_service()
    get_reranker()
    get_hybrid_retriever()
    logger.info("Neural models pre-warmed and ready for inference.")

    # 3. Start asyncio gRPC server
    grpc_task = asyncio.create_task(start_grpc_server())

    yield

    logger.info("=== Shutting down NexusEnterprise AI Service ===")
    await stop_grpc_server(grace=3.0)
    try:
        await grpc_task
    except Exception:
        pass


app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    description="NexusEnterprise Hub AI Service: LangChain RAG, Hybrid Search & gRPC Bridge",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class HealthResponse(BaseModel):
    status: str
    service: str
    version: str


class ReadinessResponse(BaseModel):
    status: str
    qdrant: bool
    grpc_port: int


class RestSearchRequest(BaseModel):
    tenant_id: str
    query: str
    top_k: int = 5
    alpha: float = 0.7
    rerank: bool = True
    collection_name: str | None = None


@app.get("/healthz", response_model=HealthResponse, tags=["Health"])
async def health_check():
    """Liveness probe returning basic service operational status."""
    return HealthResponse(
        status="ok",
        service="ai-service",
        version=settings.app_version
    )


@app.get("/readyz", response_model=ReadinessResponse, tags=["Health"])
async def readiness_check():
    """Readiness probe checking Qdrant and backing resource connectivity."""
    qdrant_ok = False
    try:
        client = get_qdrant_client()
        client.get_collections()
        qdrant_ok = True
    except Exception as e:
        logger.warning(f"Readiness check failed for Qdrant: {e}")

    return ReadinessResponse(
        status="ready" if qdrant_ok else "degraded",
        qdrant=qdrant_ok,
        grpc_port=settings.grpc_port
    )


@app.post("/api/v1/rag/search", tags=["RAG"])
async def rest_hybrid_search(payload: RestSearchRequest):
    """Direct REST endpoint for hybrid search and reranking verification."""
    try:
        retriever = get_hybrid_retriever()
        results = retriever.search(
            tenant_id=payload.tenant_id,
            query=payload.query,
            top_k=payload.top_k,
            alpha=payload.alpha,
            collection_name=payload.collection_name,
            rerank=payload.rerank
        )
        return {
            "query": payload.query,
            "total_found": len(results),
            "results": [
                {
                    "chunk_id": r.chunk_id,
                    "document_id": r.document_id,
                    "content": r.content,
                    "score": r.score,
                    "rerank_score": r.rerank_score,
                    "metadata": r.metadata,
                    "citation": r.citation
                }
                for r in results
            ]
        }
    except Exception as e:
        logger.error(f"Search failed: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(e)
        )
