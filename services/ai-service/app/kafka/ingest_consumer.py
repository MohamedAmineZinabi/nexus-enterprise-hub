"""
Asynchronous document ingestion consumer.

core-service writes the upload to the shared volume, commits the Document row (status INDEXING)
and publishes `document.ingest.requested`. This consumer parses/embeds/upserts the document off the
event loop and reports the outcome on `document.ingest.completed` (INDEXED or FAILED). Unrecoverable
failures are additionally routed to the DLQ.
"""
import asyncio
import json
import logging
import time
import traceback
from typing import Any, Dict, Optional

from aiokafka import AIOKafkaConsumer
from aiokafka.structs import OffsetAndMetadata, TopicPartition

from app.core.config import settings
from app.db.redis import distributed_lock
from app.kafka.producer import get_event_publisher
from app.rag.ingestion import (
    DocumentDeletedDuringIngest,
    InvalidIngestRequest,
    SourceFileMissing,
    ingest_document,
)
from app.security.auth import TenantValidationError, require_tenant_id

logger = logging.getLogger("nexus.kafka.ingest_consumer")


def _optional_int(value: Any) -> Optional[int]:
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


class DocumentIngestConsumer:
    """Consumes `document.ingest.requested` with explicit offset commits and per-document Redlock."""

    def __init__(self, bootstrap_servers: Optional[str] = None):
        self.bootstrap_servers = bootstrap_servers or settings.kafka_bootstrap_servers
        self.topic = settings.kafka_topic_document_ingest_requested
        self.group_id = settings.kafka_ingest_group_id
        self.consumer: Optional[AIOKafkaConsumer] = None
        self._running = False
        self._task: Optional[asyncio.Task] = None

    async def start(self):
        if self._running:
            return
        self._running = True
        self._task = asyncio.create_task(self._run_loop())
        logger.info(f"[Ingest Consumer] Background task scheduled for topic '{self.topic}'.")

    async def stop(self):
        self._running = False
        if self.consumer:
            try:
                await self.consumer.stop()
            except Exception as e:
                logger.warning(f"[Ingest Consumer] Error stopping consumer: {e}")
            finally:
                self.consumer = None
        if self._task and not self._task.done():
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass

    async def _run_loop(self):
        publisher = get_event_publisher()
        while self._running:
            try:
                self.consumer = AIOKafkaConsumer(
                    self.topic,
                    bootstrap_servers=self.bootstrap_servers,
                    group_id=self.group_id,
                    auto_offset_reset="earliest",
                    enable_auto_commit=False,
                    max_poll_records=1,
                    max_poll_interval_ms=settings.kafka_ingest_max_poll_interval_ms,
                )
                await self.consumer.start()
                logger.info(f"[Ingest Consumer] Listening on '{self.topic}' (group='{self.group_id}').")

                async for msg in self.consumer:
                    if not self._running:
                        break
                    try:
                        raw = msg.value.decode("utf-8", errors="replace") if msg.value is not None else ""
                        await self.process_message(raw, publisher, msg_meta={
                            "topic": msg.topic, "partition": msg.partition, "offset": msg.offset,
                        })
                    except Exception as exc:  # never let one record kill the loop
                        logger.error(f"[Ingest Consumer] Unhandled error at offset {msg.offset}: {exc}", exc_info=True)
                    finally:
                        await self._commit(msg)
            except asyncio.CancelledError:
                break
            except Exception as exc:
                logger.warning(f"[Ingest Consumer] Connection error: {exc}. Retrying in 5 seconds...")
                if self.consumer:
                    try:
                        await self.consumer.stop()
                    except Exception:
                        pass
                    self.consumer = None
                await asyncio.sleep(5)

    async def _commit(self, msg):
        if self.consumer is None:
            return
        try:
            await self.consumer.commit({TopicPartition(msg.topic, msg.partition): OffsetAndMetadata(msg.offset + 1, "")})
        except Exception as exc:
            logger.warning(f"[Ingest Consumer] Failed to commit offset {msg.offset + 1}: {exc}")

    async def process_message(self, raw: str, publisher, msg_meta: Optional[Dict[str, Any]] = None) -> Optional[str]:
        """Processes one ingestion request. Returns the published status (or None when skipped)."""
        started = time.monotonic()
        document_id, tenant_id, trace_id, collection = "", "", "", ""

        def elapsed() -> int:
            return int((time.monotonic() - started) * 1000)

        try:
            data = json.loads(raw)
            if not isinstance(data, dict):
                raise InvalidIngestRequest("payload must be a JSON object")
            document_id = str(data.get("documentId") or "").strip()
            trace_id = str(data.get("traceId") or f"trace-ingest-{document_id}")
            collection = str(data.get("vectorCollection") or "")
            if not document_id:
                raise InvalidIngestRequest("documentId is required")
            tenant_id = require_tenant_id(data.get("tenantId"))
            file_path = str(data.get("filePath") or "")
            if not file_path:
                raise InvalidIngestRequest("filePath is required")

            metadata = data.get("metadata") if isinstance(data.get("metadata"), dict) else {}
            if data.get("fileName"):
                metadata.setdefault("filename", str(data["fileName"]))

            async with distributed_lock(f"lock:doc:{document_id}", timeout_seconds=settings.ingest_lock_ttl_seconds) as lock:
                if not lock.acquired:
                    logger.warning(f"[Ingest Consumer] Document '{document_id}' is already being indexed; skipping duplicate.")
                    return None

                logger.info(f"[Ingest Consumer] Indexing document '{document_id}' for tenant '{tenant_id}'")
                result = await asyncio.to_thread(
                    ingest_document,
                    tenant_id=tenant_id,
                    document_id=document_id,
                    file_path=file_path,
                    mime_type=str(data.get("mimeType") or ""),
                    chunk_size=_optional_int(data.get("chunkSize")),
                    chunk_overlap=_optional_int(data.get("chunkOverlap")),
                    collection=collection or None,
                    metadata=metadata,
                )

            await publisher.publish_ingest_completed(
                document_id=document_id,
                tenant_id=tenant_id,
                status="INDEXED",
                chunks_created=result.chunks_created,
                vector_collection=result.collection,
                trace_id=trace_id,
                duration_ms=elapsed(),
            )
            return "INDEXED"

        except (InvalidIngestRequest, TenantValidationError, SourceFileMissing, DocumentDeletedDuringIngest, json.JSONDecodeError) as exc:
            # Deterministic failures: report FAILED, never retry, DLQ for audit
            return await self._fail(publisher, raw, document_id, tenant_id, trace_id, collection, exc, elapsed(), msg_meta)
        except Exception as exc:
            return await self._fail(publisher, raw, document_id, tenant_id, trace_id, collection, exc, elapsed(), msg_meta)

    async def _fail(self, publisher, raw, document_id, tenant_id, trace_id, collection, exc, duration_ms, msg_meta):
        error_type = type(exc).__name__
        logger.error(f"[Ingest Consumer] Ingestion failed for document '{document_id or 'unknown'}' ({error_type}): {exc}")
        try:
            await publisher.publish_dlq(
                task_id=document_id or "unknown",
                tenant_id=tenant_id,
                error=str(exc),
                error_type=error_type,
                trace_id=trace_id,
                stack_trace=traceback.format_exc(),
                raw_message=raw,
                source_topic=(msg_meta or {}).get("topic", self.topic),
                partition=(msg_meta or {}).get("partition"),
                offset=(msg_meta or {}).get("offset"),
            )
        except Exception as dlq_err:
            logger.error(f"[Ingest Consumer] Failed to route to DLQ: {dlq_err}")

        if document_id and tenant_id:
            try:
                await publisher.publish_ingest_completed(
                    document_id=document_id,
                    tenant_id=tenant_id,
                    status="FAILED",
                    chunks_created=0,
                    vector_collection=collection,
                    trace_id=trace_id,
                    error_details=f"[{error_type}] {exc}",
                    duration_ms=duration_ms,
                )
            except Exception as pub_err:
                logger.error(f"[Ingest Consumer] Failed to publish FAILED outcome for '{document_id}': {pub_err}")
        return "FAILED"


_ingest_consumer: Optional[DocumentIngestConsumer] = None


def get_ingest_consumer() -> DocumentIngestConsumer:
    global _ingest_consumer
    if _ingest_consumer is None:
        _ingest_consumer = DocumentIngestConsumer()
    return _ingest_consumer
