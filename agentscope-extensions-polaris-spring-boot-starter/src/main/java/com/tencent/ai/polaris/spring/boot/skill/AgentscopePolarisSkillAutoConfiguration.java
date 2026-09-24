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

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.skill.PolarisMountedSkillRepository;
import com.tencent.ai.polaris.skill.PolarisSkillRepository;
import com.tencent.ai.polaris.spring.boot.AgentscopePolarisAutoConfiguration;
import com.tencent.ai.polaris.spring.boot.config.skill.AgentScopePolarisSkillProperties;
import com.tencent.ai.polaris.spring.boot.constants.PolarisConstants;
import com.tencent.polaris.ai.api.core.SkillAPI;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * AgentScope skill repository auto-configuration backed by Polaris SkillAPI.
 */
@AutoConfiguration(after = AgentscopePolarisAutoConfiguration.class)
@ConditionalOnClass({AgentSkillRepository.class, SkillAPI.class})
@ConditionalOnBean(PolarisContextManager.class)
@ConditionalOnProperty(
        prefix = PolarisConstants.SKILL_POLARIS_PREFIX,
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(AgentScopePolarisSkillProperties.class)
public class AgentscopePolarisSkillAutoConfiguration {

    /**
     * Creates the Polaris-backed AgentScope skill repository.
     *
     * <p>{@code agentscope.polaris.skill.mounted.enabled=true} binds
     * {@link PolarisMountedSkillRepository}. The service name is
     * {@code skill.mounted.service-name}, else {@code agentscope.a2a.server.card.name},
     * else {@code agentscope.agent.name}. Otherwise this is a published-skill
     * {@link PolarisSkillRepository}.
     *
     * @param context shared Polaris SDK context
     * @param skillProps skill repository settings
     * @param environment application environment for A2A / agent name fallbacks
     * @return the Polaris-backed {@link AgentSkillRepository}
     */
    @Bean
    @ConditionalOnMissingBean(AgentSkillRepository.class)
    public AgentSkillRepository polarisSkillRepository(
            PolarisContextManager context,
            AgentScopePolarisSkillProperties skillProps,
            Environment environment) {
        if (skillProps.getMounted() != null && skillProps.getMounted().isEnabled()) {
            return new PolarisMountedSkillRepository(
                    context, resolveMountedServiceName(skillProps, environment), skillProps.getVersion());
        }
        return new PolarisSkillRepository(
                context,
                skillProps.getVersion(),
                skillProps.getNames(),
                skillProps.getListLimit(),
                skillProps.getMaxSkills(),
                skillProps.getListRefreshIntervalMs());
    }

    static String resolveMountedServiceName(
            AgentScopePolarisSkillProperties skillProps, Environment environment) {
        String serviceName = firstNonBlank(
                skillProps.getMounted() == null ? null : skillProps.getMounted().getServiceName(),
                environment.getProperty(PolarisConstants.A2A_SERVER_CARD_NAME),
                environment.getProperty(PolarisConstants.AGENT_NAME));
        if (serviceName.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot resolve Polaris mounted skill service name when "
                            + "agentscope.polaris.skill.mounted.enabled=true. Set "
                            + "agentscope.a2a.server.card.name or agentscope.agent.name"
                            + " (or agentscope.polaris.skill.mounted.service-name to override)");
        }
        return serviceName;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
