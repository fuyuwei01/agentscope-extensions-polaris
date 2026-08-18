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

import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Interactive multi-turn REPL for an {@link io.agentscope.core.a2a.agent.A2aAgent}.
 *
 * <p>Reads lines from stdin, streams the agent's reply token-by-token to the console, and loops until the user types
 * {@code exit} or {@code quit}. Mirrors the agentscope A2A documentation example's runner, adapted to the
 * {@code stream(List, StreamOptions)} signature of agentscope-core 1.0.x.
 */
public class A2aAgentExampleRunner {

    private static final String USER_PROMPT = "[34mYou>[0m ";
    private static final String AGENT_PROMPT = "[32mAgent>[0m ";

    private final io.agentscope.core.a2a.agent.A2aAgent agent;

    public A2aAgentExampleRunner(io.agentscope.core.a2a.agent.A2aAgent agent) {
        this.agent = agent;
    }

    public void startExample() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            System.out.println("Enter your message (type 'exit' or 'quit' to leave):");
            while (true) {
                System.out.print(USER_PROMPT);
                String input = reader.readLine();
                if (input == null
                        || input.trim().equalsIgnoreCase("exit")
                        || input.trim().equalsIgnoreCase("quit")) {
                    System.out.println(AGENT_PROMPT + "Bye!");
                    break;
                }
                if (input.isBlank()) {
                    continue;
                }

                System.out.println(AGENT_PROMPT + "I have received your question: " + input);
                System.out.print(AGENT_PROMPT);

                Msg msg = Msg.builder()
                        .role(io.agentscope.core.message.MsgRole.USER)
                        .content(TextBlock.builder().text(input).build())
                        .build();
                try {
                    processInput(msg).doOnNext(System.out::print).then().block();
                } catch (Exception e) {
                    System.err.println("\n[error] " + e.getMessage());
                }
                System.out.println();
            }
        } catch (IOException e) {
            System.err.println("input error: " + e.getMessage());
        }
    }

    private Flux<String> processInput(Msg msg) {
        return agent.stream(java.util.List.of(msg), StreamOptions.defaults())
                .map(event -> {
                    if (event.isLast()) {
                        // The last event carries the full assembled result, already printed incrementally above.
                        return "";
                    }
                    StringBuilder partText = new StringBuilder();
                    if (event.getMessage() != null && event.getMessage().getContent() != null) {
                        event.getMessage().getContent().stream()
                                .filter(block -> block instanceof TextBlock)
                                .map(block -> (TextBlock) block)
                                .forEach(block -> partText.append(block.getText()));
                    }
                    return partText.toString();
                });
    }
}
