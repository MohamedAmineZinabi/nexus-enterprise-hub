import json
import time
import logging
from typing import Optional, Dict, Any, List
from datetime import datetime, timezone

from aiokafka import AIOKafkaProducer
from app.core.config import settings

logger = logging.getLogger("nexus.kafka.producer")


class AgentEventPublisher:
    """Enterprise Kafka Event Publisher for task progress, completion, and DLQ routing."""

    def __init__(self, bootstrap_servers: Optional[str] = None):
        self.bootstrap_servers = bootstrap_servers or settings.kafka_bootstrap_servers
        self.producer: Optional[AIOKafkaProducer] = None
        self._started = False

    async def start(self):
        """Initializes and connects the async Kafka producer."""
        if self._started:
            return

        logger.info(f"[Kafka Producer] Connecting to bootstrap servers: {self.bootstrap_servers}")
        self.producer = AIOKafkaProducer(
            bootstrap_servers=self.bootstrap_servers,
            value_serializer=lambda v: json.dumps(v, default=str).encode("utf-8"),
            key_serializer=lambda k: str(k).encode("utf-8") if k else b"",
            acks="all",
            enable_idempotence=True
        )
        await self.producer.start()
        self._started = True
        logger.info("[Kafka Producer] Successfully connected and ready to publish events.")

    async def stop(self):
        """Gracefully flushes and closes the Kafka producer."""
        if self._started and self.producer:
            logger.info("[Kafka Producer] Flushing pending messages and stopping...")
            await self.producer.stop()
            self._started = False
            self.producer = None
            logger.info("[Kafka Producer] Stopped.")

    async def publish_event(
        self,
        task_id: str,
        tenant_id: str,
        node_type: str,
        phase: str,
        message: str,
        payload: Optional[Dict[str, Any]] = None,
        trace_id: str = ""
    ):
        """Publishes an intermediate node transition event to `agent.task.events`."""
        if not self._started or not self.producer:
            await self.start()

        event = {
            "taskId": task_id,
            "tenantId": tenant_id,
            "traceId": trace_id,
            "nodeType": node_type.upper(),
            "phase": phase.upper(),
            "message": message,
            "payload": payload or {},
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "timestampMs": int(time.time() * 1000)
        }

        topic = settings.kafka_topic_task_events
        key = tenant_id or task_id
        await self.producer.send_and_wait(topic, value=event, key=key)
        logger.debug(f"[Kafka Producer] Published event for task='{task_id}', node='{node_type}', phase='{phase}'")

    async def publish_completed(
        self,
        task_id: str,
        tenant_id: str,
        draft_report: str,
        execution_status: str = "COMPLETED",
        citations: Optional[List[Any]] = None,
        trace_id: str = ""
    ):
        """Publishes the finalized report to `agent.task.completed`."""
        if not self._started or not self.producer:
            await self.start()

        event = {
            "taskId": task_id,
            "tenantId": tenant_id,
            "traceId": trace_id,
            "status": execution_status,
            "report": draft_report,
            "citations": citations or [],
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "timestampMs": int(time.time() * 1000)
        }

        topic = settings.kafka_topic_task_completed
        key = task_id
        await self.producer.send_and_wait(topic, value=event, key=key)
        logger.info(f"[Kafka Producer] Published completion event for task='{task_id}' to topic '{topic}'")

    async def publish_dlq(
        self,
        task_id: str,
        tenant_id: str,
        error: str,
        stack_trace: str = "",
        raw_message: str = ""
    ):
        """Routes unrecoverable task failures to `agent.task.dlq`."""
        if not self._started or not self.producer:
            await self.start()

        event = {
            "taskId": task_id,
            "tenantId": tenant_id,
            "error": error,
            "stackTrace": stack_trace,
            "rawMessage": raw_message,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "timestampMs": int(time.time() * 1000)
        }

        topic = settings.kafka_topic_task_dlq
        key = task_id or "dlq-error"
        await self.producer.send_and_wait(topic, value=event, key=key)
        logger.error(f"[Kafka Producer] Routed failed task='{task_id}' to DLQ topic '{topic}': {error}")


# Global singleton instance
_event_publisher: Optional[AgentEventPublisher] = None


def get_event_publisher() -> AgentEventPublisher:
    """Returns the singleton AgentEventPublisher instance."""
    global _event_publisher
    if _event_publisher is None:
        _event_publisher = AgentEventPublisher()
    return _event_publisher

