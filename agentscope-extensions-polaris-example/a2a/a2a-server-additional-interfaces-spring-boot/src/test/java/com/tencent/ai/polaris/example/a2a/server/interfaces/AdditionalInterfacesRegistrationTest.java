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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdditionalInterfacesRegistrationTest {

    @Test
    void configuredInterfacesSurviveRegistrationAndDiscovery() {
        new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PolarisA2aAdditionalInterfacesSpringBootExample.class,
                        PolarisTestConfiguration.class)
                .withPropertyValues("server.address=127.0.0.1", "server.port=18890",
                        "agentscope.polaris.a2a.registry.port=19090")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    AgentScopeA2aServer server = application.getBean(AgentScopeA2aServer.class);
                    AgentCard card = server.getAgentCard();
                    List<String> urls = List.of("http://127.0.0.1:18890/",
                            "http://127.0.0.1:18890/a2a/jsonrpc", "http://127.0.0.1:18890/a2a/internal",
                            "http://127.0.0.1:18890/a2a/external");
                    assertThat(card.url()).isEqualTo(urls.get(0));
                    assertThat(card.additionalInterfaces()).extracting("url").containsExactlyElementsOf(urls);
                    assertThat(card.additionalInterfaces()).extracting("transport").containsOnly("JSONRPC");

                    PolarisContextManager context = application.getBean(PolarisContextManager.class);
                    server.postEndpointReady();
                    ArgumentCaptor<InstanceRegisterRequest> captor = ArgumentCaptor.forClass(InstanceRegisterRequest.class);
                    verify(context.providerAPI()).registerInstance(captor.capture());
                    InstanceRegisterRequest request = captor.getValue();
                    assertThat(request.getHost()).isEqualTo("127.0.0.1");
                    assertThat(request.getPort()).isEqualTo(19090);
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
