import logging
import grpc
from app.grpc.generated.ai.v1 import agent_service_pb2, agent_service_pb2_grpc

logger = logging.getLogger(__name__)


class AgentServiceServicer(agent_service_pb2_grpc.AgentServiceServicer):
    """Stub servicer for multi-agent workflows and MCP tool discovery (expanded in Day 7)."""

    def ExecuteAgentSync(
        self,
        request: agent_service_pb2.AgentQueryRequest,
        context: grpc.ServicerContext
    ) -> agent_service_pb2.AgentQueryResponse:
        logger.info(f"AgentService.ExecuteAgentSync called for task {request.task_id}")
        return agent_service_pb2.AgentQueryResponse(
            task_id=request.task_id,
            response_text=f"Processed query: {request.user_prompt}",
            execution_status="COMPLETED",
            total_tokens_used=120,
            latency_ms=45
        )

    def ListMcpTools(
        self,
        request: agent_service_pb2.ListMcpToolsRequest,
        context: grpc.ServicerContext
    ) -> agent_service_pb2.ListMcpToolsResponse:
        logger.info("AgentService.ListMcpTools called")
        tools = [
            agent_service_pb2.McpToolDefinition(
                name="qdrant_hybrid_search",
                description="Performs dense semantic and sparse keyword hybrid search across indexed documents",
                input_schema_json='{"type": "object", "properties": {"query": {"type": "string"}}}',
                server_origin="mcp://ai-service:50051"
            ),
            agent_service_pb2.McpToolDefinition(
                name="document_summarizer",
                description="Summarizes enterprise documents with citation extraction",
                input_schema_json='{"type": "object", "properties": {"document_id": {"type": "string"}}}',
                server_origin="mcp://ai-service:50051"
            )
        ]
        return agent_service_pb2.ListMcpToolsResponse(
            tools=tools,
            total_tools=len(tools)
        )
