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
import com.tencent.ai.polaris.spring.boot.config.skill.AgentScopePolarisSkillProperties;
import com.tencent.polaris.ai.api.core.SkillAPI;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Tests for {@link AgentscopePolarisSkillAutoConfiguration}.
 *
 * <p>Stays off a real Polaris connection by injecting a stub {@link PolarisContextManager}
 * and a mock {@link SkillAPI}. The auto-config must not call
 * {@code APIFactory.createSkillAPIByContext} when a {@link SkillAPI} bean is already present.
 */
class AgentscopePolarisSkillAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AgentscopePolarisAutoConfiguration.class,
                    AgentscopePolarisSkillAutoConfiguration.class));

    @Test
    void shouldNotCreateRepositoryWhenSkillDisabled() {
        runner.withPropertyValues("agentscope.polaris.skill.enabled=false")
                .withBean(PolarisContextManager.class, this::stubContextManager)
                .withBean(SkillAPI.class, () -> mock(SkillAPI.class))
                .run(context -> assertThat(context).doesNotHaveBean(AgentSkillRepository.class));
    }

    @Test
    void shouldCreatePolarisSkillRepositoryWhenEnabled() {
        runner.withPropertyValues(
                        "agentscope.polaris.skill.enabled=true",
                        "agentscope.polaris.skill.names=sql-analysis,chart-rendering")
                .withBean(PolarisContextManager.class, this::stubContextManager)
                .withBean(SkillAPI.class, () -> mock(SkillAPI.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentSkillRepository.class);
                    assertThat(context).hasBean("polarisSkillRepository");
                    AgentSkillRepository repository =
                            context.getBean("polarisSkillRepository", AgentSkillRepository.class);
                    assertThat(repository).isInstanceOf(PolarisSkillRepository.class);
                    assertThat(repository.getAllSkillNames())
                            .containsExactly("sql-analysis", "chart-rendering");
                });
    }

    @Test
    void shouldNotCreateRepositoryWhenContextManagerMissing() {
        runner.withPropertyValues("agentscope.polaris.skill.enabled=true")
                .withBean(SkillAPI.class, () -> mock(SkillAPI.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PolarisContextManager.class);
                    assertThat(context).doesNotHaveBean(AgentSkillRepository.class);
                });
    }

    @Test
    void shouldBindSkillPropertiesFromPrefix() {
        new ApplicationContextRunner()
                .withUserConfiguration(SkillPropertiesOnlyConfig.class)
                .withPropertyValues(
                        "agentscope.polaris.skill.enabled=true",
                        "agentscope.polaris.skill.version=1.0.0",
                        "agentscope.polaris.skill.names=sql-analysis",
                        "agentscope.polaris.skill.list-limit=20",
                        "agentscope.polaris.skill.max-skills=10",
                        "agentscope.polaris.skill.list-refresh-interval-ms=5000")
                .run(context -> {
                    AgentScopePolarisSkillProperties skillProps =
                            context.getBean(AgentScopePolarisSkillProperties.class);
                    assertThat(skillProps.isEnabled()).isTrue();
                    assertThat(skillProps.getVersion()).isEqualTo("1.0.0");
                    assertThat(skillProps.getNames()).containsExactly("sql-analysis");
                    assertThat(skillProps.getListLimit()).isEqualTo(20);
                    assertThat(skillProps.getMaxSkills()).isEqualTo(10);
                    assertThat(skillProps.getListRefreshIntervalMs()).isEqualTo(5000L);
                });
    }

    @Test
    void shouldUseSkillPropertyDefaults() {
        new ApplicationContextRunner()
                .withUserConfiguration(SkillPropertiesOnlyConfig.class)
                .run(context -> {
                    AgentScopePolarisSkillProperties skillProps =
                            context.getBean(AgentScopePolarisSkillProperties.class);
                    assertThat(skillProps.isEnabled()).isTrue();
                    assertThat(skillProps.getVersion()).isEmpty();
                    assertThat(skillProps.getNames()).isEmpty();
                    assertThat(skillProps.getListLimit()).isEqualTo(50);
                    assertThat(skillProps.getMaxSkills()).isEqualTo(100);
                    assertThat(skillProps.getListRefreshIntervalMs()).isEqualTo(30000L);
                });
    }

    private PolarisContextManager stubContextManager() {
        PolarisContextManager ctx = mock(PolarisContextManager.class);
        when(ctx.getNamespace()).thenReturn("default");
        return ctx;
    }

    @Configuration
    @EnableConfigurationProperties(AgentScopePolarisSkillProperties.class)
    static class SkillPropertiesOnlyConfig {
    }
}
