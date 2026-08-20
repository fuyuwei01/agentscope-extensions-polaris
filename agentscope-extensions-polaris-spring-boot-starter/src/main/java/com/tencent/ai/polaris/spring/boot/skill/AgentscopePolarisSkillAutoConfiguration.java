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
import com.tencent.polaris.factory.api.APIFactory;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Skill repository beans backed by Polaris.
 *
 * <p>Processed after {@link AgentscopePolarisAutoConfiguration} so {@code @ConditionalOnBean}
 * can see a {@link PolarisContextManager} created by this starter or supplied by the application.
 * {@link SkillAPI} shares {@code SDKContext} with {@link PolarisContextManager}; its bean must
 * not use {@code destroy} or {@code close} as a destroy method.
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
     * Creates a {@link SkillAPI} from the shared Polaris SDK context when the application
     * has not supplied one.
     *
     * @param context shared Polaris SDK context
     * @return a SkillAPI that must not be destroyed independently of {@code context}
     */
    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean(SkillAPI.class)
    public SkillAPI skillAPI(PolarisContextManager context) {
        return APIFactory.createSkillAPIByContext(context.getSdkContext());
    }

    /**
     * Registers a Polaris-backed skill repository.
     *
     * @param skillAPI skill client (application-supplied or created by {@link #skillAPI})
     * @param context shared Polaris SDK context
     * @param skillProperties list/cache settings bound from {@code agentscope.polaris.skill}
     * @return the Polaris-backed {@link AgentSkillRepository}
     */
    @Bean
    public AgentSkillRepository polarisSkillRepository(
            SkillAPI skillAPI,
            PolarisContextManager context,
            AgentScopePolarisSkillProperties skillProperties) {
        return new PolarisSkillRepository(
                skillAPI,
                context.getNamespace(),
                skillProperties.getVersion(),
                skillProperties.getNames(),
                skillProperties.getListLimit(),
                skillProperties.getMaxSkills(),
                skillProperties.getListRefreshIntervalMs());
    }
}
