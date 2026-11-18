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

import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/** Makes the starter's deployment IP available to AgentCard URL placeholders in YAML. */
public class A2aInstanceIpEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("agentscope.a2a.server.enabled", Boolean.class, true)) {
            return;
        }
        // Match the official starter: server.address is optional, otherwise probe the local IP.
        String host = new DeploymentProperties.Builder()
                .host(environment.getProperty("server.address"))
                .port(environment.getProperty("server.port", Integer.class, 8080))
                .build().host();
        environment.getPropertySources().addFirst(
                new MapPropertySource("a2aInstanceIp", Map.of("a2a.instance-ip", host)));
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
