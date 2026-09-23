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

package com.tencent.ai.polaris.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.spring.boot.a2a.AgentscopeA2aPolarisAutoConfiguration;
import com.tencent.ai.polaris.spring.boot.config.AgentScopePolarisProperties;
import com.tencent.ai.polaris.spring.boot.config.a2a.AgentScopeA2aPolarisProperties;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.a2a.server.registry.AgentRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Tests for {@link AgentscopePolarisAutoConfiguration} and
 * {@link AgentscopeA2aPolarisAutoConfiguration}.
 *
 * <p>Covers the conditional-switch behavior and property binding. The {@link PolarisContextManager} bean builds a
 * real SDK connection on construction, so the happy-path connection is not exercised here — it is verified
 * end-to-end by the example apps. These tests stay off the real connection by omitting
 * {@code agentscope.polaris.address} and injecting a stub {@link PolarisContextManager} when feature beans
 * must be constructed, or by mocking construction when the auto-config itself must create the context.
 */
class AgentscopePolarisAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AgentscopePolarisAutoConfiguration.class,
                    AgentscopeA2aPolarisAutoConfiguration.class));

    @Test
    void shouldNotCreateA2aBeansWhenA2aDisabled() {
        runner.withPropertyValues("agentscope.polaris.a2a.enabled=false")
                .withBean(PolarisContextManager.class, AgentscopePolarisAutoConfigurationTest::stubContextManager)
                .run(context -> {
                    assertThat(context).hasSingleBean(PolarisContextManager.class);
                    assertThat(context).doesNotHaveBean(AgentRegistry.class);
                    assertThat(context).doesNotHaveBean(AgentCardResolver.class);
                });
    }

    @Test
    void shouldNotCreateContextWhenPolarisDisabled() {
        try (MockedConstruction<PolarisContextManager> ignored = Mockito.mockConstruction(PolarisContextManager.class)) {
            runner.withPropertyValues(
                            "agentscope.polaris.enabled=false",
                            "agentscope.polaris.address=127.0.0.1:8091")
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(PolarisContextManager.class);
                        assertThat(context).doesNotHaveBean(AgentRegistry.class);
                        assertThat(context).doesNotHaveBean(AgentCardResolver.class);
                    });
        }
    }

    @Test
    void shouldNotCreateContextWhenAddressMissing() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(PolarisContextManager.class);
            assertThat(context).doesNotHaveBean(AgentRegistry.class);
            assertThat(context).doesNotHaveBean(AgentCardResolver.class);
        });
    }

    @Test
    void shouldNotCreateRegistryBeanWhenRegistryDisabled() {
        runner.withPropertyValues(
                        "agentscope.polaris.a2a.enabled=true",
                        "agentscope.polaris.a2a.registry.enabled=false")
                .withBean(PolarisContextManager.class, AgentscopePolarisAutoConfigurationTest::stubContextManager)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AgentRegistry.class);
                    assertThat(context).hasSingleBean(AgentCardResolver.class);
                });
    }

    @Test
    void shouldNotCreateDiscoveryBeanWhenDiscoveryDisabled() {
        runner.withPropertyValues(
                        "agentscope.polaris.a2a.enabled=true",
                        "agentscope.polaris.a2a.discovery.enabled=false")
                .withBean(PolarisContextManager.class, AgentscopePolarisAutoConfigurationTest::stubContextManager)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AgentCardResolver.class);
                    assertThat(context).hasSingleBean(AgentRegistry.class);
                });
    }

    @Test
    void shouldCreateRegistryAndDiscoveryWhenEnabled() {
        runner.withPropertyValues("agentscope.polaris.a2a.enabled=true")
                .withBean(PolarisContextManager.class, AgentscopePolarisAutoConfigurationTest::stubContextManager)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentRegistry.class);
                    assertThat(context).hasSingleBean(AgentCardResolver.class);
                });
    }

    @Test
    void shouldKeepUserSuppliedAgentRegistry() {
        AgentRegistry customRegistry = mock(AgentRegistry.class);

        runner.withPropertyValues("agentscope.polaris.a2a.enabled=true")
                .withBean(PolarisContextManager.class, AgentscopePolarisAutoConfigurationTest::stubContextManager)
                .withBean(AgentRegistry.class, () -> customRegistry)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentRegistry.class);
                    assertThat(context.getBean(AgentRegistry.class)).isSameAs(customRegistry);
                });
    }

    @Test
    void shouldKeepUserSuppliedAgentCardResolver() {
        AgentCardResolver customResolver = mock(AgentCardResolver.class);

        runner.withPropertyValues("agentscope.polaris.a2a.enabled=true")
                .withBean(PolarisContextManager.class, AgentscopePolarisAutoConfigurationTest::stubContextManager)
                .withBean(AgentCardResolver.class, () -> customResolver)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentCardResolver.class);
                    assertThat(context.getBean(AgentCardResolver.class)).isSameAs(customResolver);
                });
    }

    /**
     * Regression: A2A beans must appear when {@link PolarisContextManager} is created by this
     * starter's own auto-configuration, not only when a user bean is registered first.
     */
    @Test
    void shouldCreateA2aBeansWhenContextManagerIsCreatedByAutoConfiguration() {
        try (MockedConstruction<PolarisContextManager> ignored = Mockito.mockConstruction(
                PolarisContextManager.class,
                (mock, construction) -> {
                    when(mock.getNamespace()).thenReturn("default");
                    when(mock.getProperties()).thenReturn(new com.tencent.ai.polaris.core.PolarisServerProperties());
                    when(mock.providerAPI())
                            .thenReturn(mock(com.tencent.polaris.api.core.ProviderAPI.class));
                    when(mock.consumerAPI())
                            .thenReturn(mock(com.tencent.polaris.api.core.ConsumerAPI.class));
                })) {
            runner.withPropertyValues(
                            "agentscope.polaris.address=127.0.0.1:8091",
                            "agentscope.polaris.a2a.enabled=true")
                    .run(context -> {
                        assertThat(context).hasSingleBean(PolarisContextManager.class);
                        assertThat(context).hasSingleBean(AgentRegistry.class);
                        assertThat(context).hasSingleBean(AgentCardResolver.class);
                    });
        }
    }

    /**
     * Bind properties via a minimal context that only enables the properties classes — bypassing the
     * auto-config so no {@link PolarisContextManager} connection is attempted.
     */
    @Test
    void shouldBindPropertiesFromPrefix() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnlyConfig.class)
                .withPropertyValues(
                        "agentscope.polaris.address=10.0.0.1:8091",
                        "agentscope.polaris.namespace=prod",
                        "agentscope.polaris.token=secret",
                        "agentscope.polaris.a2a.enabled=true",
                        "agentscope.polaris.a2a.registry.ttl=10")
                .run(context -> {
                    AgentScopePolarisProperties polarisProps =
                            context.getBean(AgentScopePolarisProperties.class);
                    assertThat(polarisProps.getAddress()).isEqualTo("10.0.0.1:8091");
                    assertThat(polarisProps.getNamespace()).isEqualTo("prod");
                    assertThat(polarisProps.getToken()).isEqualTo("secret");

                    AgentScopeA2aPolarisProperties a2aProps =
                            context.getBean(AgentScopeA2aPolarisProperties.class);
                    assertThat(a2aProps.isEnabled()).isTrue();
                    assertThat(a2aProps.getRegistry().getTtl()).isEqualTo(10);
                    assertThat(a2aProps.getDiscovery().isEnabled()).isTrue();
                });
    }

    private static PolarisContextManager stubContextManager() {
        PolarisContextManager ctx = mock(PolarisContextManager.class);
        when(ctx.getNamespace()).thenReturn("default");
        when(ctx.getProperties()).thenReturn(new com.tencent.ai.polaris.core.PolarisServerProperties());
        when(ctx.providerAPI()).thenReturn(mock(com.tencent.polaris.api.core.ProviderAPI.class));
        when(ctx.consumerAPI()).thenReturn(mock(com.tencent.polaris.api.core.ConsumerAPI.class));
        return ctx;
    }

    @Configuration
    @EnableConfigurationProperties({AgentScopePolarisProperties.class, AgentScopeA2aPolarisProperties.class})
    static class PropertiesOnlyConfig {
    }
}
