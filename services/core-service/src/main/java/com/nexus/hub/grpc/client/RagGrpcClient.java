package com.nexus.hub.grpc.client;

import com.nexus.hub.common.exception.AiServiceException;
import com.nexus.hub.common.exception.AiServiceTimeoutException;
import com.nexus.hub.common.exception.AiServiceUnavailableException;
import com.nexus.hub.grpc.ai.v1.*;
import com.nexus.hub.grpc.common.TenantContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
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

    private static final String CB_NAME = "aiServiceGrpc";
    private static final String RETRY_NAME = "aiServiceRetry";

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

    // NOTE: document ingestion is asynchronous over Kafka (document.ingest.requested /
    // document.ingest.completed); the synchronous IngestDocument RPC is no longer called.

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "hybridSearchShortFallback")
    @Retry(name = RETRY_NAME)
    public HybridSearchResponse hybridSearch(String query, int topK, float alpha, boolean rerank) {
        return doHybridSearch(query, topK, alpha, rerank, "enterprise_docs", Collections.emptyMap());
    }

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "hybridSearchFallback")
    @Retry(name = RETRY_NAME)
    public HybridSearchResponse hybridSearch(
            String query,
            int topK,
            float alpha,
            boolean rerank,
            String collectionName,
            Map<String, String> metadataFilters) {
        return doHybridSearch(query, topK, alpha, rerank, collectionName, metadataFilters);
    }

    private HybridSearchResponse doHybridSearch(
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

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "deleteDocumentVectorsShortFallback")
    @Retry(name = RETRY_NAME)
    public DeleteDocumentResponse deleteDocumentVectors(UUID documentId) {
        return doDeleteDocumentVectors(contextHelper.buildTenantContext(), documentId, "enterprise_docs");
    }

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "deleteDocumentVectorsFallback")
    @Retry(name = RETRY_NAME)
    public DeleteDocumentResponse deleteDocumentVectors(UUID documentId, String collectionName) {
        return doDeleteDocumentVectors(contextHelper.buildTenantContext(), documentId, collectionName);
    }

    /**
     * Background orphan-vector cleanup for a document that was deleted while ai-service was indexing
     * it. There is no authenticated user on the Kafka consumer thread, so the TenantContext is built
     * for a system caller from the event's (known) tenant id. Used ONLY by DocumentIngestCompletedConsumer.
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "deleteOrphanDocumentVectorsFallback")
    @Retry(name = RETRY_NAME)
    public DeleteDocumentResponse deleteOrphanDocumentVectors(
            UUID tenantId, UUID documentId, String collectionName, String traceId) {
        return doDeleteDocumentVectors(contextHelper.buildSystemTenantContext(tenantId, traceId), documentId, collectionName);
    }

    private DeleteDocumentResponse doDeleteDocumentVectors(TenantContext context, UUID documentId, String collectionName) {
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

    // -------------------------------------------------------------------------
    // Resilience4j CircuitBreaker Fallbacks (Invoked when circuit state is OPEN)
    // -------------------------------------------------------------------------

    @SuppressWarnings("unused")
    private HybridSearchResponse hybridSearchShortFallback(
            String query, int topK, float alpha, boolean rerank,
            CallNotPermittedException ex) {
        throw openCircuitException("hybridSearch", ex);
    }

    @SuppressWarnings("unused")
    private HybridSearchResponse hybridSearchFallback(
            String query, int topK, float alpha, boolean rerank,
            String collectionName, Map<String, String> metadataFilters,
            CallNotPermittedException ex) {
        throw openCircuitException("hybridSearch", ex);
    }

    @SuppressWarnings("unused")
    private DeleteDocumentResponse deleteDocumentVectorsShortFallback(
            UUID documentId, CallNotPermittedException ex) {
        throw openCircuitException("deleteDocumentVectors", ex);
    }

    @SuppressWarnings("unused")
    private DeleteDocumentResponse deleteDocumentVectorsFallback(
            UUID documentId, String collectionName, CallNotPermittedException ex) {
        throw openCircuitException("deleteDocumentVectors", ex);
    }

    @SuppressWarnings("unused")
    private DeleteDocumentResponse deleteOrphanDocumentVectorsFallback(
            UUID tenantId, UUID documentId, String collectionName, String traceId, CallNotPermittedException ex) {
        throw openCircuitException("deleteOrphanDocumentVectors", ex);
    }

    private AiServiceUnavailableException openCircuitException(String operation, CallNotPermittedException ex) {
        log.warn("CircuitBreaker '{}' is OPEN. Fast-failing RAG gRPC operation '{}': {}", CB_NAME, operation, ex.getMessage());
        return new AiServiceUnavailableException(
                "AI Service is temporarily unavailable (circuit breaker OPEN) during " + operation + ": " + ex.getMessage());
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
            case UNAUTHENTICATED:
                // Service-to-service credential problem (x-internal-api-key mismatch), NOT an end-user auth failure.
                log.error("AI Service rejected core-service internal credentials on {}. "
                        + "Check that INTERNAL_API_KEY matches on both services.", operation);
                return new AiServiceException("AI Service integration is misconfigured; please contact an administrator",
                        HttpStatus.BAD_GATEWAY);
            default:
                return new AiServiceException("AI Service gRPC error [" + code + "]: " + description, HttpStatus.BAD_GATEWAY);
        }
    }
}
