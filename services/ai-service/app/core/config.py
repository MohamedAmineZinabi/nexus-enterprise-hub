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

    # Model Context Protocol (MCP) Configuration
    mcp_config_path: str = Field(
        default="app/mcp/configs/mcp_servers.json",
        validation_alias="MCP_CONFIG_PATH"
    )
    mcp_client_timeout_seconds: float = Field(
        default=10.0,
        validation_alias="MCP_CLIENT_TIMEOUT_SECONDS"
    )

    # Multi-Agent Orchestration & Retries
    max_agent_iterations: int = Field(
        default=2,
        validation_alias="MAX_AGENT_ITERATIONS"
    )

    # Internal service-to-service authentication (REQUIRED, no default).
    # Every gRPC call and every /api/v1/* or /mcp/* HTTP request must present this key.
    internal_api_key: str = Field(..., min_length=16, validation_alias="INTERNAL_API_KEY")

    # MCP SSE transport DNS-rebinding protection (comma-separated host[:port] patterns)
    mcp_allowed_hosts: str = Field(
        default="ai-service:*,nexus-ai-service:*,localhost:*,127.0.0.1:*",
        validation_alias="MCP_ALLOWED_HOSTS"
    )

    # Vector collections callers may target (comma-separated allowlist)
    allowed_collections: str = Field(default="enterprise_docs", validation_alias="ALLOWED_COLLECTIONS")

    # Optional OpenAI-compatible LLM (OpenAI, Groq, Ollama, vLLM...). Unset key => deterministic mode.
    llm_api_key: Optional[str] = Field(default=None, validation_alias="LLM_API_KEY")
    llm_base_url: Optional[str] = Field(default=None, validation_alias="LLM_BASE_URL")
    llm_model: str = Field(default="gpt-4o-mini", validation_alias="LLM_MODEL")
    llm_temperature: float = Field(default=0.0, validation_alias="LLM_TEMPERATURE")
    llm_timeout_seconds: float = Field(default=60.0, validation_alias="LLM_TIMEOUT_SECONDS")

    # Kafka Messaging Configuration
    kafka_bootstrap_servers: str = Field(
        default="nexus-kafka:29092",
        validation_alias="KAFKA_BOOTSTRAP_SERVERS"
    )
    kafka_group_id: str = Field(
        default="nexus-ai-agents",
        validation_alias="KAFKA_GROUP_ID"
    )
    kafka_topic_task_requested: str = Field(
        default="agent.task.requested",
        validation_alias="KAFKA_TOPIC_TASK_REQUESTED"
    )
    kafka_topic_task_events: str = Field(
        default="agent.task.events",
        validation_alias="KAFKA_TOPIC_TASK_EVENTS"
    )
    kafka_topic_task_completed: str = Field(
        default="agent.task.completed",
        validation_alias="KAFKA_TOPIC_TASK_COMPLETED"
    )
    kafka_topic_task_dlq: str = Field(
        default="agent.task.dlq",
        validation_alias="KAFKA_TOPIC_TASK_DLQ"
    )

    # Asynchronous document ingestion (core-service -> ai-service -> core-service)
    kafka_topic_document_ingest_requested: str = Field(
        default="document.ingest.requested",
        validation_alias="KAFKA_TOPIC_DOCUMENT_INGEST_REQUESTED"
    )
    kafka_topic_document_ingest_completed: str = Field(
        default="document.ingest.completed",
        validation_alias="KAFKA_TOPIC_DOCUMENT_INGEST_COMPLETED"
    )
    kafka_ingest_group_id: str = Field(default="nexus-ai-ingest", validation_alias="KAFKA_INGEST_GROUP_ID")
    # Large documents can take minutes to embed on CPU; keep the consumer in the group meanwhile
    kafka_ingest_max_poll_interval_ms: int = Field(
        default=1_800_000, validation_alias="KAFKA_INGEST_MAX_POLL_INTERVAL_MS"
    )
    ingest_lock_ttl_seconds: int = Field(default=300, validation_alias="INGEST_LOCK_TTL_SECONDS")

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore"
    )

    @property
    def llm_enabled(self) -> bool:
        return bool(self.llm_api_key and self.llm_api_key.strip())

    @property
    def allowed_collection_set(self) -> set:
        return {c.strip() for c in self.allowed_collections.split(",") if c.strip()}

    @property
    def mcp_allowed_host_list(self) -> list:
        return [h.strip() for h in self.mcp_allowed_hosts.split(",") if h.strip()]

    def resolve_collection(self, requested: Optional[str]) -> str:
        """Returns the requested collection if allowlisted, the default if blank; raises otherwise."""
        name = (requested or "").strip() or self.qdrant_collection_name
        if name not in self.allowed_collection_set and name != self.qdrant_collection_name:
            raise ValueError(f"Vector collection '{name}' is not allowed")
        return name


settings = Settings()
