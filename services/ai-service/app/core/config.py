from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Application runtime configurations with environment variable overrides."""

    app_name: str = "NexusEnterprise AI Service"
    app_version: str = "1.0.0"
    debug: bool = False

    # Backing Services
    qdrant_url: str = Field(default="http://localhost:6333", validation_alias="QDRANT_URL")
    collection_name: str = Field(default="enterprise_docs", validation_alias="COLLECTION_NAME")
    redis_url: str = Field(default="redis://localhost:6379/0", validation_alias="REDIS_URL")
    shared_uploads_dir: str = Field(default="./data/shared-uploads", validation_alias="SHARED_UPLOADS_DIR")

    # Networking & Ports
    http_port: int = Field(default=8000, validation_alias="HTTP_PORT")
    grpc_port: int = Field(default=50051, validation_alias="GRPC_PORT")

    # Neural & Machine Learning Models
    embedding_model_name: str = Field(
        default="BAAI/bge-small-en-v1.5",
        validation_alias="EMBEDDING_MODEL_NAME"
    )
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

    # Model Context Protocol (MCP) Configuration
    mcp_config_path: str = Field(
        default="app/mcp/configs/mcp_servers.json",
        validation_alias="MCP_CONFIG_PATH"
    )
    mcp_client_timeout_seconds: float = Field(
        default=10.0,
        validation_alias="MCP_CLIENT_TIMEOUT_SECONDS"
    )

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore"
    )


settings = Settings()
