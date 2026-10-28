import logging
import math
from typing import List, Optional
from sentence_transformers import CrossEncoder
from app.core.config import settings

logger = logging.getLogger(__name__)

_reranker_instance: Optional["CrossEncoderReranker"] = None


class CrossEncoderReranker:
    """Reranks candidate retrieved chunks using a cross-encoder model for high precision."""

    def __init__(self, model_name: Optional[str] = None):
        self.model_name = model_name or settings.cross_encoder_model_name
        logger.info(f"Loading CrossEncoder model '{self.model_name}' on CPU...")
        self.model = CrossEncoder(self.model_name, device="cpu")
        logger.info(f"CrossEncoder model '{self.model_name}' initialized.")

    def rerank(self, query: str, candidate_chunks: list, top_k: int = 5) -> list:
        """Score (query, chunk_content) pairs and return top_k candidates sorted by cross-encoder score."""
        if not candidate_chunks:
            return []

        pairs = [[query, chunk.content] for chunk in candidate_chunks]
        try:
            raw_scores = self.model.predict(pairs, batch_size=16, show_progress_bar=False)

            for chunk, raw_score in zip(candidate_chunks, raw_scores):
                # Sigmoid normalization to 0.0 - 1.0 probability range
                val = float(raw_score)
                sigmoid_val = 1.0 / (1.0 + math.exp(-val)) if -700 < val < 700 else (0.0 if val <= -700 else 1.0)
                chunk.rerank_score = float(sigmoid_val)

            # Sort descending by rerank score
            sorted_chunks = sorted(candidate_chunks, key=lambda c: c.rerank_score, reverse=True)
            return sorted_chunks[:top_k]

        except Exception as e:
            logger.error(f"Error during cross-encoder reranking: {e}", exc_info=True)
            # Fall back to original hybrid ordering if reranking fails
            return candidate_chunks[:top_k]


def get_reranker() -> CrossEncoderReranker:
    """Return the singleton instance of CrossEncoderReranker."""
    global _reranker_instance
    if _reranker_instance is None:
        _reranker_instance = CrossEncoderReranker()
    return _reranker_instance
