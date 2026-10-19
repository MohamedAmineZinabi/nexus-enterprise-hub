from ai import common_pb2 as _common_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class NodeType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    NODE_TYPE_UNSPECIFIED: _ClassVar[NodeType]
    NODE_TYPE_PLANNER: _ClassVar[NodeType]
    NODE_TYPE_RESEARCHER: _ClassVar[NodeType]
    NODE_TYPE_SYNTHESIZER: _ClassVar[NodeType]
    NODE_TYPE_CRITIC: _ClassVar[NodeType]
    NODE_TYPE_MCP_TOOL: _ClassVar[NodeType]

class EventPhase(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    EVENT_PHASE_UNSPECIFIED: _ClassVar[EventPhase]
    EVENT_PHASE_STARTED: _ClassVar[EventPhase]
    EVENT_PHASE_IN_PROGRESS: _ClassVar[EventPhase]
    EVENT_PHASE_COMPLETED: _ClassVar[EventPhase]
    EVENT_PHASE_FAILED: _ClassVar[EventPhase]
NODE_TYPE_UNSPECIFIED: NodeType
NODE_TYPE_PLANNER: NodeType
NODE_TYPE_RESEARCHER: NodeType
NODE_TYPE_SYNTHESIZER: NodeType
NODE_TYPE_CRITIC: NodeType
NODE_TYPE_MCP_TOOL: NodeType
EVENT_PHASE_UNSPECIFIED: EventPhase
EVENT_PHASE_STARTED: EventPhase
EVENT_PHASE_IN_PROGRESS: EventPhase
EVENT_PHASE_COMPLETED: EventPhase
EVENT_PHASE_FAILED: EventPhase

class AgentQueryRequest(_message.Message):
    __slots__ = ("context", "task_id", "user_prompt", "mcp_tools_enabled", "session_parameters", "enable_critic")
    class SessionParametersEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CONTEXT_FIELD_NUMBER: _ClassVar[int]
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    USER_PROMPT_FIELD_NUMBER: _ClassVar[int]
    MCP_TOOLS_ENABLED_FIELD_NUMBER: _ClassVar[int]
    SESSION_PARAMETERS_FIELD_NUMBER: _ClassVar[int]
    ENABLE_CRITIC_FIELD_NUMBER: _ClassVar[int]
    context: _common_pb2.TenantContext
    task_id: str
    user_prompt: str
    mcp_tools_enabled: _containers.RepeatedScalarFieldContainer[str]
    session_parameters: _containers.ScalarMap[str, str]
    enable_critic: bool
    def __init__(self, context: _Optional[_Union[_common_pb2.TenantContext, _Mapping]] = ..., task_id: _Optional[str] = ..., user_prompt: _Optional[str] = ..., mcp_tools_enabled: _Optional[_Iterable[str]] = ..., session_parameters: _Optional[_Mapping[str, str]] = ..., enable_critic: _Optional[bool] = ...) -> None: ...

class AgentCitation(_message.Message):
    __slots__ = ("document_id", "title", "snippet", "confidence")
    DOCUMENT_ID_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    SNIPPET_FIELD_NUMBER: _ClassVar[int]
    CONFIDENCE_FIELD_NUMBER: _ClassVar[int]
    document_id: str
    title: str
    snippet: str
    confidence: float
    def __init__(self, document_id: _Optional[str] = ..., title: _Optional[str] = ..., snippet: _Optional[str] = ..., confidence: _Optional[float] = ...) -> None: ...

class AgentQueryResponse(_message.Message):
    __slots__ = ("task_id", "response_text", "citations", "total_tokens_used", "execution_status", "latency_ms")
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    RESPONSE_TEXT_FIELD_NUMBER: _ClassVar[int]
    CITATIONS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_TOKENS_USED_FIELD_NUMBER: _ClassVar[int]
    EXECUTION_STATUS_FIELD_NUMBER: _ClassVar[int]
    LATENCY_MS_FIELD_NUMBER: _ClassVar[int]
    task_id: str
    response_text: str
    citations: _containers.RepeatedCompositeFieldContainer[AgentCitation]
    total_tokens_used: int
    execution_status: str
    latency_ms: int
    def __init__(self, task_id: _Optional[str] = ..., response_text: _Optional[str] = ..., citations: _Optional[_Iterable[_Union[AgentCitation, _Mapping]]] = ..., total_tokens_used: _Optional[int] = ..., execution_status: _Optional[str] = ..., latency_ms: _Optional[int] = ...) -> None: ...

class AgentWorkflowEvent(_message.Message):
    __slots__ = ("task_id", "trace_id", "node_type", "phase", "token_chunk", "message", "mcp_tool_name", "mcp_tool_input", "mcp_tool_output", "timestamp_ms")
    TASK_ID_FIELD_NUMBER: _ClassVar[int]
    TRACE_ID_FIELD_NUMBER: _ClassVar[int]
    NODE_TYPE_FIELD_NUMBER: _ClassVar[int]
    PHASE_FIELD_NUMBER: _ClassVar[int]
    TOKEN_CHUNK_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    MCP_TOOL_NAME_FIELD_NUMBER: _ClassVar[int]
    MCP_TOOL_INPUT_FIELD_NUMBER: _ClassVar[int]
    MCP_TOOL_OUTPUT_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_MS_FIELD_NUMBER: _ClassVar[int]
    task_id: str
    trace_id: str
    node_type: NodeType
    phase: EventPhase
    token_chunk: str
    message: str
    mcp_tool_name: str
    mcp_tool_input: str
    mcp_tool_output: str
    timestamp_ms: int
    def __init__(self, task_id: _Optional[str] = ..., trace_id: _Optional[str] = ..., node_type: _Optional[_Union[NodeType, str]] = ..., phase: _Optional[_Union[EventPhase, str]] = ..., token_chunk: _Optional[str] = ..., message: _Optional[str] = ..., mcp_tool_name: _Optional[str] = ..., mcp_tool_input: _Optional[str] = ..., mcp_tool_output: _Optional[str] = ..., timestamp_ms: _Optional[int] = ...) -> None: ...

class ListMcpToolsRequest(_message.Message):
    __slots__ = ("context",)
    CONTEXT_FIELD_NUMBER: _ClassVar[int]
    context: _common_pb2.TenantContext
    def __init__(self, context: _Optional[_Union[_common_pb2.TenantContext, _Mapping]] = ...) -> None: ...

class McpToolDefinition(_message.Message):
    __slots__ = ("name", "description", "input_schema_json", "server_origin")
    NAME_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    INPUT_SCHEMA_JSON_FIELD_NUMBER: _ClassVar[int]
    SERVER_ORIGIN_FIELD_NUMBER: _ClassVar[int]
    name: str
    description: str
    input_schema_json: str
    server_origin: str
    def __init__(self, name: _Optional[str] = ..., description: _Optional[str] = ..., input_schema_json: _Optional[str] = ..., server_origin: _Optional[str] = ...) -> None: ...

class ListMcpToolsResponse(_message.Message):
    __slots__ = ("tools", "total_tools")
    TOOLS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_TOOLS_FIELD_NUMBER: _ClassVar[int]
    tools: _containers.RepeatedCompositeFieldContainer[McpToolDefinition]
    total_tools: int
    def __init__(self, tools: _Optional[_Iterable[_Union[McpToolDefinition, _Mapping]]] = ..., total_tools: _Optional[int] = ...) -> None: ...
