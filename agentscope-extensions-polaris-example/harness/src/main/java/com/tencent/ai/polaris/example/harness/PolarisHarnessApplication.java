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

import com.tencent.ai.polaris.example.a2a.model.SkillCommandMockModel;
import com.tencent.ai.polaris.spring.boot.mcp.PolarisMcpClientFactory;

import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/** Integrates the three Polaris extensions with a real HarnessAgent runtime. */
@SpringBootApplication
public class PolarisHarnessApplication {
    public static void main(String[] args) {
        SpringApplication.run(PolarisHarnessApplication.class, args);
    }

    @Bean("harnessToolkit")
    Toolkit harnessToolkit(ObjectProvider<AgentCardResolver> resolvers) {
        Toolkit toolkit = new Toolkit();
        // The Polaris MCP post-processor adds remote tools before the runner receives this bean.
        toolkit.registerTool(new RemoteAgentTools(resolvers));
        return toolkit;
    }

    @Bean
    Model harnessModel(
            @Value("${demo.model-provider}") String provider,
            @Value("${demo.api-key}") String apiKey,
            @Value("${demo.base-url}") String baseUrl,
            @Value("${demo.model-name}") String modelName) {
        if ("integration-mock".equals(provider))
            return new IntegrationMockModel(new SkillCommandMockModel());
        if (!"openai".equals(provider))
            throw new IllegalArgumentException(
                    "A2A_MODEL_PROVIDER must be integration-mock or openai");
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .build();
    }

    @Bean
    AgentRunner harnessAgentRunner(
            Toolkit harnessToolkit,
            Model model,
            ObjectProvider<AgentSkillRepository> repositories,
            ObjectProvider<PolarisMcpClientFactory> clients,
            @Value("${demo.agent-name}") String name,
            @Value("${demo.workspace}") String workspace) {
        return new PolarisHarnessAgentRunner(
                name,
                model,
                harnessToolkit,
                repositories.getIfAvailable(),
                clients.getIfAvailable(),
                Path.of(workspace));
    }
}
