import os
import uuid
import logging
import grpc
from qdrant_client.http import models

from app.core.config import settings
from app.db.qdrant import get_qdrant_client, init_qdrant_collection
from app.rag.parser import DocumentParser
from app.rag.embeddings import get_embedding_service
from app.rag.retriever import get_hybrid_retriever
from app.grpc.generated.ai.v1 import rag_service_pb2, rag_service_pb2_grpc

logger = logging.getLogger(__name__)


def _resolve_file_path(path: str) -> str:
    """Resolve file path across local dev and container shared volume environments."""
    if os.path.exists(path):
        return path

    normalized = path.replace("\\", "/").lstrip("/")
    if normalized.startswith("shared-uploads/"):
        normalized = normalized[len("shared-uploads/"):]

    candidate = os.path.join(settings.shared_uploads_dir, normalized)
    if os.path.exists(candidate):
        return candidate

    basename_candidate = os.path.join(settings.shared_uploads_dir, os.path.basename(path))
    if os.path.exists(basename_candidate):
        return basename_candidate

    return path


class RagServiceServicer(rag_service_pb2_grpc.RagServiceServicer):
    """Production gRPC servicer implementing RAG ingestion, hybrid search, and vector lifecycle."""

    def __init__(self):
        self.embedding_service = get_embedding_service()
        self.retriever = get_hybrid_retriever()
        self.qdrant_client = get_qdrant_client()

    def IngestDocument(
        self,
        request: rag_service_pb2.IngestDocumentRequest,
        context: grpc.ServicerContext
    ) -> rag_service_pb2.IngestDocumentResponse:
        """Parse document from shared disk, compute embeddings, and index into Qdrant."""
        tenant_id = request.context.tenant_id if request.HasField("context") else ""
        if not tenant_id:
            logger.warning("IngestDocument rejected: missing tenant_id in TenantContext")
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("tenant_id must be provided in TenantContext")
            return rag_service_pb2.IngestDocumentResponse(
                document_id=request.document_id,
                success=False,
                message="tenant_id must be provided in TenantContext"
            )

        collection_name = request.vector_collection or settings.qdrant_collection_name
        resolved_path = _resolve_file_path(request.file_path)

        logger.info(
            f"gRPC IngestDocument: document_id={request.document_id}, "
            f"tenant={tenant_id}, file={resolved_path}, collection={collection_name}"
        )

        if not os.path.exists(resolved_path):
            err_msg = f"Document file not found on disk: {resolved_path}"
            logger.error(err_msg)
            context.set_code(grpc.StatusCode.NOT_FOUND)
            context.set_details(err_msg)
            return rag_service_pb2.IngestDocumentResponse(
                document_id=request.document_id,
                success=False,
                message=err_msg
            )

        try:
            # Ensure Qdrant collection exists before indexing
            init_qdrant_collection(collection_name)

            chunk_size = request.chunk_size if request.chunk_size > 0 else settings.default_chunk_size
            chunk_overlap = request.chunk_overlap if request.chunk_overlap >= 0 else settings.default_chunk_overlap
            parser = DocumentParser(chunk_size=chunk_size, chunk_overlap=chunk_overlap)

            metadata_dict = dict(request.metadata)
            chunks = parser.parse_and_chunk(
                file_path=resolved_path,
                document_id=request.document_id,
                mime_type=request.mime_type,
                base_metadata=metadata_dict
            )

            if not chunks:
                logger.warning(f"No textual content could be extracted from {resolved_path}")
                return rag_service_pb2.IngestDocumentResponse(
                    document_id=request.document_id,
                    success=True,
                    chunks_created=0,
                    vector_collection=collection_name,
                    message="Document parsed successfully but yielded 0 text chunks"
                )

            # Compute dense embeddings
            texts = [c.content for c in chunks]
            embeddings = self.embedding_service.embed_documents(texts)

            # Build Qdrant PointStruct list
            points = []
            for chunk, vector in zip(chunks, embeddings):
                point_uuid = str(uuid.uuid5(uuid.NAMESPACE_DNS, chunk.chunk_id))
                payload = {
                    "tenant_id": tenant_id,
                    "document_id": request.document_id,
                    "chunk_id": chunk.chunk_id,
                    "content": chunk.content,
                    "chunk_index": chunk.chunk_index,
                    "page_number": chunk.page_number,
                    "citation": chunk.citation,
                    "metadata": chunk.metadata
                }
                points.append(
                    models.PointStruct(
                        id=point_uuid,
                        vector=vector,
                        payload=payload
                    )
                )

            # Upsert in batches of 100 to Qdrant
            batch_size = 100
            for i in range(0, len(points), batch_size):
                batch = points[i:i + batch_size]
                self.qdrant_client.upsert(
                    collection_name=collection_name,
                    points=batch
                )

            logger.info(
                f"Successfully indexed {len(points)} points into Qdrant collection '{collection_name}' "
                f"for document {request.document_id}"
            )

            return rag_service_pb2.IngestDocumentResponse(
                document_id=request.document_id,
                success=True,
                chunks_created=len(points),
                vector_collection=collection_name,
                message=f"Document indexed successfully with {len(points)} chunks"
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
        tenant_id = request.context.tenant_id if request.HasField("context") else ""
        if not tenant_id:
            logger.warning("HybridSearch rejected: missing tenant_id in TenantContext")
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("tenant_id must be provided in TenantContext")
            return rag_service_pb2.HybridSearchResponse()

        query = request.query.strip()
        if not query:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("query cannot be empty")
            return rag_service_pb2.HybridSearchResponse()

        top_k = request.top_k if request.top_k > 0 else settings.default_top_k
        alpha = request.alpha if 0.0 <= request.alpha <= 1.0 else settings.default_alpha
        collection_name = request.collection_name or settings.qdrant_collection_name
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
        tenant_id = request.context.tenant_id if request.HasField("context") else ""
        document_id = request.document_id
        collection_name = request.collection_name or settings.qdrant_collection_name

        if not tenant_id or not document_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("tenant_id and document_id are required")
            return rag_service_pb2.DeleteDocumentResponse(
                document_id=document_id,
                success=False,
                message="tenant_id and document_id are required"
            )

        logger.info(f"gRPC DeleteDocumentVectors: tenant={tenant_id}, document_id={document_id}")

        try:
            self.qdrant_client.delete(
                collection_name=collection_name,
                points_selector=models.FilterSelector(
                    filter=models.Filter(
                        must=[
                            models.FieldCondition(
                                key="tenant_id",
                                match=models.MatchValue(value=tenant_id)
                            ),
                            models.FieldCondition(
                                key="document_id",
                                match=models.MatchValue(value=document_id)
                            )
                        ]
                    )
                )
            )

            logger.info(f"Purged vectors for document {document_id} from '{collection_name}'")
            return rag_service_pb2.DeleteDocumentResponse(
                document_id=document_id,
                success=True,
                vectors_deleted=1,
                message=f"Vectors purged for document {document_id}"
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
