import asyncio
import logging
import uuid
from contextlib import asynccontextmanager
from typing import Dict, Any, Optional, List

from fastapi import FastAPI, HTTPException, status
from pydantic import BaseModel, Field

from app.core.config import settings
from app.db.qdrant import get_qdrant_client, init_qdrant_collection
from app.db.redis import close_redis_client
from app.rag.embeddings import get_embedding_service
from app.rag.reranker import get_reranker
from app.rag.retriever import get_hybrid_retriever
from app.grpc.server import start_grpc_server, stop_grpc_server
from app.mcp.server import get_mcp_sse_app
from app.mcp.client_manager import get_mcp_client_manager
from app.agents.graph import get_agent_graph
from app.agents.state import build_initial_state
from app.kafka.producer import get_event_publisher
from app.kafka.consumer import get_task_consumer
from app.kafka.ingest_consumer import get_ingest_consumer
from app.mcp.tenant_tools import (
    ALLOWED_TENANT_TOOLS,
    InvalidToolArgumentError,
    ToolNotAllowedError,
    call_tenant_scoped_tool,
)
from app.security.auth import InternalApiKeyMiddleware, require_tenant_id, TenantValidationError

logging.basicConfig(
    level=logging.INFO if not settings.debug else logging.DEBUG,
    format="%(asctime)s [%(levelname)s] [%(name)s] %(message)s"
)
logger = logging.getLogger("nexus.ai_service")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan manager initializing DB, models, MCP manager, Kafka, and gRPC background server."""
    logger.info("=== Initializing NexusEnterprise AI Service ===")
    logger.info(
        "LLM mode: %s",
        f"enabled (model={settings.llm_model})" if settings.llm_enabled else "disabled (deterministic fallback)",
    )

    # 1. Warm up vector collection in Qdrant
    try:
        init_qdrant_collection()
        logger.info(f"Qdrant collection '{settings.qdrant_collection_name}' initialized.")
    except Exception as e:
        logger.warning(f"Could not connect to Qdrant during startup: {e}. Will retry on request.")

    # 2. Warm up neural models
    logger.info("Pre-warming Embedding and CrossEncoder neural models...")
    get_embedding_service()
    get_reranker()
    get_hybrid_retriever()
    logger.info("Neural models pre-warmed and ready for inference.")

    # 3. Initialize Model Context Protocol (MCP) Client Manager
    mcp_manager = get_mcp_client_manager()
    await mcp_manager.initialize()
    logger.info("MCP Client Manager initialized.")

    # 4. Initialize Multi-Agent Graph Engine
    get_agent_graph()
    logger.info("Multi-Agent LangGraph engine initialized.")

    # 5. Connect Kafka Producer & Start Kafka Consumer Background Loop
    event_publisher = get_event_publisher()
    try:
        await event_publisher.start()
    except Exception as e:
        logger.warning(f"Kafka Publisher startup deferred: {e}. Will connect on demand.")

    task_consumer = get_task_consumer()
    await task_consumer.start()
    logger.info("Kafka Task Consumer background task active.")

    ingest_consumer = get_ingest_consumer()
    await ingest_consumer.start()
    logger.info("Kafka Document Ingest Consumer background task active.")

    # 6. Start asyncio gRPC server
    grpc_task = asyncio.create_task(start_grpc_server())

    yield

    logger.info("=== Shutting down NexusEnterprise AI Service ===")
    await task_consumer.stop()
    await ingest_consumer.stop()
    await event_publisher.stop()
    await mcp_manager.close()
    await close_redis_client()
    await stop_grpc_server(grace=3.0)
    try:
        await grpc_task
    except Exception:
        pass


app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    description="NexusEnterprise Hub AI Service: LangGraph Multi-Agent, Kafka Consumer & MCP Layer (internal only)",
    lifespan=lifespan
)

# Internal service API: no browser CORS. Every route except /healthz and /readyz (including the
# mounted MCP SSE app and the OpenAPI docs) requires the X-Internal-Api-Key header.
app.add_middleware(InternalApiKeyMiddleware)

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
    mcp_servers_configured: int
    kafka_consumer: bool
    llm_enabled: bool


class RestSearchRequest(BaseModel):
    tenant_id: str = Field(..., min_length=1)
    query: str = Field(..., min_length=1)
    top_k: int = Field(default=5, ge=1, le=50)
    alpha: float = Field(default=0.7, ge=0.0, le=1.0)
    rerank: bool = True
    collection_name: str | None = None


class McpCallRequest(BaseModel):
    tool_name: str
    tenant_id: str = Field(..., min_length=1)
    arguments: Dict[str, Any] = Field(default_factory=dict)


class AgentExecuteRequest(BaseModel):
    prompt: str = Field(..., min_length=1)
    tenant_id: str = Field(..., min_length=1)
    task_id: Optional[str] = None
    document_ids: List[str] = Field(default_factory=list)


def _bad_request(exc: Exception) -> HTTPException:
    return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))


def _internal_error(context: str, exc: Exception) -> HTTPException:
    """Logs full details server-side and returns a generic error with a correlation id."""
    error_id = uuid.uuid4().hex
    logger.error(f"{context} failed (errorId={error_id}): {exc}", exc_info=True)
    return HTTPException(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        detail=f"{context} failed. Reference: {error_id}",
    )


@app.get("/healthz", response_model=HealthResponse, tags=["Health"])
async def health_check():
    """Liveness probe returning basic service operational status."""
    return HealthResponse(status="ok", service="ai-service", version=settings.app_version)


@app.get("/readyz", response_model=ReadinessResponse, tags=["Health"])
async def readiness_check():
    """Readiness probe checking Qdrant, MCP configuration, and Kafka consumer state."""
    qdrant_ok = False
    try:
        client = get_qdrant_client()
        await asyncio.to_thread(client.get_collections)
        qdrant_ok = True
    except Exception as e:
        logger.warning(f"Readiness check failed for Qdrant: {e}")

    consumer = get_task_consumer()
    consumer_active = bool(consumer and consumer._running) and get_ingest_consumer()._running
    mcp_manager = get_mcp_client_manager()

    return ReadinessResponse(
        status="ready" if qdrant_ok and consumer_active else "degraded",
        qdrant=qdrant_ok,
        grpc_port=settings.grpc_port,
        mcp_servers_configured=len(mcp_manager.servers),
        kafka_consumer=consumer_active,
        llm_enabled=settings.llm_enabled,
    )


@app.post("/api/v1/rag/search", tags=["RAG"])
async def rest_hybrid_search(payload: RestSearchRequest):
    """Internal REST endpoint for tenant-isolated hybrid search and reranking."""
    try:
        tenant_id = require_tenant_id(payload.tenant_id)
        collection = settings.resolve_collection(payload.collection_name)
    except (TenantValidationError, ValueError) as exc:
        raise _bad_request(exc)

    try:
        retriever = get_hybrid_retriever()
        results = await asyncio.to_thread(
            retriever.search,
            tenant_id=tenant_id,
            query=payload.query,
            top_k=payload.top_k,
            alpha=payload.alpha,
            collection_name=collection,
            rerank=payload.rerank,
        )
    except Exception as e:
        raise _internal_error("Search", e)

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
                "citation": r.citation,
            }
            for r in results
        ],
    }


@app.get("/api/v1/mcp/tools", tags=["MCP"])
async def list_mcp_tools():
    """Returns all MCP tools discovered across native and connected servers, flagging tenant-callable ones."""
    mcp_manager = get_mcp_client_manager()
    ext_tools = await mcp_manager.list_all_tools()
    return {
        "total": len(ext_tools),
        "tenant_callable_tools": sorted(ALLOWED_TENANT_TOOLS.keys()),
        "tools": ext_tools,
    }


@app.post("/api/v1/mcp/call", tags=["MCP"])
async def call_mcp_tool(req: McpCallRequest):
    """Executes an allowlisted MCP tool with a server-enforced tenant_id."""
    mcp_manager = get_mcp_client_manager()
    try:
        output = await call_tenant_scoped_tool(mcp_manager, req.tool_name, req.arguments, req.tenant_id)
    except ToolNotAllowedError as exc:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=str(exc))
    except (TenantValidationError, InvalidToolArgumentError) as exc:
        raise _bad_request(exc)
    except RuntimeError as exc:
        # Errors raised inside the sandboxed tool process (e.g. PathAccessError) arrive as
        # "MCP tool error (...)": these are caller errors, not server faults.
        if "MCP tool error" in str(exc):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Tool rejected the request arguments")
        raise _internal_error("MCP tool call", exc)
    except Exception as e:
        raise _internal_error("MCP tool call", e)

    return {"tool_name": req.tool_name, "tenant_id": req.tenant_id, "output": output}


@app.post("/api/v1/agents/execute", tags=["Agents"])
async def execute_agent_workflow(req: AgentExecuteRequest):
    """Internal HTTP trigger to run the LangGraph multi-agent loop for a tenant."""
    try:
        tenant_id = require_tenant_id(req.tenant_id)
    except TenantValidationError as exc:
        raise _bad_request(exc)

    task_id = req.task_id or f"task-{uuid.uuid4()}"
    graph = get_agent_graph()
    initial_state = build_initial_state(
        task_id=task_id,
        tenant_id=tenant_id,
        user_prompt=req.prompt,
        trace_id=f"trace-{task_id}",
        document_ids=req.document_ids,
    )

    try:
        config = {"configurable": {"thread_id": task_id}}
        final_state = await graph.ainvoke(initial_state, config=config)
    except Exception as e:
        raise _internal_error("Agent execution", e)

    return {
        "task_id": task_id,
        "tenant_id": tenant_id,
        "status": final_state.get("status"),
        "llm_mode": final_state.get("llm_mode"),
        "plan": final_state.get("plan"),
        "evidence_count": len(final_state.get("research_data", [])),
        "critique": final_state.get("critique"),
        "errors": final_state.get("errors", []),
        "token_usage": final_state.get("token_usage", 0),
        "report": final_state.get("draft_report"),
    }
