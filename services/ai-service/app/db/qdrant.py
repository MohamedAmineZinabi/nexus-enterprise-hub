import logging
from typing import Optional
from qdrant_client import QdrantClient, AsyncQdrantClient
from qdrant_client.http import models
from app.core.config import settings

logger = logging.getLogger(__name__)

_sync_client: Optional[QdrantClient] = None
_async_client: Optional[AsyncQdrantClient] = None


def get_qdrant_client() -> QdrantClient:
    """Return a singleton synchronous Qdrant client."""
    global _sync_client
    if _sync_client is None:
        logger.info(f"Connecting to Qdrant at {settings.qdrant_url}...")
        _sync_client = QdrantClient(
            url=settings.qdrant_url,
            api_key=settings.qdrant_api_key,
            timeout=10.0
        )
    return _sync_client


def get_async_qdrant_client() -> AsyncQdrantClient:
    """Return a singleton asynchronous Qdrant client."""
    global _async_client
    if _async_client is None:
        _async_client = AsyncQdrantClient(
            url=settings.qdrant_url,
            api_key=settings.qdrant_api_key,
            timeout=10.0
        )
    return _async_client


def init_qdrant_collection(collection_name: Optional[str] = None) -> None:
    """Idempotently initialize Qdrant collection with cosine distance and multi-tenant indexes."""
    col_name = collection_name or settings.qdrant_collection_name
    client = get_qdrant_client()

    try:
        exists = client.collection_exists(collection_name=col_name)
        if not exists:
            logger.info(
                f"Collection '{col_name}' does not exist. Creating with size={settings.embedding_dimension}, "
                f"distance=COSINE..."
            )
            client.create_collection(
                collection_name=col_name,
                vectors_config=models.VectorParams(
                    size=settings.embedding_dimension,
                    distance=models.Distance.COSINE
                )
            )
            logger.info(f"Collection '{col_name}' created successfully.")
        else:
            logger.info(f"Collection '{col_name}' already exists.")

        # Create multi-tenant keyword indexes for high-speed filtered vector queries
        for field in ["tenant_id", "document_id"]:
            try:
                client.create_payload_index(
                    collection_name=col_name,
                    field_name=field,
                    field_schema=models.PayloadSchemaType.KEYWORD
                )
                logger.info(f"Created payload index on '{field}' in collection '{col_name}'.")
            except Exception as e:
                # Index may already exist or be created concurrently
                logger.debug(f"Payload index for '{field}' already exists or failed: {e}")

    except Exception as e:
        logger.error(f"Failed to initialize Qdrant collection '{col_name}': {e}", exc_info=True)
        raise
