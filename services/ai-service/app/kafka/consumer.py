import json
import re
import time
import uuid
import asyncio
import logging
import traceback
from typing import Optional, Dict, Any

from aiokafka import AIOKafkaConsumer
from aiokafka.structs import TopicPartition, OffsetAndMetadata
from app.core.config import settings
from app.agents.graph import get_agent_graph
from app.agents.state import build_initial_state
from app.kafka.producer import get_event_publisher
from app.db.redis import distributed_task_lock, save_task_state
from app.security.auth import require_tenant_id

logger = logging.getLogger("nexus.kafka.consumer")

UUID_FALLBACK_PATTERN = re.compile(r'"task(?:Id|_id)"\s*:\s*"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"')


def _is_valid_uuid(value: str) -> bool:
    """Checks whether a string represents a valid RFC 4122 UUID."""
    if not value:
        return False
    try:
        uuid.UUID(str(value))
        return True
    except (ValueError, TypeError, AttributeError):
        return False


class KafkaTaskConsumer:
    """
    Asynchronous Kafka Consumer subscribing to `agent.task.requested`.
    Orchestrates the LangGraph multi-agent loop, routes poison-pill / unrecoverable
    failures to `agent.task.dlq`, emits FAILED outcomes to `agent.task.completed`,
    and commits offsets explicitly so partitions never stall.
    """

    def __init__(self, bootstrap_servers: Optional[str] = None):
        self.bootstrap_servers = bootstrap_servers or settings.kafka_bootstrap_servers
        self.topic = settings.kafka_topic_task_requested
        self.group_id = settings.kafka_group_id
        self.consumer: Optional[AIOKafkaConsumer] = None
        self._running = False
        self._task: Optional[asyncio.Task] = None

    async def start(self):
        """Starts the consumer loop in a background asyncio task with connection resilience."""
        if self._running:
            return

        self._running = True
        self._task = asyncio.create_task(self._run_consumer_loop())
        logger.info(f"[Kafka Consumer] Background task scheduled for topic '{self.topic}'.")

    async def stop(self):
        """Stops the consumer loop and closes the underlying Kafka connection."""
        self._running = False
        if self.consumer:
            try:
                await self.consumer.stop()
            except Exception as e:
                logger.warning(f"[Kafka Consumer] Error stopping consumer: {e}")
            finally:
                self.consumer = None

        if self._task and not self._task.done():
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        logger.info("[Kafka Consumer] Stopped.")

    async def _run_consumer_loop(self):
        """Continuous consumption loop with explicit offset commits and retry backoff."""
        publisher = get_event_publisher()
        graph = get_agent_graph()

        while self._running:
            try:
                logger.info(
                    f"[Kafka Consumer] Connecting to {self.bootstrap_servers}, "
                    f"subscribing to '{self.topic}' (group='{self.group_id}')..."
                )
                self.consumer = AIOKafkaConsumer(
                    self.topic,
                    bootstrap_servers=self.bootstrap_servers,
                    group_id=self.group_id,
                    auto_offset_reset="earliest",
                    enable_auto_commit=False
                )
                await self.consumer.start()
                logger.info("[Kafka Consumer] Successfully connected and listening for task requests.")

                async for msg in self.consumer:
                    if not self._running:
                        break
                    try:
                        raw_str = (
                            msg.value.decode("utf-8", errors="replace")
                            if isinstance(msg.value, (bytes, bytearray))
                            else (str(msg.value) if msg.value is not None else "")
                        )
                        msg_meta = {
                            "topic": msg.topic,
                            "partition": msg.partition,
                            "offset": msg.offset,
                            "headers": {
                                str(k): (
                                    v.decode("utf-8", errors="replace")
                                    if isinstance(v, (bytes, bytearray))
                                    else str(v)
                                )
                                for k, v in (msg.headers or [])
                            },
                        }
                        await self._process_task_message(raw_str, graph, publisher, msg_meta=msg_meta)
                    except Exception as loop_exc:
                        logger.error(
                            f"[Kafka Consumer] Unhandled consumer loop exception at "
                            f"partition={msg.partition}, offset={msg.offset}: {loop_exc}"
                        )
                    finally:
                        await self._commit_record_offset(msg)

            except asyncio.CancelledError:
                break
            except Exception as exc:
                logger.warning(f"[Kafka Consumer] Connection error or loop interrupted: {exc}. Retrying in 5 seconds...")
                if self.consumer:
                    try:
                        await self.consumer.stop()
                    except Exception:
                        pass
                    self.consumer = None
                await asyncio.sleep(5)

    async def _commit_record_offset(self, msg):
        """Commits the offset for a processed or DLQ-routed record cleanly."""
        if self.consumer is None:
            return
        try:
            tp = TopicPartition(msg.topic, msg.partition)
            await self.consumer.commit({tp: OffsetAndMetadata(msg.offset + 1, "")})
        except Exception as commit_err:
            logger.warning(
                f"[Kafka Consumer] Failed to commit offset {msg.offset + 1} on "
                f"{msg.topic}-{msg.partition}: {commit_err}"
            )

    async def _process_task_message(
        self,
        raw_str: str,
        graph,
        publisher,
        msg_meta: Optional[Dict[str, Any]] = None
    ):
        """Processes a single task request, routing poison-pills and graph failures to DLQ."""
        task_id = "unknown"
        tenant_id = ""
        trace_id = "trace-unknown"
        started = time.monotonic()

        def _elapsed_ms() -> int:
            return int((time.monotonic() - started) * 1000)

        try:
            data = json.loads(raw_str)
            if not isinstance(data, dict):
                raise ValueError(f"Malformed message payload: expected JSON object, got {type(data).__name__}")

            task_id = str(data.get("taskId") or data.get("task_id") or "").strip()
            tenant_id = str(data.get("tenantId") or data.get("tenant_id") or "").strip()
            prompt = data.get("prompt") or data.get("user_prompt") or ""
            trace_id = str(data.get("traceId") or data.get("trace_id") or f"trace-{task_id or 'unknown'}")
            doc_ids = data.get("documentIds") or data.get("document_ids") or []
            meta = data.get("metadata") or {}

            if not task_id:
                task_id = "unknown"
                raise ValueError("Poison-pill message: missing required 'taskId' field")

            if not isinstance(prompt, str) or not prompt.strip():
                raise ValueError(f"Invalid task request '{task_id}': missing or blank 'prompt'")

            # Poison-pill: a task without a valid tenant must never run (no 'default' tenant fallback)
            tenant_id = require_tenant_id(tenant_id)

            logger.info(f"[Kafka Consumer] Received task: id='{task_id}', tenant='{tenant_id}', prompt_chars={len(prompt)}")

            # Acquire distributed lock before executing task to ensure idempotency
            async with distributed_task_lock(task_id, timeout_seconds=120) as lock:
                if isinstance(getattr(lock, "error", None), Exception):
                    # Lock store outage is not contention: fail the task visibly instead of dropping it
                    raise RuntimeError(f"Distributed lock store unavailable: {lock.error}")
                if not lock.acquired:
                    logger.warning(
                        f"[Kafka Consumer] Task '{task_id}' is already locked or running on another worker. "
                        f"Skipping duplicate execution to enforce strict idempotency."
                    )
                    return

                # Measure processing time from lock acquisition (excludes time spent waiting in the topic)
                started = time.monotonic()

                # Persist active task state in Redis with 24h TTL
                await save_task_state(task_id, {
                    "task_id": task_id,
                    "tenant_id": tenant_id,
                    "prompt": prompt,
                    "status": "PROCESSING",
                    "trace_id": trace_id
                }, ttl_seconds=86400)

                # 1. Publish initial started event
                await publisher.publish_event(
                    task_id=task_id,
                    tenant_id=tenant_id,
                    node_type="WORKFLOW",
                    phase="STARTED",
                    message="Task accepted by multi-agent workflow.",
                    trace_id=trace_id,
                    duration_ms=_elapsed_ms()
                )

                # 2. Build initial state and run graph
                initial_state = build_initial_state(
                    task_id=task_id,
                    tenant_id=tenant_id,
                    user_prompt=prompt,
                    trace_id=trace_id,
                    document_ids=doc_ids,
                    metadata=meta if isinstance(meta, dict) else {},
                )

                config = {"configurable": {"thread_id": task_id}}
                final_report = ""
                citations = []

                # 3. Stream transitions across Planner -> Researcher -> Synthesizer -> Critic
                async for step_output in graph.astream(initial_state, config=config):
                    for node_name, node_state in step_output.items():
                        node_status = node_state.get("status", "IN_PROGRESS")
                        msg_text = f"Node '{node_name}' finished: status={node_status}"

                        if node_name == "planner":
                            plan_steps = node_state.get("plan", [])
                            msg_text = f"Planner decomposed prompt into {len(plan_steps)} sub-tasks."
                        elif node_name == "researcher":
                            r_data = node_state.get("research_data", [])
                            msg_text = f"Researcher retrieved {len(r_data)} factual context items."
                            citations = [item.get("citation") for item in r_data if item.get("citation")]
                        elif node_name == "synthesizer":
                            final_report = node_state.get("draft_report", "")
                            msg_text = f"Synthesizer produced executive report ({len(final_report)} characters)."
                        elif node_name == "critic":
                            crit = node_state.get("critique", {})
                            msg_text = f"Critic evaluated draft: is_valid={crit.get('is_valid')}, score={crit.get('hallucination_score')}"

                        await publisher.publish_event(
                            task_id=task_id,
                            tenant_id=tenant_id,
                            node_type=node_name,
                            phase="COMPLETED",
                            message=msg_text,
                            payload={
                                "current_node": node_name,
                                "status": node_status,
                                "iteration": node_state.get("iteration_count", 0),
                                "errors": node_state.get("errors", [])[-3:],
                            },
                            trace_id=trace_id,
                            duration_ms=_elapsed_ms()
                        )

                # 4. Publish final completion event
                await publisher.publish_completed(
                    task_id=task_id,
                    tenant_id=tenant_id,
                    draft_report=final_report,
                    execution_status="COMPLETED",
                    citations=citations,
                    trace_id=trace_id,
                    total_duration_ms=_elapsed_ms()
                )

                # Persist completed task metadata in Redis with 24h TTL
                await save_task_state(task_id, {
                    "task_id": task_id,
                    "tenant_id": tenant_id,
                    "prompt": prompt,
                    "status": "COMPLETED",
                    "report": final_report,
                    "citations": citations,
                    "trace_id": trace_id
                }, ttl_seconds=86400)

                logger.info(f"[Kafka Consumer] Successfully completed and published task '{task_id}'")

        except Exception as exc:
            err_trace = traceback.format_exc()
            error_type = type(exc).__name__

            # Attempt regex UUID recovery in case of malformed truncated JSON containing taskId
            if task_id == "unknown" and raw_str:
                match = UUID_FALLBACK_PATTERN.search(raw_str)
                if match:
                    task_id = match.group(1)

            logger.error(
                f"[Kafka Consumer] Poison-pill or unrecoverable error processing task '{task_id}' "
                f"({error_type}): {exc}\n{err_trace}"
            )

            if task_id != "unknown":
                try:
                    await save_task_state(task_id, {
                        "task_id": task_id,
                        "tenant_id": tenant_id,
                        "status": "FAILED",
                        "errorType": error_type,
                        "error": str(exc),
                        "trace_id": trace_id
                    }, ttl_seconds=86400)
                except Exception as redis_err:
                    logger.warning(f"[Kafka Consumer] Failed to save FAILED state in Redis for '{task_id}': {redis_err}")

            # Route structured failure record to Dead Letter Queue (agent.task.dlq)
            try:
                await publisher.publish_dlq(
                    task_id=task_id,
                    tenant_id=tenant_id,
                    error=str(exc),
                    error_type=error_type,
                    trace_id=trace_id,
                    stack_trace=err_trace,
                    raw_message=raw_str,
                    source_topic=(msg_meta or {}).get("topic", self.topic),
                    partition=(msg_meta or {}).get("partition"),
                    offset=(msg_meta or {}).get("offset"),
                    headers=(msg_meta or {}).get("headers"),
                )
            except Exception as dlq_err:
                logger.error(f"[Kafka Consumer] Failed to route to DLQ: {dlq_err}")

            # Notify core-service PostgreSQL & SSE clients immediately when taskId is a valid UUID
            if _is_valid_uuid(task_id):
                try:
                    await publisher.publish_completed(
                        task_id=task_id,
                        tenant_id=tenant_id,
                        draft_report="",
                        execution_status="FAILED",
                        citations=[],
                        trace_id=trace_id,
                        error_details=f"[{error_type}] {exc}",
                        total_duration_ms=_elapsed_ms()
                    )
                except Exception as notify_err:
                    logger.error(
                        f"[Kafka Consumer] Failed to publish FAILED completion event for task '{task_id}': {notify_err}"
                    )


# Global singleton instance
_task_consumer: Optional[KafkaTaskConsumer] = None


def get_task_consumer() -> KafkaTaskConsumer:
    """Returns the singleton KafkaTaskConsumer instance."""
    global _task_consumer
    if _task_consumer is None:
        _task_consumer = KafkaTaskConsumer()
    return _task_consumer
