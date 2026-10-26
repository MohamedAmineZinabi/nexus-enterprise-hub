package com.nexus.hub.modules.document.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HybridSearchRequestDto {

    @NotBlank(message = "Search query cannot be blank")
    private String query;

    @Builder.Default
    private Integer topK = 5;

    @Builder.Default
    private Float alpha = 0.7f;

    @Builder.Default
    private Boolean rerank = true;

    private String collectionName;

    private Map<String, String> metadataFilters;
}
