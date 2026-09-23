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

package com.tencent.ai.polaris.spring.boot.a2a;

import com.tencent.ai.polaris.a2a.discovery.PolarisAgentCardResolver;
import com.tencent.ai.polaris.a2a.registry.PolarisAgentRegistry;
import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.spring.boot.AgentscopePolarisAutoConfiguration;
import com.tencent.ai.polaris.spring.boot.config.a2a.AgentScopeA2aPolarisProperties;
import com.tencent.ai.polaris.spring.boot.constants.PolarisConstants;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.a2a.server.registry.AgentRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * A2A registry and discovery beans backed by Polaris.
 *
 * <p>Processed after {@link AgentscopePolarisAutoConfiguration} so {@code @ConditionalOnBean}
 * can see a {@link PolarisContextManager} created by this starter or supplied by the application.
 */
@AutoConfiguration(after = AgentscopePolarisAutoConfiguration.class)
@ConditionalOnClass({AgentRegistry.class, AgentCardResolver.class})
@ConditionalOnBean(PolarisContextManager.class)
@ConditionalOnProperty(
        prefix = PolarisConstants.A2A_POLARIS_PREFIX,
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(AgentScopeA2aPolarisProperties.class)
public class AgentscopeA2aPolarisAutoConfiguration {

    /**
     * Registers A2A agent cards to Polaris.
     *
     * @param context shared Polaris SDK context
     * @param a2aProperties A2A registry/discovery settings
     * @return the Polaris-backed {@link AgentRegistry}
     */
    @Bean
    @ConditionalOnMissingBean(AgentRegistry.class)
    @ConditionalOnProperty(
            name = "agentscope.polaris.a2a.registry.enabled",
            havingValue = "true",
            matchIfMissing = true)
    public AgentRegistry polarisAgentRegistry(
            PolarisContextManager context, AgentScopeA2aPolarisProperties a2aProperties) {
        return PolarisAgentRegistry.builder(context)
                .ttl(a2aProperties.getRegistry().getTtl())
                .build();
    }

    /**
     * Resolves A2A agent cards from Polaris.
     *
     * @param context shared Polaris SDK context
     * @return the Polaris-backed {@link AgentCardResolver}
     */
    @Bean
    @ConditionalOnMissingBean(AgentCardResolver.class)
    @ConditionalOnProperty(
            prefix = PolarisConstants.A2A_POLARIS_DISCOVERY_PREFIX,
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public AgentCardResolver polarisAgentCardResolver(PolarisContextManager context) {
        return PolarisAgentCardResolver.builder(context).build();
    }
}
