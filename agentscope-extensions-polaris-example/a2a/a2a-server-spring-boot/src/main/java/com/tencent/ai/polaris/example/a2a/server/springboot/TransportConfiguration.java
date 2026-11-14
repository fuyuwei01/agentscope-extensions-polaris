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

package com.tencent.ai.polaris.example.a2a.server.springboot;

import io.a2a.spec.AgentInterface;
import io.a2a.spec.TransportProtocol;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.executor.AgentExecuteProperties;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.registry.AgentRegistry;
import io.agentscope.core.a2a.server.transport.CustomTransportProperties;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import io.agentscope.spring.boot.a2a.properties.A2aAgentCardProperties;
import io.agentscope.spring.boot.a2a.properties.A2aCommonProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;

/** Assembles the three transports with one automatically resolved instance IP. */
@Configuration(proxyBeanMethods = false)
public class TransportConfiguration {

    @Bean
    AgentScopeA2aServer agentScopeA2aServer(
            AgentRunner agentRunner, A2aAgentCardProperties card, A2aCommonProperties common,
            Environment environment, List<AgentRegistry> registries,
            List<CustomTransportProperties> transports,
            @Value("${agentscope.a2a.server.grpc.port:8889}") int grpcPort) {
        // server.address controls socket binding; it must not become the advertised IP.
        DeploymentProperties deployment = new DeploymentProperties.Builder()
                .port(environment.getProperty("server.port", Integer.class, 8888))
                .path(environment.getProperty("server.servlet.context-path"))
                .build();
        String contextPath = deployment.path() == null ? "" : deployment.path();
        String httpUrl = "http://" + deployment.host() + ":" + deployment.port() + contextPath;
        ConfigurableAgentCard configuredCard = new ConfigurableAgentCard.Builder()
                .name(card.getName())
                .description(card.getDescription())
                .provider(card.getProvider())
                .version(card.getVersion())
                .documentationUrl(card.getDocumentationUrl())
                .defaultInputModes(card.getDefaultInputModes())
                .defaultOutputModes(card.getDefaultOutputModes())
                .skills(card.getSkills())
                .securitySchemes(card.getSecuritySchemes())
                .security(card.getSecurity())
                .iconUrl(card.getIconUrl())
                .preferredTransport(TransportProtocol.JSONRPC.asString())
                .url(httpUrl + "/")
                .additionalInterfaces(List.of(
                        new AgentInterface(TransportProtocol.HTTP_JSON.asString(), httpUrl + "/a2a/rest"),
                        new AgentInterface(TransportProtocol.GRPC.asString(),
                                "http://" + deployment.host() + ":" + grpcPort)))
                .build();
        AgentScopeA2aServer.Builder builder = AgentScopeA2aServer.builder(agentRunner)
                .deploymentProperties(deployment)
                .agentCard(configuredCard)
                .agentExecuteProperties(AgentExecuteProperties.builder()
                        .completeWithMessage(common.isCompleteWithMessage())
                        .requireInnerMessage(common.isRequireInnerMessage())
                        .build());
        transports.stream().filter(CustomTransportProperties::isEnabled).forEach(transport -> {
            transport.setDeploymentProperties(deployment);
            builder.withTransport(transport.toTransportProperties());
        });
        registries.forEach(builder::withAgentRegistry);
        return builder.build();
    }

    @Bean
    CustomTransportProperties restTransportProperties() {
        return properties(TransportProtocol.HTTP_JSON.asString(), null, "/a2a/rest");
    }

    @Bean
    CustomTransportProperties grpcTransportProperties(@Value("${agentscope.a2a.server.grpc.port:8889}") int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("agentscope.a2a.server.grpc.port must be between 1 and 65535");
        }
        return properties(TransportProtocol.GRPC.asString(), port, "");
    }

    private CustomTransportProperties properties(String protocol, Integer port, String path) {
        return new CustomTransportProperties() {
            private DeploymentProperties deployment;

            @Override
            public TransportProperties toTransportProperties() {
                return TransportProperties.builder(protocol)
                        .host(deployment.host())
                        .port(port == null ? deployment.port() : port)
                        .path(path)
                        .build();
            }

            @Override
            public void setDeploymentProperties(DeploymentProperties deployment) {
                this.deployment = deployment;
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };
    }
}
