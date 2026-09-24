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

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MockModelAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MockModelAutoConfiguration.class, AgentscopeAutoConfiguration.class))
            .withPropertyValues("agentscope.agent.enabled=true");

    @Test
    void shouldLoadSkillCommandModelFromProviderConfiguration() {
        runner.withPropertyValues("agentscope.model.provider=skill-command-mock")
                .run(context -> {
                    assertThat(context).hasSingleBean(Model.class);
                    assertThat(context.getBean(Model.class))
                            .isExactlyInstanceOf(SkillCommandMockModel.class);
                    assertThat(context).hasSingleBean(ReActAgent.class);
                });
    }

    @Test
    void shouldLoadEchoModelFromProviderConfiguration() {
        runner.withPropertyValues("agentscope.model.provider=echo-last-user-input")
                .run(context -> {
                    assertThat(context).hasSingleBean(Model.class);
                    assertThat(context.getBean(Model.class))
                            .isExactlyInstanceOf(EchoLastUserInputModel.class);
                    assertThat(context).hasSingleBean(ReActAgent.class);
                });
    }
}
