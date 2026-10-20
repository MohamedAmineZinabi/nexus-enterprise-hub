package com.nexus.hub.modules.task.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_audit_events", schema = "core")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private AgentTask task;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "node_type", length = 50)
    private String nodeType;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(columnDefinition = "JSONB")
    private String payload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
