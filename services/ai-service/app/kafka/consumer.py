import json
import asyncio
import logging
import traceback
from typing import Optional

from aiokafka import AIOKafkaConsumer
from app.core.config import settings
from app.agents.graph import get_agent_graph
from app.agents.state import AgentWorkflowState
from app.kafka.producer import get_event_publisher

logger = logging.getLogger("nexus.kafka.consumer")


class KafkaTaskConsumer:
    """
    Asynchronous Kafka Consumer subscribing to `agent.task.requested`.
    Orchestrates the LangGraph multi-agent loop and streams intermediate events.
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
        """Continuous consumption loop with retry backoff for network resilience."""
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
                    enable_auto_commit=True
                )
                await self.consumer.start()
                logger.info("[Kafka Consumer] Successfully connected and listening for task requests.")

                async for msg in self.consumer:
                    if not self._running:
                        break
                    raw_str = msg.value.decode("utf-8")
                    await self._process_task_message(raw_str, graph, publisher)

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

    async def _process_task_message(self, raw_str: str, graph, publisher):
        """Processes a single task request, streaming LangGraph transitions to Kafka."""
        task_id = "unknown"
        tenant_id = "default"
        trace_id = "trace-unknown"

        try:
            data = json.loads(raw_str)
            task_id = str(data.get("taskId") or data.get("task_id") or "")
            tenant_id = str(data.get("tenantId") or data.get("tenant_id") or "default")
            prompt = data.get("prompt") or data.get("user_prompt") or ""
            trace_id = data.get("traceId") or data.get("trace_id") or f"trace-{task_id}"
            doc_ids = data.get("documentIds") or data.get("document_ids") or []
            meta = data.get("metadata") or {}

            logger.info(f"[Kafka Consumer] Received task: id='{task_id}', tenant='{tenant_id}', prompt='{prompt}'")

            # 1. Publish initial started event
            await publisher.publish_event(
                task_id=task_id,
                tenant_id=tenant_id,
                node_type="WORKFLOW",
                phase="STARTED",
                message=f"Received task: '{prompt}'",
                trace_id=trace_id
            )

            # 2. Build initial state and run graph
            initial_state: AgentWorkflowState = {
                "task_id": task_id,
                "tenant_id": tenant_id,
                "user_prompt": prompt,
                "trace_id": trace_id,
                "document_ids": [str(d) for d in doc_ids],
                "metadata": meta,
                "plan": [],
                "research_data": [],
                "draft_report": "",
                "critique": {},
                "iteration_count": 0,
                "current_node": "START",
                "status": "PROCESSING"
            }

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
                            "iteration": node_state.get("iteration_count", 0)
                        },
                        trace_id=trace_id
                    )

            # 4. Publish final completion event
            await publisher.publish_completed(
                task_id=task_id,
                tenant_id=tenant_id,
                draft_report=final_report,
                execution_status="COMPLETED",
                citations=citations,
                trace_id=trace_id
            )
            logger.info(f"[Kafka Consumer] Successfully completed and published task '{task_id}'")

        except Exception as exc:
            err_trace = traceback.format_exc()
            logger.error(f"[Kafka Consumer] Unrecoverable error processing task '{task_id}': {exc}\n{err_trace}")
            try:
                await publisher.publish_dlq(
                    task_id=task_id,
                    tenant_id=tenant_id,
                    error=str(exc),
                    stack_trace=err_trace,
                    raw_message=raw_str
                )
            except Exception as dlq_err:
                logger.error(f"[Kafka Consumer] Failed to route to DLQ: {dlq_err}")


# Global singleton instance
_task_consumer: Optional[KafkaTaskConsumer] = None


def get_task_consumer() -> KafkaTaskConsumer:
    """Returns the singleton KafkaTaskConsumer instance."""
    global _task_consumer
    if _task_consumer is None:
        _task_consumer = KafkaTaskConsumer()
    return _task_consumer

