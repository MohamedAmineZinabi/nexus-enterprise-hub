package com.nexus.hub.modules.agent.controller;

import com.nexus.hub.common.dto.ApiResponse;
import com.nexus.hub.grpc.ai.v1.ListMcpToolsResponse;
import com.nexus.hub.grpc.client.AgentGrpcClient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentGrpcClient agentGrpcClient;

    @GetMapping("/mcp-tools")
    public ResponseEntity<ApiResponse<McpToolsResponseDto>> listMcpTools() {
        log.info("Request received to discover active MCP tools from AI service");
        ListMcpToolsResponse response = agentGrpcClient.listMcpTools();

        List<McpToolDto> tools = response.getToolsList().stream()
                .map(t -> McpToolDto.builder()
                        .name(t.getName())
                        .description(t.getDescription())
                        .inputSchemaJson(t.getInputSchemaJson())
                        .serverOrigin(t.getServerOrigin())
                        .build())
                .toList();

        McpToolsResponseDto dto = McpToolsResponseDto.builder()
                .totalTools(response.getTotalTools())
                .tools(tools)
                .build();

        return ResponseEntity.ok(ApiResponse.success(dto, "Active MCP tools discovered successfully"));
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class McpToolsResponseDto {
        private int totalTools;
        private List<McpToolDto> tools;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class McpToolDto {
        private String name;
        private String description;
        private String inputSchemaJson;
        private String serverOrigin;
    }
}
