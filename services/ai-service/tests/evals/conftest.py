import json
import uuid
from pathlib import Path
from typing import Dict, Any, List

import pytest
from qdrant_client import QdrantClient
from qdrant_client.http import models
from langgraph.checkpoint.memory import MemorySaver

import app.db.qdrant as qdrant_module
import app.rag.retriever as retriever_module
from app.core.config import settings
from app.rag.embeddings import get_embedding_service
from app.rag.retriever import HybridRetriever
from app.agents.graph import create_agent_graph

EVALS_DIR = Path(__file__).resolve().parent
GOLDEN_DATASET_PATH = EVALS_DIR / "golden_dataset.json"
REPORTS_DIR = EVALS_DIR / "reports"


@pytest.fixture(scope="session")
def golden_dataset() -> Dict[str, Any]:
    """Loads the curated enterprise golden evaluation dataset."""
    with GOLDEN_DATASET_PATH.open("r", encoding="utf-8") as f:
        return json.load(f)


@pytest.fixture(scope="session")
def eval_qdrant_client(golden_dataset: Dict[str, Any]) -> QdrantClient:
    """
    Creates an isolated in-memory Qdrant instance pre-seeded with multi-tenant
    golden corpus chunks (Tenant Alpha + Tenant Beta) using the real BGE embedding model.
    Runs 100% locally on CPU without external infrastructure dependencies.
    """
    client = QdrantClient(":memory:")
    col_name = settings.qdrant_collection_name
    embedding_service = get_embedding_service()

    client.create_collection(
        collection_name=col_name,
        vectors_config=models.VectorParams(
            size=embedding_service.dimension,
            distance=models.Distance.COSINE,
        ),
    )

    corpus: List[Dict[str, Any]] = golden_dataset["corpus"]
    contents = [doc["content"] for doc in corpus]
    vectors = embedding_service.embed_documents(contents)

    points = []
    for doc, vec in zip(corpus, vectors):
        point_id = str(uuid.uuid5(uuid.NAMESPACE_DNS, f"{doc['tenant_id']}:{doc['chunk_id']}"))
        points.append(
            models.PointStruct(
                id=point_id,
                vector=vec,
                payload={
                    "tenant_id": doc["tenant_id"],
                    "document_id": doc["document_id"],
                    "chunk_id": doc["chunk_id"],
                    "citation": doc["citation"],
                    "content": doc["content"],
                    "metadata": doc.get("metadata", {}),
                },
            )
        )

    client.upsert(collection_name=col_name, points=points)
    return client


@pytest.fixture(scope="function")
def eval_retriever(eval_qdrant_client: QdrantClient, monkeypatch: pytest.MonkeyPatch) -> HybridRetriever:
    """
    Patches Qdrant client lookup to use the seeded in-memory multi-tenant Qdrant
    instance and returns a real HybridRetriever (dense + BM25 + CrossEncoder reranker).
    """
    monkeypatch.setattr(qdrant_module, "get_qdrant_client", lambda: eval_qdrant_client)
    monkeypatch.setattr(retriever_module, "get_qdrant_client", lambda: eval_qdrant_client)
    retriever = HybridRetriever()
    monkeypatch.setattr(retriever_module, "_retriever_instance", retriever)
    monkeypatch.setattr(retriever_module, "get_hybrid_retriever", lambda: retriever)
    return retriever


@pytest.fixture(scope="function")
def eval_agent_graph(eval_retriever: HybridRetriever, monkeypatch: pytest.MonkeyPatch):
    """
    Compiles the real LangGraph Multi-Agent workflow (Planner -> Researcher -> Synthesizer -> Critic)
    with in-memory checkpointing and wired to the seeded eval_retriever (with MCP gracefully mocked offline).
    """
    import app.agents.nodes.researcher as researcher_module

    class _NoOpMCPManager:
        async def list_all_tools(self):
            return []

    monkeypatch.setattr(researcher_module, "get_hybrid_retriever", lambda: eval_retriever)
    monkeypatch.setattr(researcher_module, "get_mcp_client_manager", lambda: _NoOpMCPManager())
    return create_agent_graph(checkpointer=MemorySaver())
