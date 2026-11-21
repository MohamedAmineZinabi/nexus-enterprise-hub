package com.nexus.hub.modules.document;

import com.nexus.hub.common.exception.ApiException;
import com.nexus.hub.config.RedisConfig;
import com.nexus.hub.grpc.ai.v1.DeleteDocumentResponse;
import com.nexus.hub.grpc.ai.v1.HybridSearchResponse;
import com.nexus.hub.grpc.client.RagGrpcClient;
import com.nexus.hub.kafka.event.DocumentIngestRequestedEvent;
import com.nexus.hub.modules.document.controller.DocumentController;
import com.nexus.hub.modules.document.dto.DocumentUploadResponse;
import com.nexus.hub.modules.document.dto.HybridSearchRequestDto;
import com.nexus.hub.modules.document.event.DocumentUploadedEvent;
import com.nexus.hub.modules.document.service.DocumentService;
import com.nexus.hub.modules.task.entity.Document;
import com.nexus.hub.modules.task.entity.DocumentStatus;
import com.nexus.hub.modules.task.repository.DocumentRepository;
import com.nexus.hub.modules.tenant.entity.Tenant;
import com.nexus.hub.modules.user.entity.Role;
import com.nexus.hub.modules.user.entity.User;
import com.nexus.hub.modules.user.repository.UserRepository;
import com.nexus.hub.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * C4 regression: one document id across disk filename, DB row, gRPC ingest, API response, and
 * vector deletion; plus Persistable/isNew semantics and collection allowlisting.
 */
@ExtendWith(MockitoExtension.class)
class DocumentIdConsistencyTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RagGrpcClient ragGrpcClient;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @TempDir
    Path uploadsDir;

    private DocumentService documentService;
    private UserPrincipal principal;
    private User user;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(documentRepository, userRepository, ragGrpcClient, eventPublisher,
                TransactionOperations.withoutTransaction(), uploadsDir.toString(), "enterprise_docs, finance_docs");
        tenantId = UUID.randomUUID();
        Tenant tenant = Tenant.builder().id(tenantId).name("A").slug("a").status("ACTIVE").build();
        user = User.builder().id(UUID.randomUUID()).tenant(tenant).email("a@a.io").fullName("A")
                .role(Role.ADMIN).enabled(true).build();
        principal = UserPrincipal.builder().id(user.getId()).tenantId(tenantId).email("a@a.io").role(Role.ADMIN)
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))).enabled(true).build();
    }

    @Test
    @DisplayName("Document is new until persisted/loaded; id is assigned, not generated")
    void persistableIsNewLifecycle() throws Exception {
        UUID id = UUID.randomUUID();
        Document doc = Document.builder().id(id).filename("a.pdf").build();
        assertThat(doc.isNew()).isTrue();
        assertThat(doc.getId()).isEqualTo(id);

        // Simulate the JPA @PostPersist / @PostLoad callback
        var callback = Document.class.getDeclaredMethod("markNotNew");
        assertThat(callback.isAnnotationPresent(jakarta.persistence.PostPersist.class)).isTrue();
        assertThat(callback.isAnnotationPresent(jakarta.persistence.PostLoad.class)).isTrue();
        callback.setAccessible(true);
        callback.invoke(doc);
        assertThat(doc.isNew()).isFalse();

        assertThat(Document.class.getDeclaredField("id").isAnnotationPresent(jakarta.persistence.GeneratedValue.class))
                .as("id must not be @GeneratedValue").isFalse();
    }

    @Test
    @DisplayName("Upload persists INDEXING, publishes one event with the same id for file, row, Kafka payload and response")
    void uploadUsesSingleDocumentId() throws Exception {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "Q3 report.pdf", "application/pdf",
                "%PDF-1.7\n%test".getBytes(StandardCharsets.US_ASCII));

        DocumentUploadResponse response = documentService.uploadAndIngest(principal, file, 500, 50, null);

        ArgumentCaptor<Document> savedCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, times(1)).save(savedCaptor.capture());
        Document saved = savedCaptor.getValue();
        UUID dbId = saved.getId();
        assertThat(saved.isNew()).isTrue();
        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.INDEXING);
        assertThat(saved.getChunksCount()).isZero();

        // Exactly one application event (the Kafka send happens AFTER_COMMIT in DocumentIngestDispatchListener)
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());
        DocumentUploadedEvent event = (DocumentUploadedEvent) eventCaptor.getValue();
        DocumentIngestRequestedEvent payload = event.payload();
        assertThat(event.documentId()).isEqualTo(dbId);
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(payload.getDocumentId()).isEqualTo(dbId.toString());
        assertThat(payload.getTenantId()).isEqualTo(tenantId.toString());
        assertThat(payload.getUserId()).isEqualTo(user.getId().toString());
        assertThat(payload.getFileName()).isEqualTo("Q3_report.pdf");
        assertThat(payload.getMimeType()).isEqualTo("application/pdf");
        assertThat(payload.getChunkSize()).isEqualTo(500);
        assertThat(payload.getChunkOverlap()).isEqualTo(50);
        assertThat(payload.getVectorCollection()).isEqualTo("enterprise_docs");
        assertThat(payload.getMetadata()).containsEntry("filename", "Q3 report.pdf");
        assertThat(payload.getTraceId()).isNotBlank();
        assertThat(Instant.parse(payload.getRequestedAt())).isNotNull();
        // filePath = {shared-uploads-dir}/{tenantId}/{documentId}_{fileName}, absolute, forward slashes
        String expectedPath = uploadsDir.toAbsolutePath().toString().replace('\\', '/')
                + "/" + tenantId + "/" + dbId + "_Q3_report.pdf";
        assertThat(payload.getFilePath()).isEqualTo(expectedPath);
        assertThat(saved.getFilePath()).isEqualTo(expectedPath);

        assertThat(response.getDocumentId()).isEqualTo(dbId);
        assertThat(response.getStatus()).isEqualTo(DocumentStatus.INDEXING);
        assertThat(response.getChunksCreated()).isZero();

        // No synchronous gRPC ingestion on the request path
        verifyNoInteractions(ragGrpcClient);

        try (Stream<Path> files = Files.list(uploadsDir.resolve(tenantId.toString()))) {
            assertThat(files.map(p -> p.getFileName().toString()).toList())
                    .singleElement().asString().isEqualTo(dbId + "_Q3_report.pdf");
        }
    }

    @Test
    @DisplayName("Upload without chunk parameters sends null chunkSize/chunkOverlap (ai-service defaults)")
    void uploadWithoutChunkParamsSendsNulls() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        documentService.uploadAndIngest(principal, file, null, null, "finance_docs");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        DocumentIngestRequestedEvent payload = ((DocumentUploadedEvent) eventCaptor.getValue()).payload();
        assertThat(payload.getChunkSize()).isNull();
        assertThat(payload.getChunkOverlap()).isNull();
        assertThat(payload.getVectorCollection()).isEqualTo("finance_docs");
    }

    @Test
    @DisplayName("If the row cannot be persisted, the stored file is removed and no event is published")
    void persistenceFailureRemovesFile() throws Exception {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(documentRepository.save(any(Document.class))).thenThrow(new IllegalStateException("db down"));
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> documentService.uploadAndIngest(principal, file, null, null, null))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(eventPublisher);
        try (Stream<Path> files = Files.list(uploadsDir.resolve(tenantId.toString()))) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    @DisplayName("markIngestDispatchFailed only moves INDEXING -> FAILED for the same tenant, in REQUIRES_NEW")
    void markIngestDispatchFailedUsesConditionalUpdate() throws Exception {
        UUID docId = UUID.randomUUID();
        when(documentRepository.transitionStatus(eq(docId), eq(tenantId), eq(DocumentStatus.INDEXING),
                eq(DocumentStatus.FAILED), eq(0), anyString(), any(Instant.class))).thenReturn(1);

        documentService.markIngestDispatchFailed(docId, tenantId, "Ingestion dispatch failed: Kafka publish failed: X");

        verify(documentRepository).transitionStatus(eq(docId), eq(tenantId), eq(DocumentStatus.INDEXING),
                eq(DocumentStatus.FAILED), eq(0), contains("Kafka publish failed"), any(Instant.class));

        Method m = DocumentService.class.getMethod("markIngestDispatchFailed", UUID.class, UUID.class, String.class);
        assertThat(m.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        CacheEvict evict = m.getAnnotation(CacheEvict.class);
        assertThat(evict.value()).containsExactly(RedisConfig.DOCUMENTS_CACHE);
        assertThat(evict.key()).isEqualTo("#tenantId");
    }

    @Test
    @DisplayName("Upload no longer runs inside a single @Transactional method (file I/O outside the DB transaction)")
    void uploadIsNotTransactional() throws Exception {
        Method upload = DocumentService.class.getMethod("uploadAndIngest", UserPrincipal.class,
                org.springframework.web.multipart.MultipartFile.class, Integer.class, Integer.class, String.class);
        assertThat(upload.getAnnotation(Transactional.class)).isNull();
        assertThat(upload.getAnnotation(CacheEvict.class).key()).isEqualTo("#principal.tenantId");
    }

    @Test
    @DisplayName("Controller answers 202 Accepted with status INDEXING")
    void controllerReturns202() {
        DocumentService service = mock(DocumentService.class);
        DocumentUploadResponse body = DocumentUploadResponse.builder().documentId(UUID.randomUUID())
                .status(DocumentStatus.INDEXING).chunksCreated(0).message("m").build();
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        when(service.uploadAndIngest(principal, file, null, null, null)).thenReturn(body);

        var response = new DocumentController(service).uploadDocument(principal, file, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().getData().getStatus()).isEqualTo(DocumentStatus.INDEXING);
    }

    @Test
    @DisplayName("Delete purges vectors with the persisted id, removes the file and the DB row")
    void deletePurgesVectorsFileAndRow() throws Exception {
        UUID docId = UUID.randomUUID();
        Path stored = Files.createDirectories(uploadsDir.resolve(tenantId.toString())).resolve(docId + "_a.pdf");
        Files.writeString(stored, "%PDF-1.4");
        Document doc = Document.builder().id(docId).tenant(user.getTenant()).user(user).filename("a.pdf")
                .filePath(stored.toString()).fileSize(8L).mimeType("application/pdf")
                .vectorCollection("enterprise_docs").build();
        when(documentRepository.findByIdAndTenantId(docId, tenantId)).thenReturn(Optional.of(doc));
        when(ragGrpcClient.deleteDocumentVectors(docId, "enterprise_docs"))
                .thenReturn(DeleteDocumentResponse.newBuilder().setSuccess(true).build());

        documentService.deleteDocument(principal, docId);

        verify(ragGrpcClient).deleteDocumentVectors(docId, "enterprise_docs");
        verify(documentRepository).delete(doc);
        assertThat(Files.exists(stored)).isFalse();
    }

    @Test
    @DisplayName("Upload rejects a vector collection outside the allowlist with 400 before touching disk")
    void uploadRejectsDisallowedCollection() {
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> documentService.uploadAndIngest(principal, file, null, null, "other_tenant_docs"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(documentRepository, ragGrpcClient, eventPublisher);
        assertThat(Files.exists(uploadsDir.resolve(tenantId.toString()))).isFalse();
    }

    @Test
    @DisplayName("Search defaults blank collection to enterprise_docs, allows listed ones and rejects others")
    void searchCollectionAllowlist() {
        when(ragGrpcClient.hybridSearch(anyString(), anyInt(), anyFloat(), anyBoolean(), anyString(), any()))
                .thenReturn(HybridSearchResponse.newBuilder().build());

        documentService.searchDocuments(principal, HybridSearchRequestDto.builder().query("q").collectionName(" ").build());
        verify(ragGrpcClient).hybridSearch(eq("q"), anyInt(), anyFloat(), anyBoolean(), eq("enterprise_docs"), any());

        documentService.searchDocuments(principal, HybridSearchRequestDto.builder().query("q").collectionName("finance_docs").build());
        verify(ragGrpcClient).hybridSearch(eq("q"), anyInt(), anyFloat(), anyBoolean(), eq("finance_docs"), any());

        assertThatThrownBy(() -> documentService.searchDocuments(principal,
                HybridSearchRequestDto.builder().query("q").collectionName("secret_collection").metadataFilters(Map.of()).build()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
