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

import com.tencent.ai.polaris.spring.boot.config.mcp.AgentScopePolarisMcpProperties;
import com.tencent.ai.polaris.spring.boot.skill.AgentscopePolarisSkillAgentAutoConfiguration;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

import java.util.Arrays;

/** Binds before the Toolkit is injected into an Agent, including lazily created prototypes. */
@AutoConfiguration(
        after = AgentscopeMcpPolarisClientAutoConfiguration.class,
        before = {
            AgentscopeAutoConfiguration.class,
            AgentscopePolarisSkillAgentAutoConfiguration.class
        })
@ConditionalOnClass(AgentscopeAutoConfiguration.class)
@ConditionalOnBean(PolarisMcpClientFactory.class)
@ConditionalOnProperty(
        prefix = "agentscope.polaris.mcp.discovery",
        name = "attach-to-toolkit",
        havingValue = "true")
public class AgentscopeMcpPolarisToolkitAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(Toolkit.class)
    @ConditionalOnProperty(prefix = "agentscope.agent", name = "enabled", havingValue = "true")
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public Toolkit agentscopeToolkit() {
        return new Toolkit();
    }

    @Bean
    public static ToolkitBinder polarisMcpToolkitBinder(
            ObjectProvider<PolarisMcpClientFactory> factory,
            ObjectProvider<AgentScopePolarisMcpProperties> properties,
            ConfigurableListableBeanFactory beanFactory) {
        return new ToolkitBinder(factory, properties, beanFactory);
    }

    static final class ToolkitBinder implements BeanPostProcessor, SmartInitializingSingleton {
        private final ObjectProvider<PolarisMcpClientFactory> factory;
        private final ObjectProvider<AgentScopePolarisMcpProperties> properties;
        private final ConfigurableListableBeanFactory beans;

        ToolkitBinder(
                ObjectProvider<PolarisMcpClientFactory> factory,
                ObjectProvider<AgentScopePolarisMcpProperties> properties,
                ConfigurableListableBeanFactory beans) {
            this.factory = factory;
            this.properties = properties;
            this.beans = beans;
        }

        private String target() {
            String name = properties.getObject().getDiscovery().getToolkitBeanName();
            return name == null || name.isBlank() ? "agentscopeToolkit" : name;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof Toolkit toolkit
                    && (target().equals(beanName)
                            || Arrays.asList(beans.getAliases(beanName)).contains(target())))
                factory.getObject().bind(toolkit);
            if (bean instanceof ReActAgent agent) factory.getObject().retain(agent.getToolkit());
            return bean;
        }

        @Override
        public void afterSingletonsInstantiated() {
            if (!factory.getObject().hasClients()) return;
            String target = target();
            if (!beans.containsBean(target)
                    || !Toolkit.class.isAssignableFrom(beans.getType(target)))
                throw new IllegalStateException(
                        "MCP binding requires a Toolkit Bean; set toolkit-bean-name for a custom"
                                + " Toolkit");
            String configured = properties.getObject().getDiscovery().getToolkitBeanName();
            if ((configured == null || configured.isBlank())
                    && beans.getBeanNamesForType(Toolkit.class, false, false).length > 1)
                throw new IllegalStateException(
                        "Multiple Toolkits require an explicit MCP toolkit-bean-name");
        }
    }
}
