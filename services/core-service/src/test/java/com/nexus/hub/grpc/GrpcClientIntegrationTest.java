package com.nexus.hub.grpc;

import com.nexus.hub.common.exception.AiServiceException;
import com.nexus.hub.common.exception.AiServiceTimeoutException;
import com.nexus.hub.common.exception.AiServiceUnavailableException;
import com.nexus.hub.config.GrpcClientConfig;
import com.nexus.hub.grpc.ai.v1.*;
import com.nexus.hub.grpc.client.AgentGrpcClient;
import com.nexus.hub.grpc.client.GrpcContextHelper;
import com.nexus.hub.grpc.client.RagGrpcClient;
import com.nexus.hub.modules.user.entity.Role;
import com.nexus.hub.security.UserPrincipal;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrpcClientIntegrationTest {

    private static final String TEST_INTERNAL_API_KEY = "test-internal-api-key-0123456789";

    private ApiKeyCapturingServerInterceptor apiKeyCaptor;
    private String serverName;
    private Server inProcessServer;
    private ManagedChannel inProcessChannel;

    private FakeRagService fakeRagService;
    private FakeAgentService fakeAgentService;

    private RagGrpcClient ragGrpcClient;
    private AgentGrpcClient agentGrpcClient;

    private UUID expectedTenantId;
    private UUID expectedUserId;
    private String expectedTraceId;

    @BeforeEach
    void setUp() throws Exception {
        serverName = InProcessServerBuilder.generateName();
        fakeRagService = new FakeRagService();
        fakeAgentService = new FakeAgentService();

        apiKeyCaptor = new ApiKeyCapturingServerInterceptor();
        inProcessServer = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(fakeRagService, apiKeyCaptor))
                .addService(ServerInterceptors.intercept(fakeAgentService, apiKeyCaptor))
                .build()
                .start();

        // Same interceptor production wires onto the ManagedChannel in GrpcClientConfig
        inProcessChannel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .intercept(GrpcClientConfig.internalApiKeyInterceptor(TEST_INTERNAL_API_KEY))
                .build();

        GrpcContextHelper contextHelper = new GrpcContextHelper();

        RagServiceGrpc.RagServiceBlockingStub ragStub = RagServiceGrpc.newBlockingStub(inProcessChannel);
        ragGrpcClient = new RagGrpcClient(ragStub, contextHelper, 5000);

        AgentServiceGrpc.AgentServiceBlockingStub agentBlockingStub = AgentServiceGrpc.newBlockingStub(inProcessChannel);
        AgentServiceGrpc.AgentServiceStub agentAsyncStub = AgentServiceGrpc.newStub(inProcessChannel);
        agentGrpcClient = new AgentGrpcClient(agentBlockingStub, agentAsyncStub, contextHelper, 5000);

        // Configure security context and MDC for TenantContext propagation
        expectedTenantId = UUID.randomUUID();
        expectedUserId = UUID.randomUUID();
        expectedTraceId = "trace-test-" + UUID.randomUUID();
        MDC.put("traceId", expectedTraceId);

        UserPrincipal principal = UserPrincipal.builder()
                .id(expectedUserId)
                .tenantId(expectedTenantId)
                .email("test.engineer@techcorp.io")
                .role(Role.ADMIN)
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .enabled(true)
                .build();

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        MDC.clear();

        if (inProcessChannel != null) {
            inProcessChannel.shutdownNow();
            inProcessChannel.awaitTermination(3, TimeUnit.SECONDS);
        }
        if (inProcessServer != null) {
            inProcessServer.shutdownNow();
            inProcessServer.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("deleteDocumentVectors propagates the full user TenantContext (tenant, user, trace, roles)")
    void testDeleteDocumentVectorsPropagatesUserContext() {
        ragGrpcClient.deleteDocumentVectors(UUID.randomUUID(), "enterprise_docs");

        DeleteDocumentRequest receivedRequest = fakeRagService.lastDeleteRequest;
        assertThat(receivedRequest.hasContext()).isTrue();
        assertThat(receivedRequest.getContext().getTenantId()).isEqualTo(expectedTenantId.toString());
        assertThat(receivedRequest.getContext().getUserId()).isEqualTo(expectedUserId.toString());
        assertThat(receivedRequest.getContext().getTraceId()).isEqualTo(expectedTraceId);
        assertThat(receivedRequest.getContext().getRolesList()).contains("ROLE_ADMIN");
    }

    @Test
    @DisplayName("deleteOrphanDocumentVectors works without a principal using a system context for the GIVEN tenant")
    void testOrphanDeleteUsesSystemContextForKnownTenant() {
        SecurityContextHolder.clearContext();
        UUID orphanTenant = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        DeleteDocumentResponse response = ragGrpcClient.deleteOrphanDocumentVectors(
                orphanTenant, docId, "enterprise_docs", "trace-orphan-1");

        assertThat(response.getSuccess()).isTrue();
        DeleteDocumentRequest received = fakeRagService.lastDeleteRequest;
        assertThat(received.getDocumentId()).isEqualTo(docId.toString());
        assertThat(received.getContext().getTenantId()).isEqualTo(orphanTenant.toString());
        assertThat(received.getContext().getUserId()).isEqualTo(GrpcContextHelper.SYSTEM_USER_ID);
        assertThat(received.getContext().getTraceId()).isEqualTo("trace-orphan-1");
        assertThat(received.getContext().getRolesList()).containsExactly(GrpcContextHelper.SYSTEM_ROLE);
        assertThat(apiKeyCaptor.seenKeys).containsOnly(TEST_INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("System TenantContext requires an explicit tenant id (no random-tenant fallback)")
    void testSystemContextRequiresTenant() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> ragGrpcClient.deleteOrphanDocumentVectors(null, UUID.randomUUID(), "enterprise_docs", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fakeRagService.lastDeleteRequest).isNull();
    }

    @Test
    @DisplayName("RagGrpcClient.hybridSearch should send parameters and return search chunk hits")
    void testHybridSearchSuccess() {
        HybridSearchResponse response = ragGrpcClient.hybridSearch(
                "quarterly revenue analysis", 10, 0.65f, true, "enterprise_docs", Map.of("category", "finance"));

        assertThat(response).isNotNull();
        assertThat(response.getTotalFound()).isEqualTo(2);
        assertThat(response.getResultsCount()).isEqualTo(2);

        DocumentChunk chunk1 = response.getResults(0);
        assertThat(chunk1.getChunkId()).isEqualTo("chk-001");
        assertThat(chunk1.getContent()).contains("Q3 financial reports");
        assertThat(chunk1.getScore()).isEqualTo(0.92f);

        // Verify server received request
        HybridSearchRequest receivedRequest = fakeRagService.lastSearchRequest;
        assertThat(receivedRequest).isNotNull();
        assertThat(receivedRequest.getQuery()).isEqualTo("quarterly revenue analysis");
        assertThat(receivedRequest.getTopK()).isEqualTo(10);
        assertThat(receivedRequest.getAlpha()).isEqualTo(0.65f);
        assertThat(receivedRequest.getRerank()).isTrue();
        assertThat(receivedRequest.getContext().getTenantId()).isEqualTo(expectedTenantId.toString());
    }

    @Test
    @DisplayName("RagGrpcClient.deleteDocumentVectors should invoke RPC and propagate TenantContext")
    void testDeleteDocumentVectorsSuccess() {
        UUID docId = UUID.randomUUID();
        DeleteDocumentResponse response = ragGrpcClient.deleteDocumentVectors(docId, "enterprise_docs");

        assertThat(response).isNotNull();
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getVectorsDeleted()).isEqualTo(8);

        DeleteDocumentRequest receivedRequest = fakeRagService.lastDeleteRequest;
        assertThat(receivedRequest).isNotNull();
        assertThat(receivedRequest.getDocumentId()).isEqualTo(docId.toString());
        assertThat(receivedRequest.getCollectionName()).isEqualTo("enterprise_docs");
        assertThat(receivedRequest.getContext().getTenantId()).isEqualTo(expectedTenantId.toString());
    }

    @Test
    @DisplayName("RagGrpcClient should map UNAVAILABLE status to AiServiceUnavailableException")
    void testRagClientUnavailableFallback() {
        fakeRagService.failWithUnavailable = true;

        assertThatThrownBy(() -> ragGrpcClient.deleteDocumentVectors(UUID.randomUUID(), "enterprise_docs"))
                .isInstanceOf(AiServiceUnavailableException.class)
                .hasMessageContaining("AI Service is currently unavailable");
    }

    @Test
    @DisplayName("AgentGrpcClient.executeAgentSync should execute synchronous agent invocation")
    void testExecuteAgentSyncSuccess() {
        String query = "Summarize compliance policy for 2026";
        String sessionId = "sess-8899";

        AgentQueryResponse response = agentGrpcClient.executeAgentSync(query, sessionId);

        assertThat(response).isNotNull();
        assertThat(response.getResponseText()).contains("Compliance Summary");
        assertThat(response.getExecutionStatus()).isEqualTo("COMPLETED");
        assertThat(response.getTotalTokensUsed()).isEqualTo(420);

        AgentQueryRequest received = fakeAgentService.lastQueryRequest;
        assertThat(received).isNotNull();
        assertThat(received.getUserPrompt()).isEqualTo(query);
        assertThat(received.getSessionParametersMap()).containsEntry("session_id", sessionId);
        assertThat(received.getContext().getTenantId()).isEqualTo(expectedTenantId.toString());
    }

    @Test
    @DisplayName("AgentGrpcClient.listMcpTools should return active MCP tool definitions")
    void testListMcpToolsSuccess() {
        ListMcpToolsResponse response = agentGrpcClient.listMcpTools();

        assertThat(response).isNotNull();
        assertThat(response.getTotalTools()).isEqualTo(2);
        assertThat(response.getToolsList()).hasSize(2);
        assertThat(response.getTools(0).getName()).isEqualTo("web_search");
        assertThat(response.getTools(1).getName()).isEqualTo("sql_executor");

        ListMcpToolsRequest received = fakeAgentService.lastListMcpRequest;
        assertThat(received).isNotNull();
        assertThat(received.getContext().getTenantId()).isEqualTo(expectedTenantId.toString());
    }

    @Test
    @DisplayName("AgentGrpcClient.streamAgentWorkflow should stream real-time events to Consumer")
    void testStreamAgentWorkflow() throws InterruptedException {
        List<AgentWorkflowEvent> receivedEvents = new CopyOnWriteArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        agentGrpcClient.streamAgentWorkflow(
                "Run deep analysis",
                "task-uuid-4455",
                List.of("web_search"),
                receivedEvents::add,
                throwable -> latch.countDown(),
                () -> {
                    completed.set(true);
                    latch.countDown();
                }
        );

        boolean finishedInTime = latch.await(3, TimeUnit.SECONDS);
        assertThat(finishedInTime).isTrue();
        assertThat(completed.get()).isTrue();
        assertThat(receivedEvents).hasSize(3);

        assertThat(receivedEvents.get(0).getNodeType()).isEqualTo(NodeType.NODE_TYPE_PLANNER);
        assertThat(receivedEvents.get(0).getPhase()).isEqualTo(EventPhase.EVENT_PHASE_STARTED);

        assertThat(receivedEvents.get(1).getNodeType()).isEqualTo(NodeType.NODE_TYPE_RESEARCHER);
        assertThat(receivedEvents.get(1).getTokenChunk()).isEqualTo("Analyzing research findings...");

        assertThat(receivedEvents.get(2).getNodeType()).isEqualTo(NodeType.NODE_TYPE_SYNTHESIZER);
        assertThat(receivedEvents.get(2).getPhase()).isEqualTo(EventPhase.EVENT_PHASE_COMPLETED);
    }

    @Test
    @DisplayName("Every Rag and Agent call carries the x-internal-api-key metadata header")
    void testInternalApiKeyAttachedToEveryCall() {
        ragGrpcClient.hybridSearch("q", 3, 0.5f, false, "enterprise_docs", Map.of());
        ragGrpcClient.deleteDocumentVectors(UUID.randomUUID(), "enterprise_docs");
        agentGrpcClient.listMcpTools();
        agentGrpcClient.executeAgentSync("hello", "sess-1");

        assertThat(apiKeyCaptor.seenKeys).hasSize(4).containsOnly(TEST_INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("internalApiKeyInterceptor fails fast with a clear message when the key is blank")
    void testBlankInternalApiKeyFailsFast() {
        assertThatThrownBy(() -> GrpcClientConfig.internalApiKeyInterceptor("  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_API_KEY");
        assertThatThrownBy(() -> GrpcClientConfig.internalApiKeyInterceptor(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("UNAUTHENTICATED from ai-service maps to AiServiceException (502), not a user 401")
    void testUnauthenticatedMapsToAiServiceException() {
        fakeRagService.failWithUnauthenticated = true;

        assertThatThrownBy(() -> ragGrpcClient.hybridSearch("q", 3, 0.5f, false, "enterprise_docs", Map.of()))
                .isInstanceOf(AiServiceException.class)
                .satisfies(ex -> assertThat(((AiServiceException) ex).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    @DisplayName("Calls without an authenticated principal are rejected instead of using a random tenant")
    void testNoPrincipalIsRejected() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> ragGrpcClient.hybridSearch("q", 3, 0.5f, false, "enterprise_docs", Map.of()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThat(fakeRagService.lastSearchRequest).isNull();
    }

    // -------------------------------------------------------------------------
    // Fake In-Memory Service Implementations
    // -------------------------------------------------------------------------
    private static class ApiKeyCapturingServerInterceptor implements ServerInterceptor {
        final List<String> seenKeys = new CopyOnWriteArrayList<>();

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            String key = headers.get(Metadata.Key.of("x-internal-api-key", Metadata.ASCII_STRING_MARSHALLER));
            seenKeys.add(String.valueOf(key));
            return next.startCall(call, headers);
        }
    }

    private static class FakeRagService extends RagServiceGrpc.RagServiceImplBase {
        volatile HybridSearchRequest lastSearchRequest;
        volatile DeleteDocumentRequest lastDeleteRequest;
        volatile boolean failWithUnavailable = false;
        volatile boolean failWithUnauthenticated = false;

        @Override
        public void hybridSearch(HybridSearchRequest request, StreamObserver<HybridSearchResponse> responseObserver) {
            if (failWithUnauthenticated) {
                responseObserver.onError(Status.UNAUTHENTICATED.withDescription("bad internal key").asRuntimeException());
                return;
            }
            this.lastSearchRequest = request;
            DocumentChunk chunk1 = DocumentChunk.newBuilder()
                    .setChunkId("chk-001")
                    .setDocumentId("doc-101")
                    .setContent("Q3 financial reports show 25% ARR growth.")
                    .setScore(0.92f)
                    .setRerankScore(0.95f)
                    .putMetadata("department", "Finance")
                    .setCitation("Finance 2026 pg. 12")
                    .build();

            DocumentChunk chunk2 = DocumentChunk.newBuilder()
                    .setChunkId("chk-002")
                    .setDocumentId("doc-102")
                    .setContent("Operating margin increased to 31%.")
                    .setScore(0.88f)
                    .setRerankScore(0.90f)
                    .putMetadata("department", "Finance")
                    .setCitation("Finance 2026 pg. 15")
                    .build();

            HybridSearchResponse response = HybridSearchResponse.newBuilder()
                    .addResults(chunk1)
                    .addResults(chunk2)
                    .setTotalFound(2)
                    .setQueryInterpreted(request.getQuery())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void deleteDocumentVectors(DeleteDocumentRequest request, StreamObserver<DeleteDocumentResponse> responseObserver) {
            if (failWithUnavailable) {
                responseObserver.onError(Status.UNAVAILABLE.withDescription("Service offline").asRuntimeException());
                return;
            }
            this.lastDeleteRequest = request;
            DeleteDocumentResponse response = DeleteDocumentResponse.newBuilder()
                    .setDocumentId(request.getDocumentId())
                    .setSuccess(true)
                    .setVectorsDeleted(8)
                    .setMessage("Document vectors purged")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    private static class FakeAgentService extends AgentServiceGrpc.AgentServiceImplBase {
        volatile AgentQueryRequest lastQueryRequest;
        volatile ListMcpToolsRequest lastListMcpRequest;

        @Override
        public void executeAgentSync(AgentQueryRequest request, StreamObserver<AgentQueryResponse> responseObserver) {
            this.lastQueryRequest = request;
            AgentQueryResponse response = AgentQueryResponse.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setResponseText("Compliance Summary: All ISO/IEC 27001 policies verified.")
                    .setExecutionStatus("COMPLETED")
                    .setTotalTokensUsed(420)
                    .setLatencyMs(85)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void streamAgentWorkflow(AgentQueryRequest request, StreamObserver<AgentWorkflowEvent> responseObserver) {
            AgentWorkflowEvent ev1 = AgentWorkflowEvent.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setNodeType(NodeType.NODE_TYPE_PLANNER)
                    .setPhase(EventPhase.EVENT_PHASE_STARTED)
                    .setMessage("Planner node initialized")
                    .setTimestampMs(System.currentTimeMillis())
                    .build();

            AgentWorkflowEvent ev2 = AgentWorkflowEvent.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setNodeType(NodeType.NODE_TYPE_RESEARCHER)
                    .setPhase(EventPhase.EVENT_PHASE_IN_PROGRESS)
                    .setTokenChunk("Analyzing research findings...")
                    .setTimestampMs(System.currentTimeMillis())
                    .build();

            AgentWorkflowEvent ev3 = AgentWorkflowEvent.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setNodeType(NodeType.NODE_TYPE_SYNTHESIZER)
                    .setPhase(EventPhase.EVENT_PHASE_COMPLETED)
                    .setMessage("Synthesis complete")
                    .setTimestampMs(System.currentTimeMillis())
                    .build();

            responseObserver.onNext(ev1);
            responseObserver.onNext(ev2);
            responseObserver.onNext(ev3);
            responseObserver.onCompleted();
        }

        @Override
        public void listMcpTools(ListMcpToolsRequest request, StreamObserver<ListMcpToolsResponse> responseObserver) {
            this.lastListMcpRequest = request;
            McpToolDefinition tool1 = McpToolDefinition.newBuilder()
                    .setName("web_search")
                    .setDescription("Performs live search on Bing/DuckDuckGo")
                    .setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}")
                    .setServerOrigin("mcp://search-server:8000")
                    .build();

            McpToolDefinition tool2 = McpToolDefinition.newBuilder()
                    .setName("sql_executor")
                    .setDescription("Executes read-only SQL queries against analytical datastore")
                    .setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}}}")
                    .setServerOrigin("mcp://db-server:8001")
                    .build();

            ListMcpToolsResponse response = ListMcpToolsResponse.newBuilder()
                    .addTools(tool1)
                    .addTools(tool2)
                    .setTotalTools(2)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
