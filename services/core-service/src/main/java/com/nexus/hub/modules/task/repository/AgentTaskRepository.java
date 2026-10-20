package com.nexus.hub.modules.task.repository;

import com.nexus.hub.modules.task.entity.AgentTask;
import com.nexus.hub.modules.task.entity.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AgentTaskRepository extends JpaRepository<AgentTask, UUID> {
    Page<AgentTask> findByTenantId(UUID tenantId, Pageable pageable);
    Page<AgentTask> findByTenantIdAndStatus(UUID tenantId, TaskStatus status, Pageable pageable);
}
