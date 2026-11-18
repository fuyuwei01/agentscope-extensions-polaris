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

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.core.PolarisServerProperties;
import com.tencent.ai.polaris.mcp.client.PolarisMcpClientBuilder;
import com.tencent.ai.polaris.mcp.client.PolarisMcpClientWrapper;
import com.tencent.ai.polaris.mcp.discovery.PolarisMcpServerManager;

import io.agentscope.core.tool.Toolkit;

import java.util.Map;

/** Lists remote tools and optionally calls one; the MCP Server must already be registered. */
public final class PolarisMcpClientExample {
    public static void main(String[] args) {
        if (!Boolean.parseBoolean(env("POLARIS_ENABLED", "true"))) {
            System.out.println("Polaris discovery is disabled");
            return;
        }
        String address = env("POLARIS_DISCOVERY_ADDRESS", env("POLARIS_ADDRESS", "127.0.0.1:8091"));
        String namespace = env("POLARIS_NAMESPACE", "default");
        String service = env("MCP_SERVICE_NAME", "business-mcp-server");
        PolarisServerProperties properties = new PolarisServerProperties(address);
        properties.setNamespace(namespace);
        properties.setToken(env("POLARIS_TOKEN", ""));
        try (PolarisContextManager context = new PolarisContextManager(properties);
                PolarisMcpServerManager manager = new PolarisMcpServerManager(context);
                PolarisMcpClientWrapper client =
                        PolarisMcpClientBuilder.create(service, manager)
                                .namespace(namespace)
                                .build()) {
            Toolkit toolkit = new Toolkit();
            toolkit.registerMcpClient(client).block();
            System.out.println("Discovered tools: " + toolkit.getToolNames());
            // Pass this populated Toolkit to ReActAgent.builder().model(model).toolkit(toolkit).
            if (args.length > 0) System.out.println(client.callTool(args[0], Map.of()).block());
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
