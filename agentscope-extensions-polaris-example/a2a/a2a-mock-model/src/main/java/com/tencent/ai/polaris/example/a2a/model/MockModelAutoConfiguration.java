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

package com.tencent.ai.polaris.example.a2a.model;

import io.agentscope.core.model.Model;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Makes the two offline models selectable through {@code agentscope.model.provider}.
 *
 * <p>This configuration runs before the AgentScope starter. Once one of these beans exists,
 * {@link AgentscopeAutoConfiguration#agentscopeModel} backs off through its
 * {@code @ConditionalOnMissingBean(Model.class)} condition, avoiding validation of API keys.
 */
@AutoConfiguration(before = AgentscopeAutoConfiguration.class)
@ConditionalOnClass({Model.class, AgentscopeAutoConfiguration.class})
public class MockModelAutoConfiguration {

    /** Provider id for {@link SkillCommandMockModel}. */
    public static final String SKILL_COMMAND_PROVIDER = "skill-command-mock";

    /** Provider id for {@link EchoLastUserInputModel}. */
    public static final String ECHO_PROVIDER = "echo-last-user-input";

    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnProperty(
            prefix = "agentscope.model",
            name = "provider",
            havingValue = SKILL_COMMAND_PROVIDER)
    public Model skillCommandMockModel() {
        return new SkillCommandMockModel();
    }

    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnProperty(
            prefix = "agentscope.model",
            name = "provider",
            havingValue = ECHO_PROVIDER)
    public Model echoLastUserInputModel() {
        return new EchoLastUserInputModel();
    }
}
