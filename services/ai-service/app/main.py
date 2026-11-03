import asyncio
import logging
from contextlib import asynccontextmanager
from typing import Optional, List, Dict, Any

from fastapi import FastAPI, UploadFile, File, Form, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from app.core.config import settings
from app.db.qdrant import init_qdrant_collection, get_qdrant_client
from app.rag.embeddings import get_embedding_service
from app.rag.reranker import get_cross_encoder_service
from app.rag.ingestion import get_document_processor
from app.rag.retrieval import get_hybrid_retriever
from app.grpc.server import start_grpc_server, stop_grpc_server
from app.mcp.client_manager import get_mcp_client_manager
from app.mcp.server import get_mcp_sse_app

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("nexus.ai_service")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Lifecycle context manager: handles startup and graceful shutdown."""
    logger.info("=== Initializing NexusEnterprise AI Service ===")

    # 1. Initialize Vector Database Schema
    try:
        init_qdrant_collection()
        logger.info(f"Qdrant collection '{settings.collection_name}' initialized.")
    except Exception as e:
        logger.error(f"Failed to initialize Qdrant: {e}", exc_info=True)

    # 2. Pre-warm Neural Models (CPU inference)
    logger.info("Pre-warming Embedding and CrossEncoder neural models...")
    get_embedding_service()
    get_cross_encoder_service()
    logger.info("Neural models successfully loaded into memory.")

    # 3. Start gRPC Server
    grpc_task = asyncio.create_task(start_grpc_server())

    # 4. Initialize MCP Client Manager
    mcp_manager = get_mcp_client_manager()
    try:
        await mcp_manager.initialize()
        logger.info("MCP Client Manager initialized.")
    except Exception as e:
        logger.warning(f"Error initializing MCP Client Manager: {e}")

    logger.info(f"AI Service ready: HTTP port {settings.http_port}, gRPC port {settings.grpc_port}")

    yield

    # Teardown logic
    logger.info("=== Shutting down NexusEnterprise AI Service ===")
    await mcp_manager.close()
    await stop_grpc_server(grace=3.0)
    try:
        await grpc_task
    except Exception:
        pass


app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    description="NexusEnterprise Hub AI Service: LangChain RAG, Qdrant & Model Context Protocol (MCP)",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Mount MCP Server SSE Application (/mcp/sse and /mcp/messages)
app.mount("/mcp", get_mcp_sse_app())


class HealthResponse(BaseModel):
    status: str
    service: str
    version: str


class ReadinessResponse(BaseModel):
    status: str
    qdrant: bool
    grpc_port: int
    mcp_active: bool


class RestSearchRequest(BaseModel):
    tenant_id: str
    query: str
    top_k: int = 5
    alpha: float = 0.7
    rerank: bool = True
    collection_name: str | None = None


class McpCallRequest(BaseModel):
    tool_name: str
    server_name: Optional[str] = None
    arguments: Dict[str, Any] = {}


@app.get("/healthz", response_model=HealthResponse, tags=["Health"])
async def health_check():
    """Liveness probe returning basic service operational status."""
    return HealthResponse(
        status="healthy",
        service=settings.app_name,
        version=settings.app_version
    )


@app.get("/readyz", response_model=ReadinessResponse, tags=["Health"])
async def readiness_check():
    """Readiness probe checking Qdrant, gRPC, and MCP connectivity."""
    qdrant_ok = False
    try:
        client = get_qdrant_client()
        qdrant_ok = client.collection_exists(settings.collection_name)
    except Exception as e:
        logger.warning(f"Readiness check failed for Qdrant: {e}")

    return ReadinessResponse(
        status="ready" if qdrant_ok else "degraded",
        qdrant=qdrant_ok,
        grpc_port=settings.grpc_port,
        mcp_active=True
    )


@app.post("/api/v1/documents/index", tags=["Documents"])
async def upload_and_index_document(
    tenant_id: str = Form(...),
    document_id: str = Form(...),
    file: UploadFile = File(...)
):
    """Upload and process a document into vector embeddings."""
    processor = get_document_processor()
    try:
        file_bytes = await file.read()
        num_chunks = await processor.process_and_index(
            tenant_id=tenant_id,
            document_id=document_id,
            filename=file.filename or "unknown",
            file_bytes=file_bytes
        )
        return {
            "status": "SUCCESS",
            "document_id": document_id,
            "filename": file.filename,
            "chunks_indexed": num_chunks
        }
    except Exception as e:
        logger.error(f"Failed to process and index document {document_id}: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Indexing failed: {str(e)}"
        )


@app.post("/api/v1/search/hybrid", tags=["Search"])
async def rest_hybrid_search(payload: RestSearchRequest):
    """Hybrid semantic vector + BM25 keyword search with Cross-Encoder reranking."""
    retriever = get_hybrid_retriever()
    try:
        results = await retriever.search(
            tenant_id=payload.tenant_id,
            query=payload.query,
            top_k=payload.top_k,
            alpha=payload.alpha,
            rerank=payload.rerank,
            collection_name=payload.collection_name
        )
        return {
            "query": payload.query,
            "tenant_id": payload.tenant_id,
            "total_results": len(results),
            "results": results
        }
    except Exception as e:
        logger.error(f"Hybrid search failed: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(e)
        )


@app.get("/api/v1/mcp/tools", tags=["MCP"])
async def list_mcp_tools():
    """Returns all active MCP tools across native and connected external servers."""
    mcp_manager = get_mcp_client_manager()
    ext_tools = await mcp_manager.list_all_tools()
    native_tools = [
        {
            "name": "nexus_hybrid_search",
            "description": "Performs dense vector and sparse BM25 hybrid search with Cross-Encoder reranking",
            "server_name": "nexus-hub-mcp",
            "server_origin": "mcp://ai-service:50051"
        },
        {
            "name": "nexus_document_summarizer",
            "description": "Summarizes enterprise documents with citation extraction",
            "server_name": "nexus-hub-mcp",
            "server_origin": "mcp://ai-service:50051"
        }
    ]
    return {
        "total": len(ext_tools) + len(native_tools),
        "native_tools": native_tools,
        "external_tools": ext_tools
    }


@app.post("/api/v1/mcp/call", tags=["MCP"])
async def call_mcp_tool(req: McpCallRequest):
    """Directly executes an MCP tool by name with arguments."""
    mcp_manager = get_mcp_client_manager()
    try:
        output = await mcp_manager.call_tool(
            server_name=req.server_name,
            tool_name=req.tool_name,
            arguments=req.arguments
        )
        return {
            "tool_name": req.tool_name,
            "server_name": req.server_name,
            "output": output
        }
    except Exception as e:
        logger.error(f"MCP tool call failed: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(e)
        )
