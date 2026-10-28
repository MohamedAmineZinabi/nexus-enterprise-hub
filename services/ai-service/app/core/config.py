import os
from typing import Optional
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Production configuration settings for NexusEnterprise AI Service."""

    # Application Metadata
    app_name: str = "NexusEnterprise AI Service"
    app_version: str = "1.0.0"
    debug: bool = False

    # HTTP & gRPC Binding
    http_host: str = "0.0.0.0"
    http_port: int = 8000
    grpc_host: str = "0.0.0.0"
    grpc_port: int = Field(default=50051, validation_alias="GRPC_PORT")

    # Qdrant Vector Storage
    qdrant_url: str = Field(default="http://localhost:6333", validation_alias="QDRANT_URL")
    qdrant_api_key: Optional[str] = Field(default=None, validation_alias="QDRANT_API_KEY")
    qdrant_collection_name: str = Field(default="enterprise_docs", validation_alias="QDRANT_COLLECTION_NAME")

    # Redis Cache & Task Synchronization
    redis_url: str = Field(default="redis://localhost:6379", validation_alias="REDIS_URL")

    # Shared Storage Path (Mounted Volume)
    shared_uploads_dir: str = Field(default="/shared-uploads", validation_alias="SHARED_UPLOADS_DIR")

    # Embedding & Cross-Encoder Neural Models
    embedding_model_name: str = Field(default="BAAI/bge-small-en-v1.5", validation_alias="EMBEDDING_MODEL_NAME")
    embedding_dimension: int = 384
    cross_encoder_model_name: str = Field(
        default="cross-encoder/ms-marco-MiniLM-L-6-v2",
        validation_alias="CROSS_ENCODER_MODEL_NAME"
    )

    # Retrieval & Chunking Defaults
    default_top_k: int = 5
    default_alpha: float = 0.7
    default_chunk_size: int = 500
    default_chunk_overlap: int = 50
    rerank_candidates_top_n: int = 20

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore"
    )


settings = Settings()
