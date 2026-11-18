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

package com.tencent.ai.polaris.example.a2a.server.interfaces;

import com.tencent.ai.polaris.a2a.discovery.PolarisAgentCardResolver;
import com.tencent.ai.polaris.a2a.util.AgentCardCodec;
import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.core.ProviderAPI;
import com.tencent.polaris.api.pojo.Instance;
import com.tencent.polaris.api.rpc.GetHealthyInstancesRequest;
import com.tencent.polaris.api.rpc.InstanceRegisterRequest;
import com.tencent.polaris.api.rpc.InstanceRegisterResponse;
import com.tencent.polaris.api.rpc.InstancesResponse;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.utils.NetworkUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import io.agentscope.spring.boot.a2a.AgentscopeA2aAutoConfiguration;
import io.agentscope.spring.boot.a2a.properties.A2aAgentCardProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransportRegistrationTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"127.0.0.1"})
    void starterDeploymentIpSurvivesRegistrationAndDiscovery(String bindAddress) {
        try (MockedStatic<NetworkUtils> network = mockStatic(NetworkUtils.class)) {
            network.when(NetworkUtils::getLocalIpAddress).thenReturn("10.20.30.40");
            WebApplicationContextRunner runner = new WebApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withInitializer(context -> new A2aInstanceIpEnvironmentPostProcessor()
                            .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
                    .withUserConfiguration(PolarisA2aAdditionalInterfacesSpringBootExample.class,
                            PolarisTestConfiguration.class)
                    .withPropertyValues("server.port=18890",
                            "agentscope.a2a.server.grpc.port=18891",
                            "agentscope.model.provider=echo-last-user-input", "agentscope.polaris.skill.enabled=false");
            if (bindAddress != null) {
                runner = runner.withPropertyValues("server.address=" + bindAddress,
                        "agentscope.polaris.a2a.registry.port=19090");
            }
            runner.run(application -> {
                assertThat(application).hasNotFailed();
                assertThat(application).hasSingleBean(AgentScopeA2aServer.class);
                assertThat(application.getSourceApplicationContext().getBeanFactory()
                        .getBeanDefinition("agentScopeA2aServer").getFactoryBeanName())
                        .isEqualTo(AgentscopeA2aAutoConfiguration.class.getName());
                AgentScopeA2aServer server = application.getBean(AgentScopeA2aServer.class);
                AgentCard card = server.getAgentCard();
                String host = bindAddress == null ? "10.20.30.40" : bindAddress;
                List<String> urls = List.of("http://" + host + ":18890/a2a/rest",
                        "http://" + host + ":18891");
                assertThat(card.url()).isEqualTo("http://" + host + ":18890/");
                assertThat(card.preferredTransport()).isEqualTo("JSONRPC");
                assertThat(card.additionalInterfaces()).extracting("url").containsExactlyElementsOf(urls);
                assertThat(card.additionalInterfaces()).extracting("transport")
                        .containsExactly("HTTP+JSON", "GRPC");

                PolarisContextManager context = application.getBean(PolarisContextManager.class);
                server.postEndpointReady();
                ArgumentCaptor<InstanceRegisterRequest> captor = ArgumentCaptor.forClass(InstanceRegisterRequest.class);
                verify(context.providerAPI()).registerInstance(captor.capture());
                InstanceRegisterRequest request = captor.getValue();
                assertThat(request.getHost()).isEqualTo(host);
                assertThat(request.getPort()).isEqualTo(bindAddress == null ? 1 : 19090);
                assertThat(request.getProtocol()).isEmpty();
                assertThat(request.getMetadata().keySet())
                        .isEqualTo(Set.of("ai-agent-name", "ai-agent-card", "ai-a2a-version"));
                assertThat(request.getMetadata().get("ai-a2a-version")).isEqualTo("0.3.0");
                assertThat(AgentCardCodec.fromJson(request.getMetadata().get("ai-agent-card"))).isEqualTo(card);

                Instance instance = mock(Instance.class);
                when(instance.getHost()).thenReturn(request.getHost());
                when(instance.getPort()).thenReturn(request.getPort());
                when(instance.getMetadata()).thenReturn(request.getMetadata());
                InstancesResponse response = mock(InstancesResponse.class);
                when(response.getInstances()).thenReturn(new Instance[]{instance});
                when(context.consumerAPI().getHealthyInstances(any(GetHealthyInstancesRequest.class)))
                        .thenReturn(response);
                assertThat(application.getBean(PolarisAgentCardResolver.class).getAgentCard(card.name()))
                        .isEqualTo(card);
            });
        }
    }

    @Test
    void starterHonorsConfiguredCardUrlsAndMetadata() {
        new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> new A2aInstanceIpEnvironmentPostProcessor()
                        .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
                .withUserConfiguration(PolarisA2aAdditionalInterfacesSpringBootExample.class, PolarisTestConfiguration.class)
                .withPropertyValues("server.address=127.0.0.1",
                        "agentscope.model.provider=echo-last-user-input", "agentscope.polaris.skill.enabled=false",
                        "agentscope.a2a.server.card.url=https://agents.example.com/rpc",
                        "agentscope.a2a.server.card.additional-interfaces[0].transport=HTTP+JSON",
                        "agentscope.a2a.server.card.additional-interfaces[0].url=https://agents.example.com/rest",
                        "agentscope.a2a.server.card.additional-interfaces[1].transport=GRPC",
                        "agentscope.a2a.server.card.additional-interfaces[1].url=https://agents.example.com:443",
                        "agentscope.a2a.server.card.description=Configured description",
                        "agentscope.a2a.server.card.version=2.3.4")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application).hasSingleBean(AgentScopeA2aServer.class);
                    AgentCard card = application.getBean(AgentScopeA2aServer.class).getAgentCard();
                    assertThat(card.url()).isEqualTo("https://agents.example.com/rpc");
                    assertThat(card.additionalInterfaces()).extracting("url").containsExactly(
                            "https://agents.example.com/rest", "https://agents.example.com:443");
                    assertThat(card.description()).isEqualTo("Configured description");
                    assertThat(card.version()).isEqualTo("2.3.4");
                    assertThat(card.additionalInterfaces()).isEqualTo(
                            application.getBean(A2aAgentCardProperties.class).getAdditionalInterfaces());
                });
    }

    @Test
    void disablingA2aServerDisablesExampleEndpoints() {
        new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PolarisA2aAdditionalInterfacesSpringBootExample.class, PolarisTestConfiguration.class)
                .withPropertyValues("agentscope.a2a.server.enabled=false",
                        "agentscope.model.provider=echo-last-user-input", "agentscope.polaris.skill.enabled=false")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application).doesNotHaveBean(AgentScopeA2aServer.class);
                    assertThat(application).doesNotHaveBean(A2aRestController.class);
                    assertThat(application).doesNotHaveBean(GrpcServerLifecycle.class);
                    assertThat(application).doesNotHaveBean(TransportConfiguration.class);
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PolarisTestConfiguration {

        @Bean
        PolarisContextManager polarisContextManager() {
            ProviderAPI provider = mock(ProviderAPI.class);
            when(provider.registerInstance(any(InstanceRegisterRequest.class)))
                    .thenReturn(new InstanceRegisterResponse("additional-interfaces-instance", false));
            PolarisContextManager context = mock(PolarisContextManager.class);
            when(context.providerAPI()).thenReturn(provider);
            when(context.consumerAPI()).thenReturn(mock(ConsumerAPI.class));
            when(context.getNamespace()).thenReturn("default");
            return context;
        }
    }
}
