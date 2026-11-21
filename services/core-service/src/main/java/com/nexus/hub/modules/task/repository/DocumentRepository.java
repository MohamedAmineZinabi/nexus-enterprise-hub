package com.nexus.hub.modules.task.repository;

import com.nexus.hub.modules.task.entity.Document;
import com.nexus.hub.modules.task.entity.DocumentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {
    Page<Document> findByTenantId(UUID tenantId, Pageable pageable);
    List<Document> findByTenantId(UUID tenantId);
    List<Document> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    Optional<Document> findByIdAndTenantId(UUID id, UUID tenantId);
    List<Document> findByTenantIdAndStatus(UUID tenantId, DocumentStatus status);

    /**
     * Atomic compare-and-set of a document's ingestion outcome: the row is only updated if it exists,
     * belongs to {@code tenantId} AND is currently in {@code expectedStatus}. A single UPDATE statement,
     * so duplicate / out-of-order / cross-tenant events can never overwrite a terminal state.
     *
     * @return number of rows updated (0 or 1)
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Document d SET d.status = :newStatus, d.chunksCount = :chunksCount, "
            + "d.errorDetails = :errorDetails, d.updatedAt = :updatedAt "
            + "WHERE d.id = :id AND d.tenant.id = :tenantId AND d.status = :expectedStatus")
    int transitionStatus(@Param("id") UUID id,
                         @Param("tenantId") UUID tenantId,
                         @Param("expectedStatus") DocumentStatus expectedStatus,
                         @Param("newStatus") DocumentStatus newStatus,
                         @Param("chunksCount") int chunksCount,
                         @Param("errorDetails") String errorDetails,
                         @Param("updatedAt") Instant updatedAt);
}
