package com.nexus.hub.modules.task.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskPageResponse {

    private List<TaskResponse> items;
    private int currentPage;
    private int totalPages;
    private long totalItems;
    private int pageSize;

    public static TaskPageResponse fromPage(Page<TaskResponse> page) {
        return TaskPageResponse.builder()
                .items(page.getContent())
                .currentPage(page.getNumber())
                .totalPages(page.getTotalPages())
                .totalItems(page.getTotalElements())
                .pageSize(page.getSize())
                .build();
    }
}
