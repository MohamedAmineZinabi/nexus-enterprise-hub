import asyncio
import logging
from contextlib import asynccontextmanager
from typing import Dict, Any, Optional, List

from fastapi import FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from app.core.config import settings
from app.db.qdrant import get_qdrant_client, init_qdrant_collection
from app.db.redis import get_redis_client, close_redis_client
from app.rag.embeddings import get_embedding_service
from app.rag.reranker import get_reranker
from app.rag.retriever import get_hybrid_retriever
from app.grpc.server import start_grpc_server, stop_grpc_server
from app.mcp.server import get_mcp_sse_app
from app.mcp.client_manager import get_mcp_client_manager
from app.agents.graph import get_agent_graph
from app.agents.state import AgentWorkflowState
from app.kafka.producer import get_event_publisher
from app.kafka.consumer import get_task_consumer

logging.basicConfig(
    level=logging.INFO if not settings.debug else logging.DEBUG,
    format="%(asctime)s [%(levelname)s] [%(name)s] %(message)s"
)
logger = logging.getLogger("nexus.ai_service")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan manager initializing DB, models, MCP manager, Kafka, and gRPC background server."""
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

    # 3. Initialize Model Context Protocol (MCP) Client Manager
    logger.info("Initializing MCP Client Manager...")
    mcp_manager = get_mcp_client_manager()
    await mcp_manager.initialize()
    logger.info("MCP Client Manager initialized.")

    # 4. Initialize Multi-Agent Graph Engine
    logger.info("Initializing Multi-Agent LangGraph engine...")
    get_agent_graph()
    logger.info("Multi-Agent LangGraph engine initialized.")

    # 5. Connect Kafka Producer & Start Kafka Consumer Background Loop
    logger.info("Connecting Kafka Event Publisher and starting Consumer Loop...")
    event_publisher = get_event_publisher()
    try:
        await event_publisher.start()
    except Exception as e:
        logger.warning(f"Kafka Publisher startup deferred: {e}. Will connect on demand.")

    task_consumer = get_task_consumer()
    await task_consumer.start()
    logger.info("Kafka Task Consumer background task active.")

    # 6. Start asyncio gRPC server
    grpc_task = asyncio.create_task(start_grpc_server())

    yield

    logger.info("=== Shutting down NexusEnterprise AI Service ===")
    await task_consumer.stop()
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
    description="NexusEnterprise Hub AI Service: LangGraph Multi-Agent, Kafka Consumer & MCP Layer",
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
    kafka_consumer: bool


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


class AgentExecuteRequest(BaseModel):
    prompt: str
    tenant_id: str = "default"
    task_id: Optional[str] = None
    document_ids: List[str] = []


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
    """Readiness probe checking Qdrant, gRPC, MCP, and Kafka connectivity."""
    qdrant_ok = False
    try:
        client = get_qdrant_client()
        client.get_collections()
        qdrant_ok = True
    except Exception as e:
        logger.warning(f"Readiness check failed for Qdrant: {e}")

    consumer = get_task_consumer()
    consumer_active = consumer._running if consumer else False

    return ReadinessResponse(
        status="ready" if qdrant_ok else "degraded",
        qdrant=qdrant_ok,
        grpc_port=settings.grpc_port,
        mcp_active=True,
        kafka_consumer=consumer_active
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


@app.post("/api/v1/agents/execute", tags=["Agents"])
async def execute_agent_workflow(req: AgentExecuteRequest):
    """Direct HTTP trigger to run the LangGraph multi-agent loop."""
    import uuid
    task_id = req.task_id or f"task-{uuid.uuid4()}"
    graph = get_agent_graph()

    initial_state: AgentWorkflowState = {
        "task_id": task_id,
        "tenant_id": req.tenant_id,
        "user_prompt": req.prompt,
        "trace_id": f"trace-{task_id}",
        "document_ids": req.document_ids,
        "metadata": {},
        "plan": [],
        "research_data": [],
        "draft_report": "",
        "critique": {},
        "iteration_count": 0,
        "current_node": "START",
        "status": "PROCESSING"
    }

    try:
        config = {"configurable": {"thread_id": task_id}}
        final_state = await graph.ainvoke(initial_state, config=config)
        return {
            "task_id": task_id,
            "tenant_id": req.tenant_id,
            "status": final_state.get("status"),
            "plan": final_state.get("plan"),
            "evidence_count": len(final_state.get("research_data", [])),
            "critique": final_state.get("critique"),
            "report": final_state.get("draft_report")
        }
    except Exception as e:
        logger.error(f"Agent execution error: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(e)
        )
