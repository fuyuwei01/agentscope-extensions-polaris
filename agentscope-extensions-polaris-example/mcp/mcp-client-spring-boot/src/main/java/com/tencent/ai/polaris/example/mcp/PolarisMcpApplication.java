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

package com.tencent.ai.polaris.example.mcp;

import com.tencent.ai.polaris.spring.boot.mcp.PolarisMcpClientFactory;

import io.agentscope.core.ReActAgent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Uses the default Agent's automatically bound remote tools; model settings are supplied by the
 * host.
 */
@SpringBootApplication
public class PolarisMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(PolarisMcpApplication.class, args);
    }

    @Bean
    ApplicationRunner showTools(
            ObjectProvider<ReActAgent> agents, ObjectProvider<PolarisMcpClientFactory> factories) {
        return args -> {
            ReActAgent agent = agents.getObject();
            try {
                System.out.println("Agent tools: " + agent.getToolkit().getToolNames());
            } finally {
                PolarisMcpClientFactory clients = factories.getIfAvailable();
                if (clients != null) clients.release(agent.getToolkit());
            }
        };
    }
}
