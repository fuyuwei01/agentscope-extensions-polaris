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

import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import org.springframework.beans.factory.ObjectProvider;

import reactor.core.publisher.Mono;

import java.util.List;

/** Resolves the remote card by service name; no remote URL is configured in this example. */
public class RemoteAgentTools {
    private final ObjectProvider<AgentCardResolver> resolvers;

    public RemoteAgentTools(ObjectProvider<AgentCardResolver> resolvers) {
        this.resolvers = resolvers;
    }

    @Tool(
            name = "ask_agent",
            description = "Discover another agent through Polaris and send it a question")
    public Mono<String> askAgent(
            @ToolParam(name = "agentName", description = "Polaris service name of the remote agent")
                    String agentName,
            @ToolParam(name = "question", description = "Question for the remote agent")
                    String question) {
        return Mono.defer(
                () -> {
                    AgentCardResolver resolver = resolvers.getIfAvailable();
                    if (resolver == null)
                        return Mono.error(
                                new IllegalStateException("Polaris A2A discovery is disabled"));
                    // A separate A2aAgent gives each invocation its own conversational memory.
                    A2aAgent agent =
                            A2aAgent.builder().name(agentName).agentCardResolver(resolver).build();
                    Msg message =
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(TextBlock.builder().text(question).build())
                                    .build();
                    return agent.call(List.of(message)).map(Msg::getTextContent);
                });
    }
}
