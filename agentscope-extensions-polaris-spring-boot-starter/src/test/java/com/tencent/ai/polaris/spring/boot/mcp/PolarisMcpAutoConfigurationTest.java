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

package com.tencent.ai.polaris.spring.boot.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.mcp.client.PolarisMcpClientBuilder;
import com.tencent.ai.polaris.mcp.client.PolarisMcpClientWrapper;
import com.tencent.ai.polaris.mcp.discovery.PolarisMcpServerManager;
import com.tencent.ai.polaris.spring.boot.config.mcp.AgentScopePolarisMcpProperties;
import com.tencent.ai.polaris.spring.boot.config.mcp.AgentScopePolarisMcpProperties.Client;
import com.tencent.ai.polaris.spring.boot.skill.AgentscopePolarisSkillAgentAutoConfiguration;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.pojo.DefaultInstance;
import com.tencent.polaris.api.pojo.DefaultServiceInstances;
import com.tencent.polaris.api.pojo.ServiceKey;
import com.tencent.polaris.api.rpc.InstancesResponse;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class PolarisMcpAutoConfigurationTest {
    static final McpSchema.Tool TOOL =
            McpSchema.Tool.builder()
                    .name("lookup")
                    .description("Lookup")
                    .inputSchema(
                            McpJsonMapper.getDefault(), "{\"type\":\"object\",\"properties\":{}}")
                    .build();
    final ConsumerAPI consumer = mock(ConsumerAPI.class);
    final PolarisContextManager context = mock(PolarisContextManager.class);
    final AtomicInteger creations = new AtomicInteger();
    final AtomicInteger closes = new AtomicInteger();

    PolarisMcpAutoConfigurationTest() {
        DefaultInstance instance = new DefaultInstance();
        instance.setId("a");
        instance.setHost("127.0.0.1");
        instance.setPort(19001);
        instance.setWeight(100);
        instance.setHealthy(true);
        instance.setProtocol("mcp-streamable-http");
        instance.setMetadata(Map.of());
        when(consumer.getHealthyInstances(any()))
                .thenReturn(
                        new InstancesResponse(
                                new DefaultServiceInstances(
                                        new ServiceKey("default", "tools"), List.of(instance)),
                                null,
                                null));
        when(context.consumerAPI()).thenReturn(consumer);
        when(context.getNamespace()).thenReturn("default");
    }

    ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                AgentscopeMcpPolarisClientAutoConfiguration.class,
                                AgentscopeMcpPolarisToolkitAutoConfiguration.class,
                                AgentscopeAutoConfiguration.class))
                .withBean(PolarisContextManager.class, () -> context)
                .withBean(Model.class, () -> mock(Model.class))
                .withBean(
                        PolarisMcpClientFactory.class,
                        () -> factory(),
                        definition -> definition.setDestroyMethodName("close"))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.polaris.mcp.discovery.clients[0].service-name=tools");
    }

    PolarisMcpClientFactory factory() {
        AgentScopePolarisMcpProperties props = new AgentScopePolarisMcpProperties();
        Client client = new Client();
        client.setServiceName("tools");
        props.getDiscovery().setClients(List.of(client));
        PolarisMcpServerManager manager = new PolarisMcpServerManager(consumer, "default");
        return new PolarisMcpClientFactory(manager, props) {
            @Override
            protected PolarisMcpClientWrapper createClient(Client settings) {
                creations.incrementAndGet();
                return PolarisMcpClientBuilder.create(settings.getServiceName(), manager)
                        .clientFactory(
                                endpoint ->
                                        new NativeClient() {
                                            @Override
                                            public void close() {
                                                closes.incrementAndGet();
                                            }
                                        })
                        .build();
            }

            @Override
            public void close() {
                super.close();
                manager.close();
            }
        };
    }

    static class NativeClient extends McpClientWrapper {
        NativeClient() {
            super("native");
        }

        @Override
        public Mono<Void> initialize() {
            initialized = true;
            return Mono.empty();
        }

        @Override
        public Mono<List<McpSchema.Tool>> listTools() {
            return Mono.just(List.of(TOOL));
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> args) {
            return Mono.just(McpSchema.CallToolResult.builder().addTextContent("ok").build());
        }

        @Override
        public void close() {}
    }

    @Test
    void attachesToEachPrototypeAgentWithoutSharingClients() {
        runner().withPropertyValues("agentscope.polaris.mcp.discovery.attach-to-toolkit=true")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            ReActAgent first = ctx.getBean(ReActAgent.class),
                                    second = ctx.getBean(ReActAgent.class);
                            assertThat(first.getToolkit()).isNotSameAs(second.getToolkit());
                            assertThat(first.getToolkit().getToolNames()).contains("lookup");
                            assertThat(second.getToolkit().getToolNames()).contains("lookup");
                            assertThat(creations).hasValue(2);
                            ctx.getBean(PolarisMcpClientFactory.class).release(first.getToolkit());
                            assertThat(first.getToolkit().getToolNames()).doesNotContain("lookup");
                            assertThat(second.getToolkit().getToolNames()).contains("lookup");
                        });
    }

    @Test
    void noConfiguredServicesDoesNotInitializeTheSharedSdk() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                AgentscopeMcpPolarisClientAutoConfiguration.class,
                                AgentscopeMcpPolarisToolkitAutoConfiguration.class,
                                AgentscopeAutoConfiguration.class))
                .withBean(PolarisContextManager.class, () -> context)
                .withBean(Model.class, () -> mock(Model.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.polaris.mcp.discovery.attach-to-toolkit=true")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx.getBean(ReActAgent.class).getToolkit().getToolNames())
                                    .isEmpty();
                            verify(context, never()).consumerAPI();
                            assertThat(
                                            ctx.getBean(PolarisMcpServerManager.class)
                                                    .subscriptionCount())
                                    .isZero();
                        });
    }

    @Test
    void releaseAllowsTheOriginalToolkitToBeBoundAgain() {
        try (PolarisMcpClientFactory factory = factory()) {
            Toolkit toolkit = new Toolkit();
            factory.bind(toolkit);
            Toolkit copy = toolkit.copy();
            factory.retain(copy);
            factory.release(copy);
            assertThat(toolkit.getToolNames()).contains("lookup");
            assertThat(copy.getToolNames()).isEmpty();
            assertLookup(toolkit);
            assertThat(closes).hasValue(0);
            factory.release(toolkit);
            assertThat(toolkit.getToolNames()).isEmpty();
            assertThat(closes).hasValue(1);
            factory.bind(toolkit);
            assertThat(toolkit.getToolNames()).contains("lookup");
            assertThat(creations).hasValue(2);
            factory.release(toolkit);
            assertThat(closes).hasValue(2);
        }
    }

    @Test
    void releasingOneCopyPreservesOtherOwnersAndClosesAfterTheLastRelease() {
        try (PolarisMcpClientFactory factory = factory()) {
            Toolkit original = new Toolkit();
            factory.bind(original);
            Toolkit first = original.copy(), second = original.copy();
            factory.retain(first);
            factory.retain(second);
            factory.retain(second);

            factory.release(first);
            factory.release(first);
            assertThat(first.getToolNames()).isEmpty();
            assertLookup(original);
            assertLookup(second);
            assertThat(closes).hasValue(0);

            factory.release(original);
            assertThat(original.getToolNames()).isEmpty();
            assertLookup(second);
            assertThat(closes).hasValue(0);

            factory.release(second);
            factory.release(second);
            assertThat(second.getToolNames()).isEmpty();
            assertThat(creations).hasValue(1);
            assertThat(closes).hasValue(1);
        }
        assertThat(closes).hasValue(1);
    }

    @Test
    void rebindingReleasedOriginalDoesNotLetOldCopiesCloseItsNewClient() {
        try (PolarisMcpClientFactory factory = factory()) {
            Toolkit original = new Toolkit();
            factory.bind(original);
            Toolkit copy = original.copy();
            factory.retain(copy);
            factory.release(original);
            factory.bind(original);
            assertThat(creations).hasValue(2);
            assertLookup(original);
            assertLookup(copy);

            factory.release(copy);
            assertThat(closes).hasValue(1);
            assertLookup(original);
            factory.release(original);
            assertThat(closes).hasValue(2);
        }
    }

    @Test
    void concurrentReleasesPreserveTheRemainingOwner() throws Exception {
        try (PolarisMcpClientFactory factory = factory()) {
            Toolkit original = new Toolkit();
            factory.bind(original);
            Toolkit first = original.copy(), second = original.copy(), remaining = original.copy();
            factory.retain(first);
            factory.retain(second);
            factory.retain(remaining);
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<?>[] releases =
                    List.of(original, first, second).stream()
                            .map(
                                    toolkit ->
                                            CompletableFuture.runAsync(
                                                    () -> {
                                                        try {
                                                            if (!start.await(5, TimeUnit.SECONDS))
                                                                throw new AssertionError(
                                                                        "Release did not start");
                                                        } catch (InterruptedException e) {
                                                            Thread.currentThread().interrupt();
                                                            throw new AssertionError(e);
                                                        }
                                                        factory.release(toolkit);
                                                    }))
                            .toArray(CompletableFuture<?>[]::new);
            start.countDown();
            CompletableFuture.allOf(releases).get(5, TimeUnit.SECONDS);
            assertThat(original.getToolNames()).isEmpty();
            assertThat(first.getToolNames()).isEmpty();
            assertThat(second.getToolNames()).isEmpty();
            assertLookup(remaining);
            assertThat(closes).hasValue(0);
            factory.release(remaining);
            assertThat(closes).hasValue(1);
        }
    }

    private static void assertLookup(Toolkit toolkit) {
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
                        .block(Duration.ofSeconds(5));
        assertThat(result).isNotNull();
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).isEqualTo("ok");
    }

    @Configuration(proxyBeanMethods = false)
    static class SharedToolkit {
        @Bean("agentscopeToolkit")
        Toolkit toolkit() {
            return new Toolkit();
        }
    }

    @Test
    void singletonToolkitCopiesRemainUsableWhenAnotherAgentIsReleased() {
        runner().withUserConfiguration(SharedToolkit.class)
                .withPropertyValues("agentscope.polaris.mcp.discovery.attach-to-toolkit=true")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            ReActAgent first = ctx.getBean(ReActAgent.class),
                                    second = ctx.getBean(ReActAgent.class);
                            PolarisMcpClientFactory factory =
                                    ctx.getBean(PolarisMcpClientFactory.class);
                            Toolkit original = ctx.getBean("agentscopeToolkit", Toolkit.class);
                            assertThat(creations).hasValue(1);
                            factory.release(first.getToolkit());
                            assertThat(first.getToolkit().getToolNames()).isEmpty();
                            assertLookup(second.getToolkit());
                            assertLookup(original);
                            ReActAgent third = ctx.getBean(ReActAgent.class);
                            assertLookup(third.getToolkit());
                            factory.release(second.getToolkit());
                            factory.release(third.getToolkit());
                            assertThat(closes).hasValue(0);
                            factory.release(original);
                            assertThat(closes).hasValue(1);
                        });
        assertThat(closes).hasValue(1);
    }

    @Test
    void aServerWithoutToolsDoesNotRetainAnInvisibleSession() {
        AgentScopePolarisMcpProperties props = new AgentScopePolarisMcpProperties();
        Client config = new Client();
        config.setServiceName("tools");
        props.getDiscovery().setClients(List.of(config));
        try (PolarisMcpServerManager manager = new PolarisMcpServerManager(consumer, "default");
                PolarisMcpClientFactory factory =
                        new PolarisMcpClientFactory(manager, props) {
                            @Override
                            protected PolarisMcpClientWrapper createClient(Client settings) {
                                return PolarisMcpClientBuilder.create("tools", manager)
                                        .clientFactory(
                                                endpoint ->
                                                        new NativeClient() {
                                                            @Override
                                                            public Mono<List<McpSchema.Tool>>
                                                                    listTools() {
                                                                return Mono.just(List.of());
                                                            }
                                                        })
                                        .build();
                            }
                        }) {
            Toolkit toolkit = new Toolkit();
            factory.bind(toolkit);
            assertThat(toolkit.getToolNames()).isEmpty();
            assertThat(manager.subscriptionCount()).isZero();
        }
    }

    @Test
    void leavesDefaultToolkitUntouchedWhenAttachDisabled() {
        runner().run(
                        ctx -> {
                            assertThat(ctx.getBean(ReActAgent.class).getToolkit().getToolNames())
                                    .isEmpty();
                            assertThat(creations).hasValue(0);
                            verify(consumer, never()).watchService(any());
                        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomToolkit {
        @Bean("businessToolkit")
        @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
        Toolkit toolkit() {
            return new Toolkit();
        }
    }

    @Test
    void bindsExplicitCustomPrototypeToolkit() {
        runner().withUserConfiguration(CustomToolkit.class)
                .withPropertyValues(
                        "agentscope.polaris.mcp.discovery.attach-to-toolkit=true",
                        "agentscope.polaris.mcp.discovery.toolkit-bean-name=businessToolkit")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx.getBean("businessToolkit", Toolkit.class).getToolNames())
                                    .contains("lookup");
                            assertThat(ctx.getBean("businessToolkit", Toolkit.class).getToolNames())
                                    .contains("lookup");
                            assertThat(creations).hasValue(2);
                        });
    }

    @Test
    void requiresTargetForCustomToolkitRatherThanSilentlySkippingBinding() {
        runner().withUserConfiguration(CustomToolkit.class)
                .withPropertyValues("agentscope.polaris.mcp.discovery.attach-to-toolkit=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void bindsTypedPropertiesAndDefaults() {
        runner().withPropertyValues(
                        "agentscope.polaris.mcp.discovery.clients[0].request-timeout=45s",
                        "agentscope.polaris.mcp.discovery.clients[0].headers.Authorization=Bearer"
                                + " test")
                .run(
                        ctx -> {
                            Client client =
                                    ctx.getBean(AgentScopePolarisMcpProperties.class)
                                            .getDiscovery()
                                            .getClients()
                                            .get(0);
                            assertThat(client.getRequestTimeout()).hasSeconds(45);
                            assertThat(client.isAsyncClient()).isTrue();
                            assertThat(client.getDefaultScheme()).isEqualTo("http");
                            assertThat(client.getHeaders())
                                    .containsEntry("Authorization", "Bearer test");
                        });
    }

    @Test
    void allFeatureSwitchesDisableDiscoveryEvenWithASharedContext() {
        for (String disabled :
                List.of(
                        "agentscope.polaris.enabled=false",
                        "agentscope.polaris.mcp.enabled=false",
                        "agentscope.polaris.mcp.discovery.enabled=false")) {
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    AgentscopeMcpPolarisClientAutoConfiguration.class,
                                    AgentscopeMcpPolarisToolkitAutoConfiguration.class))
                    .withBean(PolarisContextManager.class, () -> context)
                    .withPropertyValues(disabled)
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(PolarisMcpClientFactory.class));
        }
    }

    @Test
    void coexistsWithSkillAgentFactory() {
        AgentSkillRepository repository = mock(AgentSkillRepository.class);
        when(repository.getAllSkills())
                .thenReturn(
                        List.of(
                                AgentSkill.builder()
                                        .name("weather")
                                        .description("Weather")
                                        .skillContent("# Weather")
                                        .source("test")
                                        .build()));
        runner().withConfiguration(
                        AutoConfigurations.of(AgentscopePolarisSkillAgentAutoConfiguration.class))
                .withBean(AgentSkillRepository.class, () -> repository)
                .withPropertyValues("agentscope.polaris.mcp.discovery.attach-to-toolkit=true")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx.getBean(ReActAgent.class).getToolkit().getToolNames())
                                    .contains("lookup", "load_skill_through_path");
                        });
    }

    @Test
    void duplicateToolsFailBeforeChangingToolkit() {
        PolarisMcpServerManager manager = new PolarisMcpServerManager(consumer, "default");
        AgentScopePolarisMcpProperties props = new AgentScopePolarisMcpProperties();
        Client a = new Client();
        a.setServiceName("tools-a");
        Client b = new Client();
        b.setServiceName("tools-b");
        props.getDiscovery().setClients(List.of(a, b));
        try (manager;
                PolarisMcpClientFactory factory =
                        new PolarisMcpClientFactory(manager, props) {
                            @Override
                            protected PolarisMcpClientWrapper createClient(Client client) {
                                return PolarisMcpClientBuilder.create(
                                                client.getServiceName(), manager)
                                        .clientFactory(endpoint -> new NativeClient())
                                        .build();
                            }
                        }) {
            Toolkit toolkit = new Toolkit();
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, () -> factory.bind(toolkit));
            assertThat(toolkit.getToolNames()).isEmpty();
            assertThat(manager.subscriptionCount()).isZero();
        }
    }

    @Test
    void bindsAllConsumerPoliciesAndRejectsInvalidSettingsBeforeDiscovery() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(AgentscopeMcpPolarisClientAutoConfiguration.class))
                .withBean(PolarisContextManager.class, () -> context)
                .withPropertyValues(
                        "agentscope.polaris.mcp.discovery.attach-to-toolkit=true",
                        "agentscope.polaris.mcp.discovery.toolkit-bean-name=businessToolkit",
                        "agentscope.polaris.mcp.discovery.idle-timeout=2m",
                        "agentscope.polaris.mcp.discovery.clients[0].service-name=tools",
                        "agentscope.polaris.mcp.discovery.clients[0].name=business",
                        "agentscope.polaris.mcp.discovery.clients[0].namespace=demo",
                        "agentscope.polaris.mcp.discovery.clients[0].version=v2",
                        "agentscope.polaris.mcp.discovery.clients[0].async-client=false",
                        "agentscope.polaris.mcp.discovery.clients[0].request-timeout=4s",
                        "agentscope.polaris.mcp.discovery.clients[0].initialization-timeout=3s",
                        "agentscope.polaris.mcp.discovery.clients[0].default-scheme=https",
                        "agentscope.polaris.mcp.discovery.clients[0].headers.Authorization=Bearer"
                            + " test")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            var properties = ctx.getBean(AgentScopePolarisMcpProperties.class);
                            var discovery = properties.getDiscovery();
                            assertThat(properties.isEnabled()).isTrue();
                            assertThat(discovery.isEnabled()).isTrue();
                            assertThat(discovery.isAttachToToolkit()).isTrue();
                            assertThat(discovery.getToolkitBeanName()).isEqualTo("businessToolkit");
                            assertThat(discovery.getIdleTimeout()).isEqualTo(Duration.ofMinutes(2));
                            Client client = discovery.getClients().get(0);
                            assertThat(client.getName()).isEqualTo("business");
                            assertThat(client.getNamespace()).isEqualTo("demo");
                            assertThat(client.getVersion()).isEqualTo("v2");
                            assertThat(client.isAsyncClient()).isFalse();
                            assertThat(client.getRequestTimeout()).hasSeconds(4);
                            assertThat(client.getInitializationTimeout()).hasSeconds(3);
                            assertThat(client.getDefaultScheme()).isEqualTo("https");
                            assertThat(client.getHeaders())
                                    .containsEntry("Authorization", "Bearer test");
                            verify(context, never()).consumerAPI();
                        });
        for (String invalid :
                List.of(
                        "clients[0].service-name= ",
                        "clients[0].request-timeout=0s",
                        "clients[0].initialization-timeout=-1s",
                        "clients[0].default-scheme=ftp",
                        "idle-timeout=0s",
                        "idle-timeout=-1s",
                        "clients[1].service-name=tools")) {
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    AgentscopeMcpPolarisClientAutoConfiguration.class))
                    .withBean(PolarisContextManager.class, () -> context)
                    .withPropertyValues(
                            "agentscope.polaris.mcp.discovery.clients[0].service-name=tools",
                            "agentscope.polaris.mcp.discovery." + invalid)
                    .run(ctx -> assertThat(ctx).hasFailed());
        }
        verify(context, never()).consumerAPI();
    }

    @Test
    void autoConfigurationHonorsUserFactoriesAndDoesNotBindAnAmbiguousToolkit() {
        try (PolarisMcpClientFactory factory = factory()) {
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    AgentscopeMcpPolarisClientAutoConfiguration.class))
                    .withBean(PolarisContextManager.class, () -> context)
                    .withBean(PolarisMcpClientFactory.class, () -> factory)
                    .run(
                            ctx ->
                                    assertThat(ctx.getBean(PolarisMcpClientFactory.class))
                                            .isSameAs(factory));
        }
        runner().withUserConfiguration(TwoToolkits.class)
                .withPropertyValues("agentscope.polaris.mcp.discovery.attach-to-toolkit=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoToolkits {
        @Bean
        Toolkit agentscopeToolkit() {
            return new Toolkit();
        }

        @Bean
        Toolkit otherToolkit() {
            return new Toolkit();
        }
    }

    @Test
    void emptyToolServersAndRepeatedBindingDoNotLeakInvisibleConnections() {
        try (var manager = new PolarisMcpServerManager(consumer, "default")) {
            var properties = new AgentScopePolarisMcpProperties();
            Client config = new Client();
            config.setServiceName("tools");
            properties.getDiscovery().setClients(List.of(config));
            try (var empty =
                    new PolarisMcpClientFactory(manager, properties) {
                        @Override
                        protected PolarisMcpClientWrapper createClient(Client client) {
                            return PolarisMcpClientBuilder.create("tools", manager)
                                    .clientFactory(
                                            endpoint ->
                                                    new NativeClient() {
                                                        @Override
                                                        public Mono<List<McpSchema.Tool>>
                                                                listTools() {
                                                            return Mono.just(List.of());
                                                        }

                                                        @Override
                                                        public void close() {
                                                            closes.incrementAndGet();
                                                        }
                                                    })
                                    .build();
                        }
                    }) {
                Toolkit toolkit = new Toolkit();
                empty.bind(toolkit);
                assertThat(toolkit.getToolNames()).isEmpty();
                assertThat(closes).hasValue(1);
                assertThat(manager.subscriptionCount()).isZero();
            }
        }
        try (var factory = factory()) {
            Toolkit toolkit = new Toolkit();
            factory.bind(toolkit);
            factory.bind(toolkit);
            assertThat(creations).hasValue(1);
            factory.close();
            assertThrows(IllegalStateException.class, () -> factory.bind(new Toolkit()));
        }
    }

    @Test
    void failedNativeRegistrationRollsBackToolsAndClosesEveryCreatedClient() {
        var properties = new AgentScopePolarisMcpProperties();
        Client a = new Client();
        a.setServiceName("one");
        Client b = new Client();
        b.setServiceName("two");
        properties.getDiscovery().setClients(List.of(a, b));
        try (var manager = new PolarisMcpServerManager(consumer, "default");
                var factory =
                        new PolarisMcpClientFactory(manager, properties) {
                            @Override
                            protected PolarisMcpClientWrapper createClient(Client settings) {
                                McpSchema.Tool tool =
                                        McpSchema.Tool.builder()
                                                .name(settings.getServiceName())
                                                .inputSchema(
                                                        McpJsonMapper.getDefault(),
                                                        "{\"type\":\"object\"}")
                                                .build();
                                return PolarisMcpClientBuilder.create(
                                                settings.getServiceName(), manager)
                                        .clientFactory(
                                                endpoint ->
                                                        new NativeClient() {
                                                            @Override
                                                            public Mono<List<McpSchema.Tool>>
                                                                    listTools() {
                                                                return Mono.just(List.of(tool));
                                                            }

                                                            @Override
                                                            public void close() {
                                                                closes.incrementAndGet();
                                                            }
                                                        })
                                        .build();
                            }
                        }) {
            Toolkit toolkit = spy(new Toolkit());
            doReturn(Mono.error(new IllegalStateException("registration failed")))
                    .when(toolkit)
                    .registerMcpClient(argThat(client -> "two".equals(client.getName())));
            assertThrows(IllegalStateException.class, () -> factory.bind(toolkit));
            assertThat(toolkit.getToolNames()).isEmpty();
            assertThat(closes).hasValue(2);
            assertThat(manager.subscriptionCount()).isZero();
        }
    }

    @Test
    void timerRecyclesIdleConnectionsAndNextCallReconnectsWithTheSameTools() throws Exception {
        var properties = new AgentScopePolarisMcpProperties();
        Client client = new Client();
        client.setServiceName("tools");
        properties.getDiscovery().setClients(List.of(client));
        properties.getDiscovery().setIdleTimeout(Duration.ofMillis(100));
        try (var manager = new PolarisMcpServerManager(consumer, "default");
                var factory =
                        new PolarisMcpClientFactory(manager, properties) {
                            @Override
                            protected PolarisMcpClientWrapper createClient(Client settings) {
                                var wrapper =
                                        PolarisMcpClientBuilder.create("tools", manager)
                                                .clientFactory(
                                                        endpoint -> {
                                                            creations.incrementAndGet();
                                                            return new NativeClient() {
                                                                @Override
                                                                public void close() {
                                                                    closes.incrementAndGet();
                                                                }
                                                            };
                                                        })
                                                .build();
                                return wrapper;
                            }
                        }) {
            Toolkit toolkit = new Toolkit();
            factory.bind(toolkit);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (manager.subscriptionCount() != 0 && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertThat(manager.subscriptionCount()).isZero();
            assertThat(toolkit.getToolNames()).contains("lookup");
            assertLookup(toolkit);
            assertThat(creations).hasValue(2);
            factory.release(toolkit);
            assertThat(toolkit.getToolNames()).isEmpty();
            assertThat(manager.subscriptionCount()).isZero();
        }
    }
}
