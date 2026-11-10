import json
import uuid
import asyncio
import logging
from typing import Optional, Any, Dict
from contextlib import asynccontextmanager

import redis.asyncio as aioredis
import redis
from app.core.config import settings

logger = logging.getLogger("nexus.db.redis")

# Global clients & connection pools
_async_redis_client: Optional[aioredis.Redis] = None
_sync_redis_client: Optional[redis.Redis] = None

# Lua Script: Release lock only if owner token matches
LUA_RELEASE_LOCK = """
if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("del", KEYS[1])
else
    return 0
end
"""

# Lua Script: Renew/Heartbeat lock only if owner token matches
LUA_RENEW_LOCK = """
if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("expire", KEYS[1], ARGV[2])
else
    return 0
end
"""


async def get_redis_client() -> aioredis.Redis:
    """Return a singleton asynchronous Redis client connected via connection pool."""
    global _async_redis_client
    if _async_redis_client is None:
        logger.info(f"Initializing Async Redis Client to {settings.redis_url}...")
        _async_redis_client = aioredis.from_url(
            settings.redis_url,
            decode_responses=True,
            socket_timeout=5.0,
            socket_connect_timeout=5.0
        )
    return _async_redis_client


def get_sync_redis_client() -> redis.Redis:
    """Return a singleton synchronous Redis client."""
    global _sync_redis_client
    if _sync_redis_client is None:
        _sync_redis_client = redis.from_url(
            settings.redis_url,
            decode_responses=True,
            socket_timeout=5.0,
            socket_connect_timeout=5.0
        )
    return _sync_redis_client


async def close_redis_client() -> None:
    """Closes active Redis client connections."""
    global _async_redis_client, _sync_redis_client
    if _async_redis_client is not None:
        try:
            await _async_redis_client.close()
        except Exception as e:
            logger.warning(f"Error closing async Redis client: {e}")
        finally:
            _async_redis_client = None

    if _sync_redis_client is not None:
        try:
            _sync_redis_client.close()
        except Exception as e:
            logger.warning(f"Error closing sync Redis client: {e}")
        finally:
            _sync_redis_client = None


class DistributedTaskLock:
    """
    Distributed lock implementation using Redis SET NX EX (Redlock pattern).
    Includes auto-renewal heartbeat and atomic release via Lua script.
    """

    def __init__(self, client: aioredis.Redis, task_id: str, timeout_seconds: int = 120):
        self.client = client
        self.task_id = task_id
        self.lock_key = f"lock:task:{task_id}"
        self.token = f"worker-{uuid.uuid4().hex}"
        self.timeout_seconds = timeout_seconds
        self.acquired = False
        self._heartbeat_task: Optional[asyncio.Task] = None

    async def acquire(self) -> bool:
        """Attempts to acquire the distributed lock exclusively."""
        try:
            result = await self.client.set(
                self.lock_key,
                self.token,
                nx=True,
                ex=self.timeout_seconds
            )
            self.acquired = bool(result)
            if self.acquired:
                logger.info(f"[DistributedLock] Acquired lock for task='{self.task_id}' (ttl={self.timeout_seconds}s)")
                self._start_heartbeat()
            else:
                logger.warning(f"[DistributedLock] Lock contention: task='{self.task_id}' is held by another worker")
            return self.acquired
        except Exception as e:
            logger.error(f"[DistributedLock] Error acquiring lock for task '{self.task_id}': {e}")
            self.acquired = False
            return False

    async def release(self) -> bool:
        """Releases the lock safely using Lua script verification."""
        self._stop_heartbeat()
        if not self.acquired:
            return False

        try:
            res = await self.client.eval(LUA_RELEASE_LOCK, 1, self.lock_key, self.token)
            released = (res == 1)
            logger.info(f"[DistributedLock] Released lock for task='{self.task_id}': success={released}")
            self.acquired = False
            return released
        except Exception as e:
            logger.error(f"[DistributedLock] Error releasing lock for task '{self.task_id}': {e}")
            self.acquired = False
            return False

    def _start_heartbeat(self):
        """Starts a background asyncio task to periodically extend the lock TTL."""
        renewal_interval = max(1.0, float(self.timeout_seconds) / 3.0)

        async def _heartbeat_loop():
            while self.acquired:
                await asyncio.sleep(renewal_interval)
                if not self.acquired:
                    break
                try:
                    res = await self.client.eval(
                        LUA_RENEW_LOCK,
                        1,
                        self.lock_key,
                        self.token,
                        self.timeout_seconds
                    )
                    if res == 1:
                        logger.debug(f"[DistributedLock] Renewed lock for task='{self.task_id}'")
                    else:
                        logger.warning(f"[DistributedLock] Failed to renew lock for task='{self.task_id}', lock lost")
                        self.acquired = False
                        break
                except asyncio.CancelledError:
                    break
                except Exception as ex:
                    logger.warning(f"[DistributedLock] Exception renewing lock for '{self.task_id}': {ex}")

        self._heartbeat_task = asyncio.create_task(_heartbeat_loop())

    def _stop_heartbeat(self):
        """Cancels the auto-renewal background task."""
        if self._heartbeat_task and not self._heartbeat_task.done():
            self._heartbeat_task.cancel()
            self._heartbeat_task = None


@asynccontextmanager
async def distributed_task_lock(task_id: str, timeout_seconds: int = 120):
    """
    Async context manager acquiring a distributed lock on task_id.
    Yields the DistributedTaskLock instance.
    """
    client = await get_redis_client()
    lock = DistributedTaskLock(client, task_id, timeout_seconds=timeout_seconds)
    await lock.acquire()
    try:
        yield lock
    finally:
        if lock.acquired:
            await lock.release()


async def save_task_state(task_id: str, state_data: Dict[str, Any], ttl_seconds: int = 86400) -> bool:
    """Stores task execution metadata in Redis with a 24-hour TTL."""
    try:
        client = await get_redis_client()
        key = f"task:state:{task_id}"
        serialized = json.dumps(state_data, default=str)
        await client.set(key, serialized, ex=ttl_seconds)
        logger.debug(f"[Redis TaskState] Saved state for task='{task_id}' (ttl={ttl_seconds}s)")
        return True
    except Exception as e:
        logger.error(f"[Redis TaskState] Error saving state for task '{task_id}': {e}")
        return False


async def get_task_state(task_id: str) -> Optional[Dict[str, Any]]:
    """Retrieves task execution metadata from Redis."""
    try:
        client = await get_redis_client()
        key = f"task:state:{task_id}"
        raw = await client.get(key)
        if raw:
            return json.loads(raw)
        return None
    except Exception as e:
        logger.error(f"[Redis TaskState] Error retrieving state for task '{task_id}': {e}")
        return None

