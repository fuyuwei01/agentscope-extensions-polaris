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

package com.tencent.ai.polaris.example.a2a.client;

import com.tencent.ai.polaris.a2a.discovery.PolarisAgentCardResolver;
import com.tencent.ai.polaris.core.PolarisContextManager;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example for A2aAgent with Polaris Registry.
 *
 * <p>This example demonstrates how to request a remote agent via the A2A protocol using AgentScope {@link A2aAgent},
 * which discovers the agent's {@link io.a2a.spec.AgentCard} via Polaris (no hardcoded endpoint — only the agent name).
 *
 * <p>Run {@code PolarisA2aServerExample} first so the agent card is registered to Polaris, then run this client.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code POLARIS_ADDRESS} — Polaris server address, default {@code 127.0.0.1:8091}</li>
 *   <li>{@code A2A_AGENT_NAME} — target agent (Polaris service) name,
 *       default {@code polaris-a2a-example-agent} (must match the server)</li>
 * </ul>
 */
public class PolarisA2aClientExample {

    private static final Logger log = LoggerFactory.getLogger(PolarisA2aClientExample.class);

    public static void main(String[] args) {
        String polarisAddress = env("POLARIS_ADDRESS", "114.132.133.191:8091");
        String agentName = env("A2A_AGENT_NAME", "polaris-a2a-example-agent");

        PolarisContextManager context = PolarisContextManager.fromAddress(polarisAddress);
        try {
            // Discover the agent card from Polaris by agent name.
            AgentCardResolver agentCardResolver = PolarisAgentCardResolver.builder(context).build();
            A2aAgent agent = A2aAgent.builder()
                    .name(agentName)
                    .agentCardResolver(agentCardResolver)
                    .build();

            log.info("Talking to agent '{}' discovered via Polaris at {}", agentName, polarisAddress);
            new A2aAgentExampleRunner(agent).startExample();
        } finally {
            try { context.close(); } catch (Exception e) { log.warn("context close failed", e); }
        }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
