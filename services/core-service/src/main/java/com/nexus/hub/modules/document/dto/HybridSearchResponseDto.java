package com.nexus.hub.modules.document.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HybridSearchResponseDto {

    private String query;
    private int totalFound;
    private List<DocumentChunkDto> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentChunkDto {
        private String chunkId;
        private String documentId;
        private String content;
        private float score;
        private float rerankScore;
        private Map<String, String> metadata;
        private String citation;
    }
}
