package com.nexus.hub.modules.task.repository;

import com.nexus.hub.modules.task.entity.TaskAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TaskAuditEventRepository extends JpaRepository<TaskAuditEvent, UUID> {
    List<TaskAuditEvent> findByTaskIdOrderByCreatedAtAsc(UUID taskId);
}
