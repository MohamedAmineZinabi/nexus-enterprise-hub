from ai import common_pb2 as _common_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class IngestDocumentRequest(_message.Message):
    __slots__ = ("context", "document_id", "file_path", "mime_type", "chunk_size", "chunk_overlap", "vector_collection", "metadata")
    class MetadataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CONTEXT_FIELD_NUMBER: _ClassVar[int]
    DOCUMENT_ID_FIELD_NUMBER: _ClassVar[int]
    FILE_PATH_FIELD_NUMBER: _ClassVar[int]
    MIME_TYPE_FIELD_NUMBER: _ClassVar[int]
    CHUNK_SIZE_FIELD_NUMBER: _ClassVar[int]
    CHUNK_OVERLAP_FIELD_NUMBER: _ClassVar[int]
    VECTOR_COLLECTION_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    context: _common_pb2.TenantContext
    document_id: str
    file_path: str
    mime_type: str
    chunk_size: int
    chunk_overlap: int
    vector_collection: str
    metadata: _containers.ScalarMap[str, str]
    def __init__(self, context: _Optional[_Union[_common_pb2.TenantContext, _Mapping]] = ..., document_id: _Optional[str] = ..., file_path: _Optional[str] = ..., mime_type: _Optional[str] = ..., chunk_size: _Optional[int] = ..., chunk_overlap: _Optional[int] = ..., vector_collection: _Optional[str] = ..., metadata: _Optional[_Mapping[str, str]] = ...) -> None: ...

class IngestDocumentResponse(_message.Message):
    __slots__ = ("document_id", "success", "chunks_created", "vector_collection", "message")
    DOCUMENT_ID_FIELD_NUMBER: _ClassVar[int]
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    CHUNKS_CREATED_FIELD_NUMBER: _ClassVar[int]
    VECTOR_COLLECTION_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    document_id: str
    success: bool
    chunks_created: int
    vector_collection: str
    message: str
    def __init__(self, document_id: _Optional[str] = ..., success: _Optional[bool] = ..., chunks_created: _Optional[int] = ..., vector_collection: _Optional[str] = ..., message: _Optional[str] = ...) -> None: ...

class HybridSearchRequest(_message.Message):
    __slots__ = ("context", "query", "top_k", "alpha", "metadata_filters", "rerank", "collection_name")
    class MetadataFiltersEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CONTEXT_FIELD_NUMBER: _ClassVar[int]
    QUERY_FIELD_NUMBER: _ClassVar[int]
    TOP_K_FIELD_NUMBER: _ClassVar[int]
    ALPHA_FIELD_NUMBER: _ClassVar[int]
    METADATA_FILTERS_FIELD_NUMBER: _ClassVar[int]
    RERANK_FIELD_NUMBER: _ClassVar[int]
    COLLECTION_NAME_FIELD_NUMBER: _ClassVar[int]
    context: _common_pb2.TenantContext
    query: str
    top_k: int
    alpha: float
    metadata_filters: _containers.ScalarMap[str, str]
    rerank: bool
    collection_name: str
    def __init__(self, context: _Optional[_Union[_common_pb2.TenantContext, _Mapping]] = ..., query: _Optional[str] = ..., top_k: _Optional[int] = ..., alpha: _Optional[float] = ..., metadata_filters: _Optional[_Mapping[str, str]] = ..., rerank: _Optional[bool] = ..., collection_name: _Optional[str] = ...) -> None: ...

class DocumentChunk(_message.Message):
    __slots__ = ("chunk_id", "document_id", "content", "score", "rerank_score", "metadata", "citation")
    class MetadataEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    CHUNK_ID_FIELD_NUMBER: _ClassVar[int]
    DOCUMENT_ID_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    SCORE_FIELD_NUMBER: _ClassVar[int]
    RERANK_SCORE_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    CITATION_FIELD_NUMBER: _ClassVar[int]
    chunk_id: str
    document_id: str
    content: str
    score: float
    rerank_score: float
    metadata: _containers.ScalarMap[str, str]
    citation: str
    def __init__(self, chunk_id: _Optional[str] = ..., document_id: _Optional[str] = ..., content: _Optional[str] = ..., score: _Optional[float] = ..., rerank_score: _Optional[float] = ..., metadata: _Optional[_Mapping[str, str]] = ..., citation: _Optional[str] = ...) -> None: ...

class HybridSearchResponse(_message.Message):
    __slots__ = ("results", "total_found", "query_interpreted")
    RESULTS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_FOUND_FIELD_NUMBER: _ClassVar[int]
    QUERY_INTERPRETED_FIELD_NUMBER: _ClassVar[int]
    results: _containers.RepeatedCompositeFieldContainer[DocumentChunk]
    total_found: int
    query_interpreted: str
    def __init__(self, results: _Optional[_Iterable[_Union[DocumentChunk, _Mapping]]] = ..., total_found: _Optional[int] = ..., query_interpreted: _Optional[str] = ...) -> None: ...

class DeleteDocumentRequest(_message.Message):
    __slots__ = ("context", "document_id", "collection_name")
    CONTEXT_FIELD_NUMBER: _ClassVar[int]
    DOCUMENT_ID_FIELD_NUMBER: _ClassVar[int]
    COLLECTION_NAME_FIELD_NUMBER: _ClassVar[int]
    context: _common_pb2.TenantContext
    document_id: str
    collection_name: str
    def __init__(self, context: _Optional[_Union[_common_pb2.TenantContext, _Mapping]] = ..., document_id: _Optional[str] = ..., collection_name: _Optional[str] = ...) -> None: ...

class DeleteDocumentResponse(_message.Message):
    __slots__ = ("document_id", "success", "vectors_deleted", "message")
    DOCUMENT_ID_FIELD_NUMBER: _ClassVar[int]
    SUCCESS_FIELD_NUMBER: _ClassVar[int]
    VECTORS_DELETED_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    document_id: str
    success: bool
    vectors_deleted: int
    message: str
    def __init__(self, document_id: _Optional[str] = ..., success: _Optional[bool] = ..., vectors_deleted: _Optional[int] = ..., message: _Optional[str] = ...) -> None: ...
