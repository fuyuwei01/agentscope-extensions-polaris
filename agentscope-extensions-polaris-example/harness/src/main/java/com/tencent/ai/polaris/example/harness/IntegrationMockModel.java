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

package com.tencent.ai.polaris.example.harness;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Emits real tool calls for smoke testing without an external model API key. */
public class IntegrationMockModel implements Model {
    private final Model skillModel;
    private final ObjectMapper mapper = new ObjectMapper();

    public IntegrationMockModel(Model skillModel) {
        this.skillModel = skillModel;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg message = messages.get(i);
            List<ToolResultBlock> results = message.getContentBlocks(ToolResultBlock.class);
            if (!results.isEmpty())
                return text(
                        results.stream()
                                .flatMap(result -> result.getOutput().stream())
                                .filter(TextBlock.class::isInstance)
                                .map(TextBlock.class::cast)
                                .map(TextBlock::getText)
                                .collect(Collectors.joining("\n")));
            if (message.getRole() == MsgRole.USER) {
                String input = message.getTextContent().trim();
                if (input.startsWith("/skill")) return skillModel.stream(messages, tools, options);
                try {
                    if (input.startsWith("/mcp ")) {
                        String[] parts = input.substring(5).trim().split("\\s+", 2);
                        Map<String, Object> args =
                                parts.length == 1
                                        ? Map.of()
                                        : mapper.readValue(
                                                parts[1],
                                                new TypeReference<Map<String, Object>>() {});
                        return tool(parts[0], args);
                    }
                    if (input.startsWith("/a2a ")) {
                        String[] parts = input.substring(5).trim().split("\\s+", 2);
                        if (parts.length != 2) return text("Usage: /a2a <agent-name> <question>");
                        return tool(
                                "ask_agent", Map.of("agentName", parts[0], "question", parts[1]));
                    }
                } catch (Exception e) {
                    return text("Invalid command: " + e.getMessage());
                }
                return text(
                        "Commands: /skill-list, /skill <name>, /mcp <tool> <JSON>, /a2a"
                            + " <agent-name> <question>");
            }
        }
        return text("No user message");
    }

    private Flux<ChatResponse> tool(String name, Map<String, Object> args) throws Exception {
        return Flux.just(
                ChatResponse.builder()
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id(UUID.randomUUID().toString())
                                                .name(name)
                                                .input(args)
                                                .content(mapper.writeValueAsString(args))
                                                .build()))
                        .finishReason("tool_calls")
                        .build());
    }

    private static Flux<ChatResponse> text(String text) {
        return Flux.just(
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(text).build()))
                        .finishReason("stop")
                        .build());
    }

    @Override
    public String getModelName() {
        return "integration-mock";
    }
}
