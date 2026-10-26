package com.nexus.hub.modules.document.dto;

import com.nexus.hub.modules.task.entity.DocumentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadResponse {
    private UUID documentId;
    private String fileName;
    private DocumentStatus status;
    private Integer chunksCreated;
    private String vectorCollection;
    private Instant createdAt;
    private String message;
}
