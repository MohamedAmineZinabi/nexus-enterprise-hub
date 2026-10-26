package com.nexus.hub.grpc;

import com.nexus.hub.common.exception.AiServiceTimeoutException;
import com.nexus.hub.common.exception.AiServiceUnavailableException;
import com.nexus.hub.grpc.ai.v1.*;
import com.nexus.hub.grpc.client.AgentGrpcClient;
import com.nexus.hub.grpc.client.GrpcContextHelper;
import com.nexus.hub.grpc.client.RagGrpcClient;
import com.nexus.hub.modules.user.entity.Role;
import com.nexus.hub.security.UserPrincipal;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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

        inProcessServer = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(fakeRagService)
                .addService(fakeAgentService)
                .build()
                .start();

        inProcessChannel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
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
    @DisplayName("RagGrpcClient.ingestDocument should serialize request, propagate TenantContext, and deserialize response")
    void testIngestDocumentSuccess() {
        UUID documentId = UUID.randomUUID();
        String filePath = "/shared-uploads/tenant-123/doc-test.pdf";
        String mimeType = "application/pdf";

        IngestDocumentResponse response = ragGrpcClient.ingestDocument(
                documentId, filePath, mimeType, 600, 60, "enterprise_docs", Map.of("author", "Dr. Jane"));

        assertThat(response).isNotNull();
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocumentId()).isEqualTo(documentId.toString());
        assertThat(response.getChunksCreated()).isEqualTo(15);
        assertThat(response.getVectorCollection()).isEqualTo("enterprise_docs");

        // Verify request received on fake gRPC server
        IngestDocumentRequest receivedRequest = fakeRagService.lastIngestRequest;
        assertThat(receivedRequest).isNotNull();
        assertThat(receivedRequest.getDocumentId()).isEqualTo(documentId.toString());
        assertThat(receivedRequest.getFilePath()).isEqualTo(filePath);
        assertThat(receivedRequest.getMimeType()).isEqualTo(mimeType);
        assertThat(receivedRequest.getChunkSize()).isEqualTo(600);
        assertThat(receivedRequest.getChunkOverlap()).isEqualTo(60);
        assertThat(receivedRequest.getMetadataMap()).containsEntry("author", "Dr. Jane");

        // Verify TenantContext propagation
        assertThat(receivedRequest.hasContext()).isTrue();
        assertThat(receivedRequest.getContext().getTenantId()).isEqualTo(expectedTenantId.toString());
        assertThat(receivedRequest.getContext().getUserId()).isEqualTo(expectedUserId.toString());
        assertThat(receivedRequest.getContext().getTraceId()).isEqualTo(expectedTraceId);
        assertThat(receivedRequest.getContext().getRolesList()).contains("ROLE_ADMIN");
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

        assertThatThrownBy(() -> ragGrpcClient.ingestDocument(UUID.randomUUID(), "/test/doc.pdf", "text/plain", 500, 50))
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

    // -------------------------------------------------------------------------
    // Fake In-Memory Service Implementations
    // -------------------------------------------------------------------------
    private static class FakeRagService extends RagServiceGrpc.RagServiceImplBase {
        volatile IngestDocumentRequest lastIngestRequest;
        volatile HybridSearchRequest lastSearchRequest;
        volatile DeleteDocumentRequest lastDeleteRequest;
        volatile boolean failWithUnavailable = false;

        @Override
        public void ingestDocument(IngestDocumentRequest request, StreamObserver<IngestDocumentResponse> responseObserver) {
            if (failWithUnavailable) {
                responseObserver.onError(Status.UNAVAILABLE.withDescription("Service offline").asRuntimeException());
                return;
            }
            this.lastIngestRequest = request;
            IngestDocumentResponse response = IngestDocumentResponse.newBuilder()
                    .setDocumentId(request.getDocumentId())
                    .setSuccess(true)
                    .setChunksCreated(15)
                    .setVectorCollection(request.getVectorCollection())
                    .setMessage("Indexed successfully")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void hybridSearch(HybridSearchRequest request, StreamObserver<HybridSearchResponse> responseObserver) {
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
