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

import io.agentscope.core.tool.Toolkit;
import com.tencent.ai.polaris.example.a2a.server.springboot.tools.ExampleTools;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot A2A server example backed by Polaris.
 *
 * <p>Almost zero wiring: the {@code agentscope-spring-boot-starter} builds the {@link io.agentscope.core.ReActAgent}
 * from {@code agentscope.*} config; the {@code agentscope-a2a-spring-boot-starter} assembles
 * {@link io.agentscope.core.a2a.server.AgentScopeA2aServer} + the JSON-RPC controller and collects every
 * {@link io.agentscope.core.a2a.server.registry.AgentRegistry} bean (including the {@code PolarisAgentRegistry}
 * contributed by our own {@code agentscope-extensions-polaris-spring-boot-starter}). On {@code ApplicationReadyEvent}
 * the server registers the agent card + transport to Polaris.
 *
 * <p>Configure via {@code src/main/resources/application.yml} (or env vars), then run:
 * <pre>{@code
 * # fetch the agent card
 * curl http://localhost:8888/.well-known/agent-card.json
 * # send a streaming message
 * curl -N -X POST http://localhost:8888/ -H 'Content-Type: application/json' \
 *   -d '{"jsonrpc":"2.0","id":"1","method":"message/stream","params":{"message":{"role":"user","kind":"message","parts":[{"kind":"text","text":"What is 346 * 47?"}],"messageId":"m1","contextId":"c1"}}}'
 * }</pre>
 * Or talk to it with {@code PolarisA2aClientExample} / the Spring Boot client.
 */
@SpringBootApplication
public class PolarisA2aServerSpringBootExample {

    public static void main(String[] args) {
        SpringApplication.run(PolarisA2aServerSpringBootExample.class, args);
    }

    /**
     * Register the example tools so the auto-built ReActAgent can call them.
     *
     * <p>The agentscope-spring-boot-starter exposes Toolkit as a prototype-scoped bean with
     * {@code @ConditionalOnMissingBean}; supplying our own here adds the tools to that kit.
     */
    @Bean
    public Toolkit toolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExampleTools());
        return toolkit;
    }
}
