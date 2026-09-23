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

package com.tencent.ai.polaris.example.a2a.client.springboot;

import com.tencent.ai.polaris.example.a2a.client.A2aAgentExampleRunner;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot A2A client example backed by Polaris.
 *
 * <p>The {@code agentscope-extensions-polaris-spring-boot-starter} contributes a {@link AgentCardResolver} bean
 * (PolarisAgentCardResolver). This app wires it into an {@link A2aAgent} bean and runs an interactive multi-turn REPL
 * via a {@link CommandLineRunner}: the agent discovers the remote card from Polaris by name and streams its reply.
 *
 * <p>Run the Spring Boot server example first, then this client.
 */
@SpringBootApplication
public class PolarisA2aClientSpringBootExample {

    public static void main(String[] args) {
        SpringApplication.run(PolarisA2aClientSpringBootExample.class, args);
    }

    @Bean
    A2aAgent a2aAgent(
            AgentCardResolver agentCardResolver,
            @Value("${agentscope.a2a.agent-name}") String agentName) {
        return A2aAgent.builder()
                .name(agentName)
                .agentCardResolver(agentCardResolver)
                .build();
    }

    @Bean
    CommandLineRunner a2aClientRunner(A2aAgent agent) {
        return args -> new A2aAgentExampleRunner(agent).startExample();
    }
}
