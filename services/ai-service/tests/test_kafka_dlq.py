import json
import uuid
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from aiokafka.structs import OffsetAndMetadata, TopicPartition

from app.kafka.consumer import KafkaTaskConsumer


@pytest.mark.asyncio
async def test_poison_pill_malformed_json_routes_to_dlq():
    consumer = KafkaTaskConsumer(bootstrap_servers="localhost:9092")
    publisher = MagicMock()
    publisher.publish_dlq = AsyncMock()
    publisher.publish_completed = AsyncMock()
    publisher.publish_event = AsyncMock()

    graph = MagicMock()
    malformed_payload = "{not-valid-json-poison-pill"
    msg_meta = {
        "topic": "agent.task.requested",
        "partition": 1,
        "offset": 42,
        "headers": {"x-trace-id": "trace-poison-1"},
    }

    with patch("app.kafka.consumer.save_task_state", new=AsyncMock()):
        await consumer._process_task_message(malformed_payload, graph, publisher, msg_meta=msg_meta)

    publisher.publish_dlq.assert_awaited_once()
    dlq_kwargs = publisher.publish_dlq.await_args.kwargs
    assert dlq_kwargs["task_id"] == "unknown"
    assert dlq_kwargs["error_type"] == "JSONDecodeError"
    assert dlq_kwargs["raw_message"] == malformed_payload
    assert dlq_kwargs["partition"] == 1
    assert dlq_kwargs["offset"] == 42
    assert dlq_kwargs["headers"] == {"x-trace-id": "trace-poison-1"}
    publisher.publish_completed.assert_not_awaited()


@pytest.mark.asyncio
async def test_unrecoverable_graph_failure_emits_dlq_and_failed_completion():
    consumer = KafkaTaskConsumer(bootstrap_servers="localhost:9092")
    publisher = MagicMock()
    publisher.publish_dlq = AsyncMock()
    publisher.publish_completed = AsyncMock()
    publisher.publish_event = AsyncMock()

    task_id = str(uuid.uuid4())
    tenant_id = str(uuid.uuid4())
    payload = json.dumps({
        "taskId": task_id,
        "tenantId": tenant_id,
        "prompt": "Run risk evaluation",
        "traceId": f"trace-{task_id}",
    })

    class BrokenGraph:
        async def astream(self, state, config=None):
            raise RuntimeError("Simulated LangGraph node explosion")
            yield state

    mock_lock = MagicMock()
    mock_lock.acquired = True
    lock_cm = MagicMock()
    lock_cm.__aenter__ = AsyncMock(return_value=mock_lock)
    lock_cm.__aexit__ = AsyncMock(return_value=False)

    mock_save_state = AsyncMock()
    with patch("app.kafka.consumer.distributed_task_lock", return_value=lock_cm), \
         patch("app.kafka.consumer.save_task_state", new=mock_save_state):
        await consumer._process_task_message(payload, BrokenGraph(), publisher)

    publisher.publish_dlq.assert_awaited_once()
    dlq_kwargs = publisher.publish_dlq.await_args.kwargs
    assert dlq_kwargs["task_id"] == task_id
    assert dlq_kwargs["tenant_id"] == tenant_id
    assert dlq_kwargs["error_type"] == "RuntimeError"
    assert "Simulated LangGraph node explosion" in dlq_kwargs["error"]

    publisher.publish_completed.assert_awaited_once()
    comp_kwargs = publisher.publish_completed.await_args.kwargs
    assert comp_kwargs["task_id"] == task_id
    assert comp_kwargs["execution_status"] == "FAILED"
    assert "RuntimeError" in comp_kwargs["error_details"]

    # Verify FAILED state recorded in Redis
    saved_statuses = [call.args[1].get("status") for call in mock_save_state.await_args_list]
    assert "FAILED" in saved_statuses


@pytest.mark.asyncio
async def test_commit_record_offset_commits_exact_next_offset():
    consumer = KafkaTaskConsumer(bootstrap_servers="localhost:9092")
    consumer.consumer = MagicMock()
    consumer.consumer.commit = AsyncMock()

    record = SimpleNamespace(topic="agent.task.requested", partition=2, offset=99)
    await consumer._commit_record_offset(record)

    consumer.consumer.commit.assert_awaited_once_with({
        TopicPartition("agent.task.requested", 2): OffsetAndMetadata(100, "")
    })
