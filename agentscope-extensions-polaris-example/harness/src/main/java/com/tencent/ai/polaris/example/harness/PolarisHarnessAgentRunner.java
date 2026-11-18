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

import com.tencent.ai.polaris.spring.boot.mcp.PolarisMcpClientFactory;

import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;

import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Creates one HarnessAgent per A2A request while sharing the original MCP Toolkit. */
public class PolarisHarnessAgentRunner implements AgentRunner {
    private static final String DESCRIPTION =
            "Harness Agent with Polaris MCP tools, skills and remote agents";
    private static final String PROMPT =
            """
            You are a helpful assistant. Your MCP tools and skills come from Polaris.
            Load a matching skill before working on a task. Use ask_agent to delegate to another agent by name.
            Commands: /skill-list lists available skills; /skill <name> loads its SKILL.md;
            /mcp <tool> <JSON arguments> calls that tool; /a2a <agent-name> <question> calls ask_agent.
            """;
    private final String name;
    private final Model model;
    private final Toolkit original;
    private final AgentSkillRepository skills;
    private final PolarisMcpClientFactory clients;
    private final Path workspace;
    private final Map<String, HarnessAgent> active = new ConcurrentHashMap<>();

    public PolarisHarnessAgentRunner(
            String name,
            Model model,
            Toolkit original,
            AgentSkillRepository skills,
            PolarisMcpClientFactory clients,
            Path workspace) {
        this.name = name;
        this.model = model;
        this.original = original;
        this.skills = skills;
        this.clients = clients;
        this.workspace = workspace;
    }

    @Override
    public String getAgentName() {
        return name;
    }

    @Override
    public String getAgentDescription() {
        return DESCRIPTION;
    }

    @Override
    public Flux<Event> stream(List<Msg> messages, AgentRequestOptions options) {
        return Flux.defer(
                () -> {
                    String taskId = options.getTaskId();
                    // Separate the working directory and Toolkit so concurrent requests cannot
                    // alter each other.
                    HarnessAgent agent =
                            HarnessAgent.builder()
                                    .name(name)
                                    .description(DESCRIPTION)
                                    .sysPrompt(PROMPT)
                                    .model(model)
                                    .toolkit(original.copy())
                                    .skillRepository(skills)
                                    .maxIters(10)
                                    .workspace(workspace.resolve(UUID.randomUUID().toString()))
                                    // Refresh at request construction; static binding targets the
                                    // delegate's Toolkit.
                                    .disableDynamicSkills()
                                    .disableShellTool()
                                    .disableSubagents()
                                    .disableMemoryTools()
                                    .disableMemoryHooks()
                                    .disableSessionPersistence()
                                    .disableToolsConfig()
                                    .build();
                    Toolkit toolkit = agent.getDelegate().getToolkit();
                    if (clients != null) clients.retain(toolkit);
                    if (active.putIfAbsent(taskId, agent) != null) {
                        if (clients != null) clients.release(toolkit);
                        agent.close();
                        return Flux.error(
                                new IllegalStateException(
                                        "Agent already exists for task: " + taskId));
                    }
                    RuntimeContext context =
                            RuntimeContext.builder()
                                    .sessionId(options.getSessionId())
                                    .userId(options.getUserId())
                                    .build();
                    return agent.stream(messages, StreamOptions.defaults(), context)
                            .doFinally(
                                    signal -> {
                                        active.remove(taskId, agent);
                                        // Release only this copy; the singleton original is owned
                                        // by the application.
                                        try {
                                            if (clients != null) clients.release(toolkit);
                                        } finally {
                                            agent.close();
                                        }
                                    });
                });
    }

    @Override
    public void stop(String taskId) {
        HarnessAgent agent = active.get(taskId);
        if (agent != null) agent.interrupt();
    }
}
