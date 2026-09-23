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

package com.tencent.ai.polaris.spring.boot.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.skill.PolarisSkillRepository;
import com.tencent.ai.polaris.spring.boot.AgentscopePolarisAutoConfiguration;
import com.tencent.polaris.ai.api.core.SkillAPI;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Tests for {@link AgentscopePolarisSkillAutoConfiguration}.
 */
class AgentscopePolarisSkillAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AgentscopePolarisAutoConfiguration.class,
                    AgentscopePolarisSkillAutoConfiguration.class));

    @Test
    void shouldNotCreateRepositoryWhenSkillDisabled() {
        runner.withPropertyValues("agentscope.polaris.skill.enabled=false")
                .withBean(PolarisContextManager.class, AgentscopePolarisSkillAutoConfigurationTest::stubContextManager)
                .run(context -> assertThat(context).doesNotHaveBean(AgentSkillRepository.class));
    }

    @Test
    void shouldCreatePolarisSkillRepositoryWhenEnabled() {
        runner.withPropertyValues(
                        "agentscope.polaris.skill.enabled=true",
                        "agentscope.polaris.skill.version=v1",
                        "agentscope.polaris.skill.names=weather,calendar",
                        "agentscope.polaris.skill.list-limit=20",
                        "agentscope.polaris.skill.max-skills=30",
                        "agentscope.polaris.skill.list-refresh-interval-ms=40000")
                .withBean(PolarisContextManager.class, AgentscopePolarisSkillAutoConfigurationTest::stubContextManager)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentSkillRepository.class);
                    assertThat(context).doesNotHaveBean(SkillAPI.class);
                    assertThat(context.getBean(AgentSkillRepository.class))
                            .isInstanceOf(PolarisSkillRepository.class);
                });
    }

    @Test
    void shouldNotCreateRepositoryWhenContextManagerMissing() {
        runner.withPropertyValues("agentscope.polaris.skill.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(AgentSkillRepository.class));
    }

    private static PolarisContextManager stubContextManager() {
        PolarisContextManager ctx = mock(PolarisContextManager.class);
        when(ctx.getNamespace()).thenReturn("default");
        when(ctx.skillAPI()).thenReturn(mock(SkillAPI.class));
        return ctx;
    }
}
