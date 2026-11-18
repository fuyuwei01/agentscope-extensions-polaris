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

import io.modelcontextprotocol.spec.McpSchema;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Deterministic tools allow testing without an LLM key or another external service. */
@Service
public class DemoTools {
    private final String instanceId;

    public DemoTools(@Value("${demo.instance-id}") String instanceId) {
        this.instanceId = instanceId;
    }

    @McpTool(name = "serverInfo", description = "Return the identifier of the serving replica")
    public McpSchema.CallToolResult serverInfo() {
        return text(instanceId);
    }

    @McpTool(name = "echo", description = "Echo a message with the serving replica identifier")
    public McpSchema.CallToolResult echo(
            @McpToolParam(description = "Message to echo", required = true) String message) {
        return text(instanceId + ":" + message);
    }

    @McpTool(name = "add", description = "Add two integers")
    public McpSchema.CallToolResult add(
            @McpToolParam(description = "First integer", required = true) int a,
            @McpToolParam(description = "Second integer", required = true) int b) {
        return text(Long.toString((long) a + b));
    }

    private static McpSchema.CallToolResult text(String value) {
        return McpSchema.CallToolResult.builder().addTextContent(value).build();
    }
}
