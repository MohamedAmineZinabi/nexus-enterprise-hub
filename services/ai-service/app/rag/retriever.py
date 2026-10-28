import re
import logging
from dataclasses import dataclass, field
from typing import List, Dict, Optional, Any
from rank_bm25 import BM25Okapi
from qdrant_client.http import models

from app.core.config import settings
from app.db.qdrant import get_qdrant_client
from app.rag.embeddings import get_embedding_service
from app.rag.reranker import get_reranker

logger = logging.getLogger(__name__)

_retriever_instance: Optional["HybridRetriever"] = None


@dataclass
class SearchResultChunk:
    """Represents a matched chunk returned from hybrid vector & keyword retrieval."""
    chunk_id: str
    document_id: str
    content: str
    score: float
    rerank_score: float = 0.0
    metadata: Dict[str, str] = field(default_factory=dict)
    citation: str = ""


def _tokenize(text: str) -> List[str]:
    """Lightweight whitespace and alphanumeric tokenizer for BM25."""
    return re.findall(r"\w+", text.lower())


class HybridRetriever:
    """Enterprise hybrid retrieval engine blending dense semantic vector search and sparse BM25."""

    def __init__(self):
        self.embedding_service = get_embedding_service()
        self.reranker = get_reranker()

    def search(
        self,
        tenant_id: str,
        query: str,
        top_k: int = 5,
        alpha: float = 0.7,
        collection_name: Optional[str] = None,
        metadata_filters: Optional[Dict[str, str]] = None,
        rerank: bool = True
    ) -> List[SearchResultChunk]:
        """Execute hybrid search strictly isolated to tenant_id with optional cross-encoder reranking."""
        col_name = collection_name or settings.qdrant_collection_name
        client = get_qdrant_client()

        # Step 1: Compute query embedding
        query_vector = self.embedding_service.embed_query(query)

        # Step 2: Build strict multi-tenant payload filter
        must_conditions: List[models.Condition] = [
            models.FieldCondition(
                key="tenant_id",
                match=models.MatchValue(value=tenant_id)
            )
        ]

        if metadata_filters:
            for k, v in metadata_filters.items():
                must_conditions.append(
                    models.FieldCondition(
                        key=f"metadata.{k}",
                        match=models.MatchValue(value=v)
                    )
                )

        qdrant_filter = models.Filter(must=must_conditions)
        candidate_limit = max(top_k * 4, settings.rerank_candidates_top_n)

        logger.info(
            f"Searching Qdrant collection '{col_name}' for tenant '{tenant_id}', "
            f"query='{query}', limit={candidate_limit}"
        )

        try:
            query_response = client.query_points(
                collection_name=col_name,
                query=query_vector,
                query_filter=qdrant_filter,
                limit=candidate_limit,
                with_payload=True
            )
            hits = query_response.points
        except Exception as e:
            logger.error(f"Qdrant query_points error in collection '{col_name}': {e}", exc_info=True)
            return []

        if not hits:
            logger.info(f"No vector hits found in '{col_name}' for tenant '{tenant_id}'.")
            return []

        # Step 3: BM25 Sparse Keyword Scoring across candidates
        candidate_chunks: List[SearchResultChunk] = []
        corpus_tokenized: List[List[str]] = []

        for hit in hits:
            payload = hit.payload or {}
            content = payload.get("content", "")
            chunk_id = payload.get("chunk_id", str(hit.id))
            doc_id = payload.get("document_id", "")
            citation = payload.get("citation", "")
            meta = {str(k): str(v) for k, v in payload.get("metadata", {}).items()}

            candidate_chunks.append(
                SearchResultChunk(
                    chunk_id=chunk_id,
                    document_id=doc_id,
                    content=content,
                    score=float(hit.score),
                    metadata=meta,
                    citation=citation
                )
            )
            corpus_tokenized.append(_tokenize(content))

        query_tokens = _tokenize(query)
        bm25_scores = [0.0] * len(candidate_chunks)

        if any(corpus_tokenized) and query_tokens:
            try:
                bm25 = BM25Okapi(corpus_tokenized)
                raw_bm25 = bm25.get_scores(query_tokens)
                max_bm25 = max(raw_bm25) if len(raw_bm25) > 0 else 0.0
                min_bm25 = min(raw_bm25) if len(raw_bm25) > 0 else 0.0

                if max_bm25 > min_bm25:
                    bm25_scores = [float((s - min_bm25) / (max_bm25 - min_bm25)) for s in raw_bm25]
                elif max_bm25 > 0.0:
                    bm25_scores = [1.0] * len(raw_bm25)
            except Exception as e:
                logger.warning(f"BM25 scoring failed, relying on dense score: {e}")

        # Step 4: Alpha Combination: Score = alpha * dense + (1 - alpha) * bm25
        clamped_alpha = max(0.0, min(1.0, alpha))
        for idx, chunk in enumerate(candidate_chunks):
            dense_score = max(0.0, min(1.0, chunk.score))
            sparse_score = bm25_scores[idx]
            combined = (clamped_alpha * dense_score) + ((1.0 - clamped_alpha) * sparse_score)
            chunk.score = float(combined)

        # Sort candidate chunks by combined score descending
        candidate_chunks.sort(key=lambda c: c.score, reverse=True)

        # Step 5: Cross-Encoder Reranking
        if rerank and candidate_chunks:
            logger.info(f"Applying Cross-Encoder reranking on top {len(candidate_chunks)} candidates...")
            return self.reranker.rerank(query, candidate_chunks, top_k=top_k)

        return candidate_chunks[:top_k]


def get_hybrid_retriever() -> HybridRetriever:
    """Return the singleton instance of HybridRetriever."""
    global _retriever_instance
    if _retriever_instance is None:
        _retriever_instance = HybridRetriever()
    return _retriever_instance
