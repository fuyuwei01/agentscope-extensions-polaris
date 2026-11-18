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

package com.tencent.ai.polaris.example.mcp.server;

import com.tencent.ai.polaris.core.PolarisConfigModifier;
import com.tencent.polaris.factory.config.global.ServerConnectorConfigImpl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** MCP registration is supplied entirely by the external Spring AI Polaris Starter. */
@SpringBootApplication
public class SpringAiPolarisMcpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpringAiPolarisMcpServerApplication.class, args);
    }

    /** Bridges the shared demo token to Spring AI Polaris's SDK customization hook. */
    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.polaris", name = "enabled", matchIfMissing = true)
    PolarisConfigModifier polarisTokenConfigModifier(
            @Value("${demo.polaris-token:}") String token) {
        return configuration -> {
            if (!token.isBlank()) {
                ((ServerConnectorConfigImpl) configuration.getGlobal().getServerConnector())
                        .setToken(token);
            }
        };
    }
}
