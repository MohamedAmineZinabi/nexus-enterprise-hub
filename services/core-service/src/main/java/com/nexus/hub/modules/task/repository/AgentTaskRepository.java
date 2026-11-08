package com.nexus.hub.modules.task.repository;

import com.nexus.hub.modules.task.entity.AgentTask;
import com.nexus.hub.modules.task.entity.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AgentTaskRepository extends JpaRepository<AgentTask, UUID> {
    Page<AgentTask> findByTenantId(UUID tenantId, Pageable pageable);
    Page<AgentTask> findByTenantIdAndStatus(UUID tenantId, TaskStatus status, Pageable pageable);

    @Modifying
    @Query("UPDATE AgentTask t SET t.lastActiveNode = :nodeName, t.status = CASE WHEN t.status = com.nexus.hub.modules.task.entity.TaskStatus.PENDING THEN com.nexus.hub.modules.task.entity.TaskStatus.PROCESSING ELSE t.status END WHERE t.id = :taskId AND t.status NOT IN (com.nexus.hub.modules.task.entity.TaskStatus.COMPLETED, com.nexus.hub.modules.task.entity.TaskStatus.FAILED)")
    int updateProgressState(@Param("taskId") UUID taskId, @Param("nodeName") String nodeName);
}
