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

import io.a2a.spec.TransportProtocol;
import io.agentscope.core.a2a.server.transport.CustomTransportProperties;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies additional transports to AgentScope's A2A Spring Boot starter. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agentscope.a2a.server", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TransportConfiguration {

    @Bean
    CustomTransportProperties restTransportProperties(
            @Value("${agentscope.a2a.server.rest.path:/a2a/rest}") String path) {
        return properties(TransportProtocol.HTTP_JSON.asString(), null, path);
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
                        .path(port == null && deployment.path() != null ? deployment.path() + path : path)
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
