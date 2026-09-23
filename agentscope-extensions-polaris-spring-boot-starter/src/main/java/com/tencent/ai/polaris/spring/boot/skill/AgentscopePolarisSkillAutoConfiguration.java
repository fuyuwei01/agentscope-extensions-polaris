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
     * @param context shared Polaris SDK context
     * @param skillProps skill repository settings
     * @return the Polaris-backed {@link AgentSkillRepository}
     */
    @Bean
    @ConditionalOnMissingBean(AgentSkillRepository.class)
    public AgentSkillRepository polarisSkillRepository(
            PolarisContextManager context,
            AgentScopePolarisSkillProperties skillProps) {
        return new PolarisSkillRepository(
                context,
                skillProps.getVersion(),
                skillProps.getNames(),
                skillProps.getListLimit(),
                skillProps.getMaxSkills(),
                skillProps.getListRefreshIntervalMs());
    }
}
