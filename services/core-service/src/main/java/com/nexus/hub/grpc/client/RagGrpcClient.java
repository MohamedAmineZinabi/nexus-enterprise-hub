package com.nexus.hub.grpc.client;

import com.nexus.hub.common.exception.AiServiceException;
import com.nexus.hub.common.exception.AiServiceTimeoutException;
import com.nexus.hub.common.exception.AiServiceUnavailableException;
import com.nexus.hub.grpc.ai.v1.*;
import com.nexus.hub.grpc.common.TenantContext;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RagGrpcClient {

    private final RagServiceGrpc.RagServiceBlockingStub ragStub;
    private final GrpcContextHelper contextHelper;
    private final long deadlineMs;

    public RagGrpcClient(
            RagServiceGrpc.RagServiceBlockingStub ragStub,
            GrpcContextHelper contextHelper,
            @Value("${nexus.grpc.ai-service.deadline-ms:15000}") long deadlineMs) {
        this.ragStub = ragStub;
        this.contextHelper = contextHelper;
        this.deadlineMs = deadlineMs;
    }

    public IngestDocumentResponse ingestDocument(UUID documentId, String filePath, String mimeType, int chunkSize, int chunkOverlap) {
        return ingestDocument(documentId, filePath, mimeType, chunkSize, chunkOverlap, "enterprise_docs", Collections.emptyMap());
    }

    public IngestDocumentResponse ingestDocument(
            UUID documentId,
            String filePath,
            String mimeType,
            int chunkSize,
            int chunkOverlap,
            String vectorCollection,
            Map<String, String> metadata) {
        TenantContext context = contextHelper.buildTenantContext();
        IngestDocumentRequest request = IngestDocumentRequest.newBuilder()
                .setContext(context)
                .setDocumentId(documentId.toString())
                .setFilePath(filePath)
                .setMimeType(mimeType)
                .setChunkSize(chunkSize > 0 ? chunkSize : 500)
                .setChunkOverlap(chunkOverlap >= 0 ? chunkOverlap : 50)
                .setVectorCollection(vectorCollection != null ? vectorCollection : "enterprise_docs")
                .putAllMetadata(metadata != null ? metadata : Collections.emptyMap())
                .build();

        log.info("Dispatching gRPC IngestDocument for documentId: {}, file: {}", documentId, filePath);
        try {
            return ragStub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS).ingestDocument(request);
        } catch (StatusRuntimeException e) {
            throw handleStatusRuntimeException("IngestDocument", e);
        }
    }

    public HybridSearchResponse hybridSearch(String query, int topK, float alpha, boolean rerank) {
        return hybridSearch(query, topK, alpha, rerank, "enterprise_docs", Collections.emptyMap());
    }

    public HybridSearchResponse hybridSearch(
            String query,
            int topK,
            float alpha,
            boolean rerank,
            String collectionName,
            Map<String, String> metadataFilters) {
        TenantContext context = contextHelper.buildTenantContext();
        HybridSearchRequest request = HybridSearchRequest.newBuilder()
                .setContext(context)
                .setQuery(query)
                .setTopK(topK > 0 ? topK : 5)
                .setAlpha(alpha >= 0.0f && alpha <= 1.0f ? alpha : 0.7f)
                .setRerank(rerank)
                .setCollectionName(collectionName != null ? collectionName : "enterprise_docs")
                .putAllMetadataFilters(metadataFilters != null ? metadataFilters : Collections.emptyMap())
                .build();

        log.info("Dispatching gRPC HybridSearch for query: '{}', topK: {}, alpha: {}", query, topK, alpha);
        try {
            return ragStub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS).hybridSearch(request);
        } catch (StatusRuntimeException e) {
            throw handleStatusRuntimeException("HybridSearch", e);
        }
    }

    public DeleteDocumentResponse deleteDocumentVectors(UUID documentId) {
        return deleteDocumentVectors(documentId, "enterprise_docs");
    }

    public DeleteDocumentResponse deleteDocumentVectors(UUID documentId, String collectionName) {
        TenantContext context = contextHelper.buildTenantContext();
        DeleteDocumentRequest request = DeleteDocumentRequest.newBuilder()
                .setContext(context)
                .setDocumentId(documentId.toString())
                .setCollectionName(collectionName != null ? collectionName : "enterprise_docs")
                .build();

        log.info("Dispatching gRPC DeleteDocumentVectors for documentId: {}", documentId);
        try {
            return ragStub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS).deleteDocumentVectors(request);
        } catch (StatusRuntimeException e) {
            throw handleStatusRuntimeException("DeleteDocumentVectors", e);
        }
    }

    private RuntimeException handleStatusRuntimeException(String operation, StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        String description = e.getStatus().getDescription();
        log.warn("gRPC call {} failed with status {}: {}", operation, code, description);

        switch (code) {
            case UNAVAILABLE:
                return new AiServiceUnavailableException("AI Service is currently unavailable: " + (description != null ? description : "Connection refused"));
            case DEADLINE_EXCEEDED:
                return new AiServiceTimeoutException("AI Service timed out after " + deadlineMs + "ms on " + operation);
            case INVALID_ARGUMENT:
                return new AiServiceException("Invalid argument sent to AI Service: " + description, HttpStatus.BAD_REQUEST);
            default:
                return new AiServiceException("AI Service gRPC error [" + code + "]: " + description, HttpStatus.BAD_GATEWAY);
        }
    }
}
