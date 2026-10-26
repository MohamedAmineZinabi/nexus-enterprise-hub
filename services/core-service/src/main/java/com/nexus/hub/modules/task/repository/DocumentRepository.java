package com.nexus.hub.modules.task.repository;

import com.nexus.hub.modules.task.entity.Document;
import com.nexus.hub.modules.task.entity.DocumentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}

