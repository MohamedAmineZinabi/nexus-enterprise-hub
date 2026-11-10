import json
import logging
from typing import Optional, Any, Iterator, AsyncIterator, Sequence, Dict
from langgraph.checkpoint.base import (
    BaseCheckpointSaver,
    Checkpoint,
    CheckpointMetadata,
    CheckpointTuple,
    ChannelVersions,
)
from langchain_core.runnables import RunnableConfig

from app.db.redis import get_redis_client, get_sync_redis_client

logger = logging.getLogger("nexus.agents.checkpointer")


class RedisSaver(BaseCheckpointSaver):
    """
    Redis-backed LangGraph state checkpointer.
    Persists state transitions and metadata under `agent:checkpoint:{taskId}` with 24h TTL.
    """

    def __init__(self, ttl_seconds: int = 86400, **kwargs):
        super().__init__(**kwargs)
        self.ttl_seconds = ttl_seconds

    def _format_key(self, thread_id: str) -> str:
        return f"agent:checkpoint:{thread_id}"

    def _format_history_key(self, thread_id: str) -> str:
        return f"agent:checkpoint:{thread_id}:history"

    def _serialize(self, checkpoint: Checkpoint, metadata: CheckpointMetadata, thread_id: str) -> str:
        data = {
            "checkpoint_id": checkpoint.get("id"),
            "task_id": thread_id,
            "ts": checkpoint.get("ts"),
            "step": metadata.get("step") if metadata else None,
            "source": metadata.get("source") if metadata else None,
            "node": metadata.get("node") or (metadata.get("source") if metadata else None),
            "state": checkpoint.get("channel_values"),
            "checkpoint": checkpoint,
            "metadata": metadata
        }
        return json.dumps(data, default=str)

    def _deserialize(self, raw_str: str, config: RunnableConfig) -> Optional[CheckpointTuple]:
        try:
            data = json.loads(raw_str)
            checkpoint = data.get("checkpoint")
            metadata = data.get("metadata", {})
            if checkpoint:
                return CheckpointTuple(
                    config=config,
                    checkpoint=checkpoint,
                    metadata=metadata,
                    parent_config=None,
                    pending_writes=[]
                )
        except Exception as e:
            logger.warning(f"[RedisSaver] Failed to deserialize checkpoint: {e}")
        return None

    def get_tuple(self, config: RunnableConfig) -> Optional[CheckpointTuple]:
        thread_id = config.get("configurable", {}).get("thread_id")
        if not thread_id:
            return None
        try:
            client = get_sync_redis_client()
            key = self._format_key(thread_id)
            raw = client.get(key)
            if raw:
                return self._deserialize(raw, config)
        except Exception as e:
            logger.error(f"[RedisSaver] Error in get_tuple for thread '{thread_id}': {e}")
        return None

    async def aget_tuple(self, config: RunnableConfig) -> Optional[CheckpointTuple]:
        thread_id = config.get("configurable", {}).get("thread_id")
        if not thread_id:
            return None
        try:
            client = await get_redis_client()
            key = self._format_key(thread_id)
            raw = await client.get(key)
            if raw:
                return self._deserialize(raw, config)
        except Exception as e:
            logger.error(f"[RedisSaver] Error in aget_tuple for thread '{thread_id}': {e}")
        return None

    def put(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions
    ) -> RunnableConfig:
        thread_id = config.get("configurable", {}).get("thread_id")
        if not thread_id:
            return config

        key = self._format_key(thread_id)
        history_key = self._format_history_key(thread_id)
        serialized = self._serialize(checkpoint, metadata, thread_id)

        try:
            client = get_sync_redis_client()
            client.set(key, serialized, ex=self.ttl_seconds)
            client.rpush(history_key, serialized)
            client.expire(history_key, self.ttl_seconds)
            logger.debug(f"[RedisSaver] Saved sync checkpoint for task='{thread_id}', id='{checkpoint.get('id')}'")
        except Exception as e:
            logger.error(f"[RedisSaver] Error saving sync checkpoint for task '{thread_id}': {e}")

        return {
            "configurable": {
                "thread_id": thread_id,
                "checkpoint_ns": config.get("configurable", {}).get("checkpoint_ns", ""),
                "checkpoint_id": checkpoint["id"]
            }
        }

    async def aput(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions
    ) -> RunnableConfig:
        thread_id = config.get("configurable", {}).get("thread_id")
        if not thread_id:
            return config

        key = self._format_key(thread_id)
        history_key = self._format_history_key(thread_id)
        serialized = self._serialize(checkpoint, metadata, thread_id)

        try:
            client = await get_redis_client()
            await client.set(key, serialized, ex=self.ttl_seconds)
            await client.rpush(history_key, serialized)
            await client.expire(history_key, self.ttl_seconds)
            logger.debug(f"[RedisSaver] Saved async checkpoint for task='{thread_id}', id='{checkpoint.get('id')}'")
        except Exception as e:
            logger.error(f"[RedisSaver] Error saving async checkpoint for task '{thread_id}': {e}")

        return {
            "configurable": {
                "thread_id": thread_id,
                "checkpoint_ns": config.get("configurable", {}).get("checkpoint_ns", ""),
                "checkpoint_id": checkpoint["id"]
            }
        }

    def put_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[Any],
        task_id: str,
        task_path: str = ""
    ) -> None:
        pass

    async def aput_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[Any],
        task_id: str,
        task_path: str = ""
    ) -> None:
        pass

    def list(
        self,
        config: Optional[RunnableConfig] = None,
        *,
        filter: Optional[Dict[str, Any]] = None,
        before: Optional[RunnableConfig] = None,
        limit: Optional[int] = None
    ) -> Iterator[CheckpointTuple]:
        if not config:
            return iter([])
        thread_id = config.get("configurable", {}).get("thread_id")
        if not thread_id:
            return iter([])

        try:
            client = get_sync_redis_client()
            history_key = self._format_history_key(thread_id)
            items = client.lrange(history_key, 0, (limit - 1) if limit else -1)
            results = []
            for item in items:
                cp = self._deserialize(item, config)
                if cp:
                    results.append(cp)
            return iter(results)
        except Exception as e:
            logger.error(f"[RedisSaver] Error listing checkpoints for '{thread_id}': {e}")
            return iter([])

    async def alist(
        self,
        config: Optional[RunnableConfig] = None,
        *,
        filter: Optional[Dict[str, Any]] = None,
        before: Optional[RunnableConfig] = None,
        limit: Optional[int] = None
    ) -> AsyncIterator[CheckpointTuple]:
        if not config:
            return
        thread_id = config.get("configurable", {}).get("thread_id")
        if not thread_id:
            return

        try:
            client = await get_redis_client()
            history_key = self._format_history_key(thread_id)
            items = await client.lrange(history_key, 0, (limit - 1) if limit else -1)
            for item in items:
                cp = self._deserialize(item, config)
                if cp:
                    yield cp
        except Exception as e:
            logger.error(f"[RedisSaver] Error in alist for '{thread_id}': {e}")

