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

package com.tencent.ai.polaris.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.tencent.ai.polaris.mcp.client.PolarisMcpClientBuilder;
import com.tencent.ai.polaris.mcp.client.PolarisMcpClientWrapper;
import com.tencent.ai.polaris.mcp.discovery.PolarisMcpServerManager;
import com.tencent.polaris.api.pojo.DefaultInstance;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Real HTTP transport/SDK/tool binding; Polaris instance API is a controlled test double. */
class PolarisMcpHttpIntegrationTest {
    @ParameterizedTest
    @CsvSource({
        "STREAMABLE,true",
        "STREAMABLE,false",
        "SSE,true",
        "SSE,false",
        "STATELESS,true",
        "STATELESS,false"
    })
    void discoversAndCallsRealMcpServer(String mode, boolean async) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(0);
        tomcat.setBaseDir(Files.createTempDirectory("polaris-mcp-tomcat").toString());
        Context context =
                tomcat.addContext(
                        "/context", Files.createTempDirectory("polaris-mcp-docbase").toString());
        List<String> auth = new CopyOnWriteArrayList<>();
        List<String> sessions = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger credentialLoads = new AtomicInteger();
        FilterDef filter = new FilterDef();
        filter.setFilterName("capture");
        filter.setFilter(
                new Filter() {
                    @Override
                    public void doFilter(
                            ServletRequest request, ServletResponse response, FilterChain chain)
                            throws IOException, ServletException {
                        HttpServletRequest http = (HttpServletRequest) request;
                        auth.add(String.valueOf(http.getHeader("Authorization")));
                        if (http.getHeader("Mcp-Session-Id") != null)
                            sessions.add(http.getHeader("Mcp-Session-Id"));
                        chain.doFilter(request, response);
                    }
                });
        filter.setAsyncSupported("true");
        context.addFilterDef(filter);
        FilterMap mapping = new FilterMap();
        mapping.setFilterName("capture");
        mapping.addURLPattern("/*");
        context.addFilterMap(mapping);
        String path = "SSE".equals(mode) ? "/events" : "/custom-mcp";
        McpSchema.Tool tool = PolarisMcpDiscoveryTest.TOOL;
        HttpServlet servlet;
        AutoCloseable server;
        if ("SSE".equals(mode)) {
            HttpServletSseServerTransportProvider transport =
                    HttpServletSseServerTransportProvider.builder()
                            .jsonMapper(McpJsonMapper.getDefault())
                            .baseUrl("/context")
                            .sseEndpoint(path)
                            .messageEndpoint("/messages")
                            .build();
            var nativeServer =
                    McpServer.sync(transport)
                            .serverInfo("test", "1.0.0")
                            .capabilities(
                                    McpSchema.ServerCapabilities.builder().tools(true).build())
                            .toolCall(tool, (exchange, request) -> result(calls))
                            .build();
            server = nativeServer::close;
            servlet = transport;
        } else if ("STATELESS".equals(mode)) {
            HttpServletStatelessServerTransport transport =
                    HttpServletStatelessServerTransport.builder()
                            .jsonMapper(McpJsonMapper.getDefault())
                            .messageEndpoint(path)
                            .build();
            var nativeServer =
                    McpServer.sync(transport)
                            .serverInfo("test", "1.0.0")
                            .capabilities(
                                    McpSchema.ServerCapabilities.builder().tools(true).build())
                            .toolCall(tool, (exchange, request) -> result(calls))
                            .build();
            server = nativeServer::close;
            servlet = transport;
        } else {
            HttpServletStreamableServerTransportProvider transport =
                    HttpServletStreamableServerTransportProvider.builder()
                            .jsonMapper(McpJsonMapper.getDefault())
                            .mcpEndpoint(path)
                            .build();
            var nativeServer =
                    McpServer.sync(transport)
                            .serverInfo("test", "1.0.0")
                            .capabilities(
                                    McpSchema.ServerCapabilities.builder().tools(true).build())
                            .toolCall(tool, (exchange, request) -> result(calls))
                            .build();
            server = nativeServer::close;
            servlet = transport;
        }
        Tomcat.addServlet(context, "mcp", servlet).setAsyncSupported(true);
        context.addServletMappingDecoded("/*", "mcp");
        tomcat.getConnector();
        tomcat.start();
        DefaultInstance instance =
                PolarisMcpDiscoveryTest.instance(
                        "a",
                        "SSE".equals(mode) ? "mcp-sse" : "mcp-streamable-http",
                        "/context" + path);
        instance.setPort(tomcat.getConnector().getLocalPort());
        PolarisMcpDiscoveryTest.Registry registry = new PolarisMcpDiscoveryTest.Registry(instance);
        try (server;
                PolarisMcpServerManager manager =
                        new PolarisMcpServerManager(registry.consumer, "default");
                PolarisMcpClientWrapper client =
                        PolarisMcpClientBuilder.create("tools", manager)
                                .asyncClient(async)
                                .header("Authorization", "Bearer fixed")
                                .headerProvider(
                                        () ->
                                                Map.of(
                                                        "Authorization",
                                                        "Bearer connection-"
                                                                + credentialLoads
                                                                        .incrementAndGet()))
                                .build()) {
            Toolkit toolkit = new Toolkit();
            toolkit.registerMcpClient(client).block(Duration.ofSeconds(20));
            assertTrue(toolkit.getToolNames().contains("lookup"));
            assertEquals(
                    List.of("lookup"),
                    client.listTools().block().stream().map(McpSchema.Tool::name).toList());
            for (int i = 0; i < 2; i++) {
                McpSchema.CallToolResult result =
                        client.callTool("lookup", Map.of()).block(Duration.ofSeconds(10));
                assertEquals("result", ((McpSchema.TextContent) result.content().get(0)).text());
            }
            var result =
                    toolkit.callTool(
                                    ToolCallParam.builder()
                                            .toolUseBlock(
                                                    ToolUseBlock.builder()
                                                            .id("lookup-test")
                                                            .name("lookup")
                                                            .input(Map.of())
                                                            .content("{}")
                                                            .build())
                                            .input(Map.of())
                                            .build())
                            .block(Duration.ofSeconds(10));
            assertNotNull(result);
            assertEquals("result", ((TextBlock) result.getOutput().get(0)).getText());
            assertEquals(3, calls.get());
            assertFalse(auth.isEmpty());
            assertEquals(1, credentialLoads.get());
            assertTrue(auth.stream().allMatch("Bearer connection-1"::equals));
            if ("STREAMABLE".equals(mode)) assertFalse(sessions.isEmpty());
            if ("STATELESS".equals(mode)) assertTrue(sessions.isEmpty());
            if ("STREAMABLE".equals(mode)) assertEquals(1, sessions.stream().distinct().count());
            // block() can return before doFinally releases the in-flight call count.
            // Retry idle recycling until cleanup finishes; an unreleased call still fails the test.
            long recycleDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            do {
                client.recycleIdle(Duration.ZERO);
                if (manager.subscriptionCount() == 0) break;
                Thread.sleep(10);
            } while (System.nanoTime() < recycleDeadline);
            assertEquals(0, manager.subscriptionCount());
            assertNotNull(
                    toolkit.callTool(
                                    ToolCallParam.builder()
                                            .toolUseBlock(
                                                    ToolUseBlock.builder()
                                                            .id("lookup-after-idle")
                                                            .name("lookup")
                                                            .input(Map.of())
                                                            .content("{}")
                                                            .build())
                                            .input(Map.of())
                                            .build())
                            .block(Duration.ofSeconds(15)));
            assertEquals(4, calls.get());
            assertEquals(2, credentialLoads.get());
            assertTrue(auth.contains("Bearer connection-2"));
            assertTrue(
                    auth.stream()
                            .allMatch(
                                    value ->
                                            "Bearer connection-1".equals(value)
                                                    || "Bearer connection-2".equals(value)));
            if ("STREAMABLE".equals(mode)) assertEquals(2, sessions.stream().distinct().count());
        } finally {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private static McpSchema.CallToolResult result(AtomicInteger calls) {
        calls.incrementAndGet();
        return McpSchema.CallToolResult.builder().addTextContent("result").isError(false).build();
    }
}
