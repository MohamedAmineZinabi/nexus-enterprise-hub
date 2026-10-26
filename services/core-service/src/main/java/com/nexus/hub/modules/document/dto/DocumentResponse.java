package com.nexus.hub.modules.document.dto;

import com.nexus.hub.modules.task.entity.Document;
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
public class DocumentResponse {
    private UUID id;
    private String filename;
    private Long fileSize;
    private String mimeType;
    private String vectorCollection;
    private DocumentStatus status;
    private Integer chunksCount;
    private Instant createdAt;
    private Instant updatedAt;

    public static DocumentResponse from(Document doc) {
        return DocumentResponse.builder()
                .id(doc.getId())
                .filename(doc.getFilename())
                .fileSize(doc.getFileSize())
                .mimeType(doc.getMimeType())
                .vectorCollection(doc.getVectorCollection())
                .status(doc.getStatus())
                .chunksCount(doc.getChunksCount())
                .createdAt(doc.getCreatedAt())
                .updatedAt(doc.getUpdatedAt())
                .build();
    }
}
