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

package com.tencent.ai.polaris.example.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tencent.ai.polaris.a2a.discovery.PolarisAgentCardResolver;
import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.spring.boot.mcp.PolarisMcpClientFactory;
import com.tencent.polaris.ai.api.core.SkillAPI;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.core.ProviderAPI;
import com.tencent.polaris.api.exception.ServerCodes;
import com.tencent.polaris.api.plugin.skill.SkillDownloadResponse;
import com.tencent.polaris.api.plugin.skill.SkillListResponse;
import com.tencent.polaris.api.plugin.skill.SkillResource;
import com.tencent.polaris.api.plugin.skill.SkillVersionInfo;
import com.tencent.polaris.api.pojo.DefaultInstance;
import com.tencent.polaris.api.pojo.DefaultServiceInstances;
import com.tencent.polaris.api.pojo.ExtendedMetadata;
import com.tencent.polaris.api.pojo.ServiceKey;
import com.tencent.polaris.api.rpc.InstanceRegisterRequest;
import com.tencent.polaris.api.rpc.InstanceRegisterResponse;
import com.tencent.polaris.api.rpc.InstancesResponse;

import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Toolkit;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real HarnessAgent, MCP and A2A HTTP endpoints; only the Polaris APIs are test doubles. */
class HarnessIntegrationTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void registeredHarnessDiscoversMcpLoadsSkillsAndDelegatesThroughA2a(boolean mounted) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(0);
        tomcat.setBaseDir(temp.resolve("tomcat").toString());
        var servletContext = tomcat.addContext("", temp.toString());
        var transport =
                HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(McpJsonMapper.getDefault())
                        .mcpEndpoint("/mcp")
                        .build();
        AtomicInteger mcpCalls = new AtomicInteger();
        McpSchema.Tool add =
                McpSchema.Tool.builder()
                        .name("add")
                        .description("Add integers")
                        .inputSchema(
                                McpJsonMapper.getDefault(),
                                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}}}")
                        .build();
        var mcp =
                McpServer.sync(transport)
                        .serverInfo("business-tools", "1.0.0")
                        .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                        .toolCall(
                                add,
                                (exchange, request) -> {
                                    mcpCalls.incrementAndGet();
                                    long sum =
                                            ((Number) request.arguments().get("a")).longValue()
                                                    + ((Number) request.arguments().get("b"))
                                                            .longValue();
                                    return McpSchema.CallToolResult.builder()
                                            .addTextContent(Long.toString(sum))
                                            .build();
                                })
                        .build();
        Tomcat.addServlet(servletContext, "mcp", transport).setAsyncSupported(true);
        servletContext.addServletMappingDecoded("/*", "mcp");
        tomcat.getConnector();
        tomcat.start();
        int a2aPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            a2aPort = socket.getLocalPort();
        }
        PolarisContextManager polaris = null;
        try (AutoCloseable closeMcp = mcp::close;
                ConfigurableApplicationContext app =
                        new SpringApplicationBuilder(
                                        PolarisHarnessApplication.class, PolarisFixtures.class)
                                .run(
                                        "--server.port=" + a2aPort,
                                        "--server.address=127.0.0.1",
                                        "--demo.workspace=" + temp.resolve("workspaces"),
                                        "--agentscope.polaris.skill.mounted.enabled=" + mounted,
                                        "--fixture.mcp-port="
                                                + tomcat.getConnector().getLocalPort())) {
            polaris = app.getBean(PolarisContextManager.class);
            AgentCard card = app.getBean(AgentScopeA2aServer.class).getAgentCard();
            assertThat(card.name()).isEqualTo("polaris-harness-agent");
            assertThat(app.getBean(PolarisAgentCardResolver.class).getAgentCard(card.name()))
                    .isEqualTo(card);
            assertThat(app.getBean("harnessToolkit", Toolkit.class).getToolNames())
                    .contains("add", "ask_agent");

            assertThat(ask(app, "/mcp add {\"a\":19,\"b\":23}")).contains("42");
            assertThat(ask(app, "/skill-list")).contains("integration-guide");
            assertThat(ask(app, "/skill integration-guide")).contains("POLARIS_SKILL_CONTENT");
            // Delegation discovers this same A2A service and performs a second real HTTP call.
            assertThat(ask(app, "/a2a polaris-harness-agent /mcp add {\"a\":20,\"b\":22}"))
                    .contains("42");
            assertThat(mcpCalls).hasValue(2);
            // A released request must not remove tools needed by the next request.
            assertThat(ask(app, "/mcp add {\"a\":1,\"b\":2}")).contains("3");
            assertThat(mcpCalls).hasValue(3);
            if (mounted) {
                verify(polaris.consumerAPI(), atLeastOnce()).getAllInstances(any());
                verify(polaris.skillAPI(), never()).listSkills(any());
            } else verify(polaris.skillAPI(), atLeastOnce()).listSkills(any());
            verify(polaris.skillAPI(), atLeastOnce()).downloadSkill(any());
            verify(polaris.providerAPI()).registerInstance(any());
            app.getBean(PolarisMcpClientFactory.class)
                    .release(app.getBean("harnessToolkit", Toolkit.class));
        } finally {
            tomcat.stop();
            tomcat.destroy();
        }
        verify(polaris.providerAPI()).deRegister(any());
    }

    private static String ask(ConfigurableApplicationContext app, String text) {
        A2aAgent agent =
                A2aAgent.builder()
                        .name("polaris-harness-agent")
                        .agentCardResolver(app.getBean(PolarisAgentCardResolver.class))
                        .build();
        Msg input =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(text).build())
                        .build();
        return agent.call(List.of(input)).block(Duration.ofSeconds(30)).getTextContent();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PolarisFixtures {
        @Bean
        PolarisContextManager polarisContextManager(Environment env) throws Exception {
            ConsumerAPI consumer = mock(ConsumerAPI.class);
            ProviderAPI provider = mock(ProviderAPI.class);
            SkillAPI skills = mock(SkillAPI.class);
            AtomicReference<InstanceRegisterRequest> registration = new AtomicReference<>();
            when(provider.registerInstance(any()))
                    .thenAnswer(
                            call -> {
                                registration.set(call.getArgument(0));
                                return new InstanceRegisterResponse("harness-test", false);
                            });
            when(consumer.getHealthyInstances(any()))
                    .thenAnswer(
                            call -> {
                                var request =
                                        (com.tencent.polaris.api.rpc.GetHealthyInstancesRequest)
                                                call.getArgument(0);
                                DefaultInstance instance = new DefaultInstance();
                                instance.setHealthy(true);
                                instance.setWeight(100);
                                instance.setId("fixture");
                                instance.setHost("127.0.0.1");
                                if ("business-mcp-server".equals(request.getService())) {
                                    instance.setPort(
                                            env.getRequiredProperty(
                                                    "fixture.mcp-port", Integer.class));
                                    instance.setProtocol("mcp-streamable-http");
                                    instance.setMetadata(Map.of("mcp-endpoint-path", "/mcp"));
                                } else {
                                    InstanceRegisterRequest registered = registration.get();
                                    instance.setPort(registered.getPort());
                                    instance.setMetadata(registered.getMetadata());
                                }
                                return new InstancesResponse(
                                        new DefaultServiceInstances(
                                                new ServiceKey("default", request.getService()),
                                                List.of(instance)),
                                        null,
                                        null);
                            });
            var skillMetadata = ExtendedMetadata.builder()
                    .type(ExtendedMetadata.ExtendedMetadataType.SKILL)
                    .agentSkill(com.tencent.polaris.api.pojo.AgentSkill.builder()
                            .name("default:integration-guide").version("1.0.0").build()).build();
            when(consumer.getAllInstances(any())).thenReturn(new InstancesResponse(
                    new DefaultServiceInstances(new ServiceKey("default", "polaris-harness-agent"), List.of()) {
                        @Override public List<ExtendedMetadata> getExtendedMetadata() {
                            return List.of(skillMetadata);
                        }
                    }, null, null));
            SkillResource resource = new SkillResource();
            resource.setName("integration-guide");
            SkillVersionInfo version = new SkillVersionInfo();
            version.setActiveVersion("1.0.0");
            resource.setVersionInfo(version);
            SkillListResponse list = new SkillListResponse();
            list.setCode(ServerCodes.EXECUTE_SUCCESS);
            list.setResources(List.of(resource));
            list.setTotal(1);
            when(skills.listSkills(any())).thenReturn(list);
            SkillDownloadResponse download = new SkillDownloadResponse();
            download.setCode(ServerCodes.EXECUTE_SUCCESS);
            download.setZipContent(skillZip());
            when(skills.downloadSkill(any())).thenReturn(download);
            PolarisContextManager context = mock(PolarisContextManager.class);
            when(context.getNamespace()).thenReturn("default");
            when(context.consumerAPI()).thenReturn(consumer);
            when(context.providerAPI()).thenReturn(provider);
            when(context.skillAPI()).thenReturn(skills);
            return context;
        }

        private static byte[] skillZip() throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("integration-guide/SKILL.md"));
                zip.write(
                        ("---\n"
                             + "name: integration-guide\n"
                             + "description: Polaris integration guide\n"
                             + "---\n"
                             + "POLARIS_SKILL_CONTENT: Call MCP add and delegate through A2A.\n")
                                .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return bytes.toByteArray();
        }
    }
}
