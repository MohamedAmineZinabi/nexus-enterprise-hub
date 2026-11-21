import logging
import grpc
from qdrant_client.http import models

from app.core.config import settings
from app.db.qdrant import get_qdrant_client
from app.rag.retriever import get_hybrid_retriever
from app.rag.ingestion import ingest_document, InvalidIngestRequest, SourceFileMissing
from app.grpc.generated.ai.v1 import rag_service_pb2, rag_service_pb2_grpc
from app.security.auth import require_tenant_id, TenantValidationError

logger = logging.getLogger(__name__)


def _validated_tenant(request) -> str:
    """Returns the TenantContext tenant_id or raises TenantValidationError (no fallback tenant)."""
    raw = request.context.tenant_id if request.HasField("context") else ""
    return require_tenant_id(raw)


def _reject(context, code: grpc.StatusCode, details: str) -> None:
    logger.warning(f"RagService request rejected ({code.name}): {details}")
    context.set_code(code)
    context.set_details(details)


class RagServiceServicer(rag_service_pb2_grpc.RagServiceServicer):
    """Production gRPC servicer implementing RAG ingestion, hybrid search, and vector lifecycle."""

    def __init__(self):
        self.retriever = get_hybrid_retriever()
        self.qdrant_client = get_qdrant_client()

    def IngestDocument(
        self,
        request: rag_service_pb2.IngestDocumentRequest,
        context: grpc.ServicerContext
    ) -> rag_service_pb2.IngestDocumentResponse:
        """
        Legacy synchronous ingestion (core-service now uses the async document.ingest.requested
        Kafka flow). Kept for internal tooling; shares the exact pipeline with the Kafka consumer.
        """
        try:
            tenant_id = _validated_tenant(request)
        except TenantValidationError as exc:
            _reject(context, grpc.StatusCode.INVALID_ARGUMENT, str(exc))
            return rag_service_pb2.IngestDocumentResponse(
                document_id=request.document_id, success=False, message=str(exc)
            )

        logger.info(f"gRPC IngestDocument: document_id={request.document_id}, tenant={tenant_id}")
        try:
            result = ingest_document(
                tenant_id=tenant_id,
                document_id=request.document_id,
                file_path=request.file_path,
                mime_type=request.mime_type,
                chunk_size=request.chunk_size,
                chunk_overlap=request.chunk_overlap,
                collection=request.vector_collection,
                metadata=dict(request.metadata),
            )
            return rag_service_pb2.IngestDocumentResponse(
                document_id=request.document_id,
                success=True,
                chunks_created=result.chunks_created,
                vector_collection=result.collection,
                message=f"Document indexed successfully with {result.chunks_created} chunks"
            )
        except InvalidIngestRequest as exc:
            _reject(context, grpc.StatusCode.INVALID_ARGUMENT, str(exc))
            return rag_service_pb2.IngestDocumentResponse(
                document_id=request.document_id, success=False, message=str(exc)
            )
        except SourceFileMissing as exc:
            _reject(context, grpc.StatusCode.NOT_FOUND, str(exc))
            return rag_service_pb2.IngestDocumentResponse(
                document_id=request.document_id, success=False, message=str(exc)
            )
        except Exception as e:
            logger.error(f"Failed to ingest document {request.document_id}: {e}", exc_info=True)
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(f"Ingestion failed: {str(e)}")
            return rag_service_pb2.IngestDocumentResponse(
                document_id=request.document_id,
                success=False,
                message=f"Ingestion failed: {str(e)}"
            )

    def HybridSearch(
        self,
        request: rag_service_pb2.HybridSearchRequest,
        context: grpc.ServicerContext
    ) -> rag_service_pb2.HybridSearchResponse:
        """Execute multi-tenant hybrid search and cross-encoder reranking."""
        try:
            tenant_id = _validated_tenant(request)
            collection_name = settings.resolve_collection(request.collection_name)
        except (TenantValidationError, ValueError) as exc:
            _reject(context, grpc.StatusCode.INVALID_ARGUMENT, str(exc))
            return rag_service_pb2.HybridSearchResponse()

        query = request.query.strip()
        if not query:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("query cannot be empty")
            return rag_service_pb2.HybridSearchResponse()

        top_k = request.top_k if request.top_k > 0 else settings.default_top_k
        alpha = request.alpha if 0.0 <= request.alpha <= 1.0 else settings.default_alpha
        metadata_filters = dict(request.metadata_filters) if request.metadata_filters else None

        logger.info(
            f"gRPC HybridSearch: tenant={tenant_id}, query='{query}', "
            f"top_k={top_k}, alpha={alpha}, rerank={request.rerank}"
        )

        try:
            results = self.retriever.search(
                tenant_id=tenant_id,
                query=query,
                top_k=top_k,
                alpha=alpha,
                collection_name=collection_name,
                metadata_filters=metadata_filters,
                rerank=request.rerank
            )

            proto_chunks = []
            for r in results:
                proto_chunks.append(
                    rag_service_pb2.DocumentChunk(
                        chunk_id=r.chunk_id,
                        document_id=r.document_id,
                        content=r.content,
                        score=r.score,
                        rerank_score=r.rerank_score,
                        metadata=r.metadata,
                        citation=r.citation
                    )
                )

            return rag_service_pb2.HybridSearchResponse(
                results=proto_chunks,
                total_found=len(proto_chunks),
                query_interpreted=query
            )

        except Exception as e:
            logger.error(f"Failed to execute HybridSearch: {e}", exc_info=True)
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(f"Hybrid search failed: {str(e)}")
            return rag_service_pb2.HybridSearchResponse()

    def DeleteDocumentVectors(
        self,
        request: rag_service_pb2.DeleteDocumentRequest,
        context: grpc.ServicerContext
    ) -> rag_service_pb2.DeleteDocumentResponse:
        """Delete all indexed vectors for a specific document and tenant from Qdrant."""
        document_id = (request.document_id or "").strip()
        try:
            tenant_id = _validated_tenant(request)
            collection_name = settings.resolve_collection(request.collection_name)
            if not document_id:
                raise ValueError("document_id is required")
        except (TenantValidationError, ValueError) as exc:
            _reject(context, grpc.StatusCode.INVALID_ARGUMENT, str(exc))
            return rag_service_pb2.DeleteDocumentResponse(document_id=document_id, success=False, message=str(exc))

        logger.info(f"gRPC DeleteDocumentVectors: tenant={tenant_id}, document_id={document_id}")

        try:
            tenant_doc_filter = models.Filter(
                must=[
                    models.FieldCondition(key="tenant_id", match=models.MatchValue(value=tenant_id)),
                    models.FieldCondition(key="document_id", match=models.MatchValue(value=document_id)),
                ]
            )

            if not self.qdrant_client.collection_exists(collection_name=collection_name):
                return rag_service_pb2.DeleteDocumentResponse(
                    document_id=document_id, success=True, vectors_deleted=0,
                    message=f"Collection '{collection_name}' does not exist; nothing to delete",
                )

            to_delete = self.qdrant_client.count(
                collection_name=collection_name, count_filter=tenant_doc_filter, exact=True
            ).count

            result = self.qdrant_client.delete(
                collection_name=collection_name,
                points_selector=models.FilterSelector(filter=tenant_doc_filter),
                wait=True,
            )
            remaining = self.qdrant_client.count(
                collection_name=collection_name, count_filter=tenant_doc_filter, exact=True
            ).count

            completed = getattr(result, "status", None) == models.UpdateStatus.COMPLETED
            success = completed and remaining == 0
            deleted = max(0, to_delete - remaining)

            if not success:
                details = f"Vector purge incomplete for document {document_id}: {remaining} vector(s) remain"
                _reject(context, grpc.StatusCode.INTERNAL, details)
                return rag_service_pb2.DeleteDocumentResponse(
                    document_id=document_id, success=False, vectors_deleted=deleted, message=details
                )

            logger.info(f"Purged {deleted} vectors for document {document_id} from '{collection_name}'")
            return rag_service_pb2.DeleteDocumentResponse(
                document_id=document_id,
                success=True,
                vectors_deleted=deleted,
                message=f"Purged {deleted} vector(s) for document {document_id}"
            )

        except Exception as e:
            logger.error(f"Failed to delete document vectors: {e}", exc_info=True)
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(f"Failed to delete vectors: {str(e)}")
            return rag_service_pb2.DeleteDocumentResponse(
                document_id=document_id,
                success=False,
                message=str(e)
            )
