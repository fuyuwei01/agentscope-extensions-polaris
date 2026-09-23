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

package com.tencent.ai.polaris.spring.boot;

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.spring.boot.config.AgentScopePolarisProperties;
import com.tencent.ai.polaris.spring.boot.config.skill.AgentScopePolarisSkillProperties;
import com.tencent.ai.polaris.spring.boot.constants.PolarisConstants;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * Shared Polaris connection auto-configuration for AgentScope.
 *
 * <p>Connection settings come from {@code agentscope.polaris} and produce a shared
 * {@link PolarisContextManager}. Feature beans (A2A, skill, later MCP) live in their own
 * {@code @AutoConfiguration} classes ordered after this one.
 *
 * <p>{@code agentscope.polaris.enabled=false} (matchIfMissing {@code true}) skips creating
 * the context bean even when {@code address} is set.
 */
@AutoConfiguration
@EnableConfigurationProperties({
        AgentScopePolarisProperties.class,
        AgentScopePolarisSkillProperties.class})
public class AgentscopePolarisAutoConfiguration {

    /**
     * Shared Polaris SDK context. Applications may replace it with their own bean.
     *
     * @param properties connection settings bound from {@code agentscope.polaris}
     * @return a context that is closed with the application context
     */
    @Bean(destroyMethod = "close")
    @Conditional(OnPolarisContextEnabled.class)
    @ConditionalOnMissingBean
    public PolarisContextManager polarisContextManager(
            AgentScopePolarisProperties properties,
            AgentScopePolarisSkillProperties skillProperties) {
        if (skillProperties.getAddress() != null && !skillProperties.getAddress().isBlank()) {
            properties.setSkillAddress(skillProperties.getAddress());
        }
        return new PolarisContextManager(properties);
    }

    /**
     * Both {@code agentscope.polaris.enabled} (default true) and {@code address} must be set.
     * Nested because {@code @ConditionalOnProperty} is not repeatable on Spring Boot 3.2.
     */
    static final class OnPolarisContextEnabled extends AllNestedConditions {

        OnPolarisContextEnabled() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(
                prefix = PolarisConstants.POLARIS_PREFIX,
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        static final class Enabled {
        }

        @ConditionalOnProperty(prefix = PolarisConstants.POLARIS_PREFIX, name = "address")
        static final class AddressPresent {
        }
    }
}
