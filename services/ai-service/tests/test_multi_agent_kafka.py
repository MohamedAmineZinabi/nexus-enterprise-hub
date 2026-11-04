import os
import json
import uuid
import asyncio
import pytest
from datetime import datetime, timezone

from aiokafka import AIOKafkaProducer, AIOKafkaConsumer

from app.core.config import settings
from app.agents.graph import get_agent_graph
from app.agents.state import AgentWorkflowState
from app.kafka.producer import AgentEventPublisher
from app.kafka.consumer import KafkaTaskConsumer


@pytest.mark.asyncio
async def test_direct_langgraph_multi_agent_execution():
    """
    Test 1: Direct Multi-Agent StateGraph Execution.
    Verifies that Planner -> Researcher -> Synthesizer -> Critic pipeline
    executes sequentially, generating sub-tasks, citations, and an executive report.
    """
    graph = get_agent_graph()
    test_task_id = f"test-task-{uuid.uuid4()}"
    test_tenant_id = "d5569b60-2545-4c7b-be8a-4aa7b787de67"

    initial_state: AgentWorkflowState = {
        "task_id": test_task_id,
        "tenant_id": test_tenant_id,
        "user_prompt": "Explain multi-tenant vector isolation in Qdrant and cross-encoder verification",
        "trace_id": f"trace-{test_task_id}",
        "document_ids": [],
        "metadata": {},
        "plan": [],
        "research_data": [],
        "draft_report": "",
        "critique": {},
        "iteration_count": 0,
        "current_node": "START",
        "status": "PROCESSING"
    }

    config = {"configurable": {"thread_id": test_task_id}}
    final_state = await graph.ainvoke(initial_state, config=config)

    # 1. Assert Plan decomposition
    assert "plan" in final_state
    assert len(final_state["plan"]) >= 2
    assert any("vector" in step.lower() or "isolation" in step.lower() for step in final_state["plan"])

    # 2. Assert Researcher outputs
    assert "research_data" in final_state
    assert isinstance(final_state["research_data"], list)

    # 3. Assert Synthesizer generated markdown report with footnotes
    assert "draft_report" in final_state
    report = final_state["draft_report"]
    assert "# Executive" in report
    assert "multi-tenant" in report.lower()

    # 4. Assert Critic verification
    assert "critique" in final_state
    critique = final_state["critique"]
    assert "is_valid" in critique
    assert "hallucination_score" in critique
    assert critique["is_valid"] is True
    assert final_state["status"] == "CRITIQUE_COMPLETED"
    assert final_state["iteration_count"] >= 1


@pytest.mark.asyncio
async def test_kafka_pipeline_task_dispatch_and_events():
    """
    Test 2: End-to-End Kafka Ingestion and Event Streaming Pipeline.
    Publishes an AgentTaskRequestedEvent to `agent.task.requested`,
    verifies intermediate node events on `agent.task.events`,
    and verifies completion on `agent.task.completed`.
    """
    bootstrap_servers = settings.kafka_bootstrap_servers
    task_id = str(uuid.uuid4())
    tenant_id = "d5569b60-2545-4c7b-be8a-4aa7b787de67"
    prompt = "Verify cryptographic JWT token expiration and Redis cache synchronization"
    trace_id = f"trace-kafka-{task_id[:8]}"

    # Start task consumer and publisher
    publisher = AgentEventPublisher(bootstrap_servers=bootstrap_servers)
    await publisher.start()

    consumer = KafkaTaskConsumer(bootstrap_servers=bootstrap_servers)
    await consumer.start()

    # Create a verification consumer to capture produced events
    verify_events_consumer = AIOKafkaConsumer(
        settings.kafka_topic_task_events,
        settings.kafka_topic_task_completed,
        bootstrap_servers=bootstrap_servers,
        group_id=f"test-verifier-{uuid.uuid4()}",
        auto_offset_reset="latest"
    )
    await verify_events_consumer.start()

    # Setup publisher to dispatch task request to `agent.task.requested`
    dispatch_producer = AIOKafkaProducer(
        bootstrap_servers=bootstrap_servers,
        value_serializer=lambda v: json.dumps(v, default=str).encode("utf-8"),
        key_serializer=lambda k: str(k).encode("utf-8")
    )
    await dispatch_producer.start()

    try:
        # Give consumer partitions a moment to settle
        await asyncio.sleep(1.0)

        task_request_payload = {
            "taskId": task_id,
            "tenantId": tenant_id,
            "userId": str(uuid.uuid4()),
            "taskType": "DEEP_RESEARCH",
            "prompt": prompt,
            "documentIds": [],
            "metadata": {"source": "integration_test"},
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "traceId": trace_id
        }

        # Dispatch task to `agent.task.requested`
        await dispatch_producer.send_and_wait(
            settings.kafka_topic_task_requested,
            value=task_request_payload,
            key=tenant_id
        )

        # Listen for events on `agent.task.events` and `agent.task.completed`
        captured_events = []
        completed_event = None
        timeout_seconds = 20.0
        start_wait = asyncio.get_event_loop().time()

        while asyncio.get_event_loop().time() - start_wait < timeout_seconds:
            try:
                msg_batch = await asyncio.wait_for(
                    verify_events_consumer.getmany(timeout_ms=1000, max_records=10),
                    timeout=2.0
                )
                for tp, messages in msg_batch.items():
                    for m in messages:
                        val = json.loads(m.value.decode("utf-8"))
                        if val.get("taskId") == task_id:
                            if m.topic == settings.kafka_topic_task_events:
                                captured_events.append(val)
                            elif m.topic == settings.kafka_topic_task_completed:
                                completed_event = val

                if completed_event is not None and len(captured_events) >= 2:
                    break
            except asyncio.TimeoutError:
                continue

        # Assertions
        assert len(captured_events) >= 2, f"Expected intermediate node events for task {task_id}, got {len(captured_events)}"
        assert completed_event is not None, f"Expected completed event on {settings.kafka_topic_task_completed} for task {task_id}"

        # Verify event sequence
        node_types = [e.get("nodeType") for e in captured_events]
        assert "WORKFLOW" in node_types or "PLANNER" in node_types

        # Verify completed payload
        assert completed_event["status"] == "COMPLETED"
        assert len(completed_event["report"]) > 0

    finally:
        await dispatch_producer.stop()
        await verify_events_consumer.stop()
        await consumer.stop()
        await publisher.stop()

