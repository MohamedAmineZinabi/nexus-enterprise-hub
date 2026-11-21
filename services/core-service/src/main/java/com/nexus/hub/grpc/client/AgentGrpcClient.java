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
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@Component
public class AgentGrpcClient {

    private static final String CB_NAME = "aiServiceGrpc";
    private static final String RETRY_NAME = "aiServiceRetry";

    private final AgentServiceGrpc.AgentServiceBlockingStub agentBlockingStub;
    private final AgentServiceGrpc.AgentServiceStub agentAsyncStub;
    private final GrpcContextHelper contextHelper;
    private final long deadlineMs;

    public AgentGrpcClient(
            AgentServiceGrpc.AgentServiceBlockingStub agentBlockingStub,
            AgentServiceGrpc.AgentServiceStub agentAsyncStub,
            GrpcContextHelper contextHelper,
            @Value("${nexus.grpc.ai-service.deadline-ms:15000}") long deadlineMs) {
        this.agentBlockingStub = agentBlockingStub;
        this.agentAsyncStub = agentAsyncStub;
        this.contextHelper = contextHelper;
        this.deadlineMs = deadlineMs;
    }

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "executeAgentSyncShortFallback")
    @Retry(name = RETRY_NAME)
    public AgentQueryResponse executeAgentSync(String query, String sessionId) {
        return doExecuteAgentSync(query, sessionId, UUID.randomUUID().toString(), Collections.emptyList(), Collections.emptyMap(), false);
    }

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "executeAgentSyncFallback")
    @Retry(name = RETRY_NAME)
    public AgentQueryResponse executeAgentSync(
            String query,
            String sessionId,
            String taskId,
            List<String> mcpToolsEnabled,
            Map<String, String> sessionParams,
            boolean enableCritic) {
        return doExecuteAgentSync(query, sessionId, taskId, mcpToolsEnabled, sessionParams, enableCritic);
    }

    private AgentQueryResponse doExecuteAgentSync(
            String query,
            String sessionId,
            String taskId,
            List<String> mcpToolsEnabled,
            Map<String, String> sessionParams,
            boolean enableCritic) {
        TenantContext context = contextHelper.buildTenantContext();
        AgentQueryRequest.Builder builder = AgentQueryRequest.newBuilder()
                .setContext(context)
                .setTaskId(taskId != null ? taskId : UUID.randomUUID().toString())
                .setUserPrompt(query)
                .setEnableCritic(enableCritic);

        if (mcpToolsEnabled != null) {
            builder.addAllMcpToolsEnabled(mcpToolsEnabled);
        }
        if (sessionParams != null) {
            builder.putAllSessionParameters(sessionParams);
        }
        if (sessionId != null) {
            builder.putSessionParameters("session_id", sessionId);
        }

        AgentQueryRequest request = builder.build();
        log.info("Dispatching gRPC executeAgentSync for taskId: {}, query: '{}'", request.getTaskId(), query);
        try {
            return agentBlockingStub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS).executeAgentSync(request);
        } catch (StatusRuntimeException e) {
            throw handleStatusRuntimeException("executeAgentSync", e);
        }
    }

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "listMcpToolsFallback")
    @Retry(name = RETRY_NAME)
    public ListMcpToolsResponse listMcpTools() {
        TenantContext context = contextHelper.buildTenantContext();
        ListMcpToolsRequest request = ListMcpToolsRequest.newBuilder()
                .setContext(context)
                .build();

        log.info("Dispatching gRPC listMcpTools request");
        try {
            return agentBlockingStub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS).listMcpTools(request);
        } catch (StatusRuntimeException e) {
            throw handleStatusRuntimeException("listMcpTools", e);
        }
    }

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "streamAgentWorkflowFallback")
    public void streamAgentWorkflow(
            String query,
            String taskId,
            List<String> mcpToolsEnabled,
            Consumer<AgentWorkflowEvent> eventConsumer,
            Consumer<Throwable> errorConsumer,
            Runnable completionHandler) {
        TenantContext context = contextHelper.buildTenantContext();
        AgentQueryRequest.Builder builder = AgentQueryRequest.newBuilder()
                .setContext(context)
                .setTaskId(taskId != null ? taskId : UUID.randomUUID().toString())
                .setUserPrompt(query);

        if (mcpToolsEnabled != null) {
            builder.addAllMcpToolsEnabled(mcpToolsEnabled);
        }

        AgentQueryRequest request = builder.build();
        log.info("Opening gRPC streamAgentWorkflow for taskId: {}", request.getTaskId());

        agentAsyncStub.streamAgentWorkflow(request, new StreamObserver<AgentWorkflowEvent>() {
            @Override
            public void onNext(AgentWorkflowEvent event) {
                if (eventConsumer != null) {
                    try {
                        eventConsumer.accept(event);
                    } catch (Exception ex) {
                        log.error("Error in streamAgentWorkflow eventConsumer: {}", ex.getMessage(), ex);
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("streamAgentWorkflow stream encountered error: {}", t.getMessage());
                if (errorConsumer != null) {
                    errorConsumer.accept(t);
                }
            }

            @Override
            public void onCompleted() {
                log.info("streamAgentWorkflow completed for taskId: {}", request.getTaskId());
                if (completionHandler != null) {
                    completionHandler.run();
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Resilience4j CircuitBreaker Fallbacks (Invoked when circuit state is OPEN)
    // -------------------------------------------------------------------------

    @SuppressWarnings("unused")
    private AgentQueryResponse executeAgentSyncShortFallback(
            String query, String sessionId, CallNotPermittedException ex) {
        throw openCircuitException("executeAgentSync", ex);
    }

    @SuppressWarnings("unused")
    private AgentQueryResponse executeAgentSyncFallback(
            String query,
            String sessionId,
            String taskId,
            List<String> mcpToolsEnabled,
            Map<String, String> sessionParams,
            boolean enableCritic,
            CallNotPermittedException ex) {
        throw openCircuitException("executeAgentSync", ex);
    }

    @SuppressWarnings("unused")
    private ListMcpToolsResponse listMcpToolsFallback(CallNotPermittedException ex) {
        throw openCircuitException("listMcpTools", ex);
    }

    @SuppressWarnings("unused")
    private void streamAgentWorkflowFallback(
            String query,
            String taskId,
            List<String> mcpToolsEnabled,
            Consumer<AgentWorkflowEvent> eventConsumer,
            Consumer<Throwable> errorConsumer,
            Runnable completionHandler,
            CallNotPermittedException ex) {
        throw openCircuitException("streamAgentWorkflow", ex);
    }

    private AiServiceUnavailableException openCircuitException(String operation, CallNotPermittedException ex) {
        log.warn("CircuitBreaker '{}' is OPEN. Fast-failing Agent gRPC operation '{}': {}", CB_NAME, operation, ex.getMessage());
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
