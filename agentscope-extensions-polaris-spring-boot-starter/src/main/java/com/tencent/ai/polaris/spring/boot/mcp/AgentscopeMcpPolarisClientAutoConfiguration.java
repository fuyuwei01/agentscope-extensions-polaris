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

package com.tencent.ai.polaris.spring.boot.mcp;

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.mcp.discovery.PolarisMcpServerManager;
import com.tencent.ai.polaris.spring.boot.AgentscopePolarisAutoConfiguration;
import com.tencent.ai.polaris.spring.boot.config.mcp.AgentScopePolarisMcpProperties;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/** Adds discovery/client factories; never creates or registers an MCP Server. */
@AutoConfiguration(after = AgentscopePolarisAutoConfiguration.class)
@Conditional(AgentscopeMcpPolarisClientAutoConfiguration.Enabled.class)
@ConditionalOnBean(PolarisContextManager.class)
@EnableConfigurationProperties(AgentScopePolarisMcpProperties.class)
public class AgentscopeMcpPolarisClientAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public PolarisMcpServerManager polarisMcpServerManager(PolarisContextManager context) {
        return new PolarisMcpServerManager(context);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public PolarisMcpClientFactory polarisMcpClientFactory(
            PolarisMcpServerManager manager, AgentScopePolarisMcpProperties properties) {
        return new PolarisMcpClientFactory(manager, properties);
    }

    static final class Enabled extends AllNestedConditions {
        Enabled() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(
                prefix = "agentscope.polaris",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        static class Polaris {}

        @ConditionalOnProperty(
                prefix = "agentscope.polaris.mcp",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        static class Mcp {}

        @ConditionalOnProperty(
                prefix = "agentscope.polaris.mcp.discovery",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        static class Discovery {}
    }
}
