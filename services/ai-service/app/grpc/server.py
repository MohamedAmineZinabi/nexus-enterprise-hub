import asyncio
import logging
from concurrent import futures
from typing import Optional
import grpc

from app.core.config import settings
from app.grpc.generated.ai.v1 import rag_service_pb2_grpc, agent_service_pb2_grpc
from app.grpc.servicers.rag_servicer import RagServiceServicer
from app.grpc.servicers.agent_servicer import AgentServiceServicer

logger = logging.getLogger(__name__)

_grpc_server: Optional[grpc.aio.Server] = None


async def start_grpc_server() -> grpc.aio.Server:
    """Initialize and start the asyncio gRPC server for RagService and AgentService."""
    global _grpc_server

    bind_address = f"{settings.grpc_host}:{settings.grpc_port}"
    logger.info(f"Configuring asyncio gRPC server on {bind_address}...")

    server_options = [
        ("grpc.max_receive_message_length", 30 * 1024 * 1024),
        ("grpc.max_send_message_length", 30 * 1024 * 1024),
        ("grpc.keepalive_time_ms", 30000),
        ("grpc.keepalive_timeout_ms", 5000),
        ("grpc.http2.min_ping_interval_without_data_ms", 5000),
        ("grpc.keepalive_permit_without_calls", 1),
    ]

    _grpc_server = grpc.aio.server(
        options=server_options
    )

    rag_service_pb2_grpc.add_RagServiceServicer_to_server(RagServiceServicer(), _grpc_server)
    agent_service_pb2_grpc.add_AgentServiceServicer_to_server(AgentServiceServicer(), _grpc_server)

    _grpc_server.add_insecure_port(bind_address)
    await _grpc_server.start()
    logger.info(f"gRPC server successfully started and listening on {bind_address}")
    return _grpc_server


async def stop_grpc_server(grace: float = 5.0) -> None:
    """Gracefully terminate the running gRPC server."""
    global _grpc_server
    if _grpc_server is not None:
        logger.info(f"Initiating graceful shutdown of gRPC server (grace={grace}s)...")
        await _grpc_server.stop(grace=grace)
        logger.info("gRPC server stopped.")
        _grpc_server = None
