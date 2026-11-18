/*
 * Tencent is pleased to support the open source community by making agentscope-extensions-polaris available.
 *
 * Copyright (C) 2026 Tencent. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.ai.polaris.example.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tencent.ai.polaris.mcp.client.PolarisMcpClientBuilder;
import com.tencent.ai.polaris.mcp.client.PolarisMcpClientWrapper;
import com.tencent.ai.polaris.mcp.discovery.PolarisMcpServerManager;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.pojo.DefaultInstance;
import com.tencent.polaris.api.pojo.DefaultServiceInstances;
import com.tencent.polaris.api.pojo.ServiceKey;
import com.tencent.polaris.api.rpc.InstancesResponse;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Real Spring AI WebMVC server; discovery data follows Spring AI Polaris's published format. */
class SpringAiMcpInteropTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = AgentscopeAutoConfiguration.class)
    static class Server {
        @Bean
        List<McpStatelessServerFeatures.SyncToolSpecification> statelessTools() {
            McpSchema.Tool tool =
                    McpSchema.Tool.builder()
                            .name("lookup")
                            .description("Lookup")
                            .inputSchema(
                                    McpJsonMapper.getDefault(),
                                    "{\"type\":\"object\",\"properties\":{}}")
                            .build();
            return List.of(
                    McpStatelessServerFeatures.SyncToolSpecification.builder()
                            .tool(tool)
                            .callHandler(
                                    (context, request) ->
                                            McpSchema.CallToolResult.builder()
                                                    .addTextContent("spring-ai-result")
                                                    .build())
                            .build());
        }

        @Bean
        List<McpServerFeatures.SyncToolSpecification> tools() {
            McpSchema.Tool tool =
                    McpSchema.Tool.builder()
                            .name("lookup")
                            .description("Lookup")
                            .inputSchema(
                                    McpJsonMapper.getDefault(),
                                    "{\"type\":\"object\",\"properties\":{}}")
                            .build();
            return List.of(
                    McpServerFeatures.SyncToolSpecification.builder()
                            .tool(tool)
                            .callHandler(
                                    (exchange, request) ->
                                            McpSchema.CallToolResult.builder()
                                                    .addTextContent("spring-ai-result")
                                                    .build())
                            .build());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSE", "STREAMABLE", "STATELESS"})
    void callsSpringAiToolsFromPolarisMetadata(String mode) {
        SpringApplication application = new SpringApplication(Server.class);
        application.setDefaultProperties(
                Map.ofEntries(
                        Map.entry("server.port", "0"),
                        Map.entry("server.servlet.context-path", "/context"),
                        Map.entry("server.shutdown", "immediate"),
                        Map.entry("spring.ai.mcp.server.protocol", mode),
                        Map.entry("spring.ai.mcp.server.type", "SYNC"),
                        Map.entry("spring.ai.mcp.server.sse-endpoint", "/custom-events"),
                        Map.entry("spring.ai.mcp.server.sse-message-endpoint", "/messages"),
                        Map.entry("spring.ai.mcp.server.base-url", "/context"),
                        Map.entry(
                                "spring.ai.mcp.server.streamable-http.mcp-endpoint", "/custom-mcp"),
                        Map.entry("agentscope.polaris.enabled", "false"),
                        Map.entry("spring.main.banner-mode", "off")));
        try (var context =
                (ServletWebServerApplicationContext)
                        application.run(
                                "--agentscope.polaris.enabled=false",
                                "--spring.main.web-application-type=servlet")) {
            DefaultInstance instance = new DefaultInstance();
            instance.setId("spring-ai");
            instance.setHost("127.0.0.1");
            instance.setPort(context.getWebServer().getPort());
            instance.setHealthy(true);
            instance.setWeight(100);
            instance.setVersion("1.0.0");
            instance.setProtocol("SSE".equals(mode) ? "mcp-sse" : "mcp-streamable-http");
            instance.setMetadata(
                    Map.of(
                            "mcp-server-name",
                            "tools",
                            "mcp-protocol-version",
                            "2024-11-05,2025-03-26,2025-06-18",
                            "mcp-endpoint-path",
                            "/context" + ("SSE".equals(mode) ? "/custom-events" : "/custom-mcp")));
            ConsumerAPI consumer = mock(ConsumerAPI.class);
            when(consumer.getHealthyInstances(any()))
                    .thenReturn(
                            new InstancesResponse(
                                    new DefaultServiceInstances(
                                            new ServiceKey("default", "tools"), List.of(instance)),
                                    null,
                                    null));
            try (PolarisMcpServerManager manager =
                            new PolarisMcpServerManager(consumer, "default");
                    PolarisMcpClientWrapper client =
                            PolarisMcpClientBuilder.create("tools", manager).build()) {
                Toolkit toolkit = new Toolkit();
                toolkit.registerMcpClient(client).block(Duration.ofSeconds(20));
                assertTrue(toolkit.getToolNames().contains("lookup"));
                var result =
                        toolkit.callTool(
                                        ToolCallParam.builder()
                                                .toolUseBlock(
                                                        ToolUseBlock.builder()
                                                                .id("spring-ai-call")
                                                                .name("lookup")
                                                                .input(Map.of())
                                                                .content("{}")
                                                                .build())
                                                .input(Map.of())
                                                .build())
                                .block(Duration.ofSeconds(10));
                assertNotNull(result);
                assertEquals("spring-ai-result", ((TextBlock) result.getOutput().get(0)).getText());
            }
        }
    }
}
