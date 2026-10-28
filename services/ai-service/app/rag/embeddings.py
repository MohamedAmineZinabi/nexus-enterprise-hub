import logging
from typing import List, Optional
from sentence_transformers import SentenceTransformer
from app.core.config import settings

logger = logging.getLogger(__name__)

_embedding_service: Optional["EmbeddingService"] = None


class EmbeddingService:
    """Computes dense vector representations using SentenceTransformers with CPU optimization."""

    def __init__(self, model_name: Optional[str] = None):
        self.model_name = model_name or settings.embedding_model_name
        logger.info(f"Loading SentenceTransformer model '{self.model_name}' on CPU...")
        self.model = SentenceTransformer(self.model_name, device="cpu")
        self.dimension = self.model.get_sentence_embedding_dimension()
        logger.info(f"Loaded embedding model '{self.model_name}' with dimension={self.dimension}.")

    def embed_query(self, text: str) -> List[float]:
        """Compute normalized vector for a single query string."""
        clean_text = text.strip()
        vector = self.model.encode(clean_text, normalize_embeddings=True, show_progress_bar=False)
        return vector.tolist()

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        """Compute normalized vectors for a batch of document chunks."""
        if not texts:
            return []
        vectors = self.model.encode(
            texts,
            batch_size=32,
            normalize_embeddings=True,
            show_progress_bar=False
        )
        return vectors.tolist()


def get_embedding_service() -> EmbeddingService:
    """Return the singleton instance of EmbeddingService."""
    global _embedding_service
    if _embedding_service is None:
        _embedding_service = EmbeddingService()
    return _embedding_service
