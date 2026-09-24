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

import com.tencent.ai.polaris.spring.boot.constants.PolarisConstants;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import io.agentscope.spring.boot.properties.AgentProperties;
import io.agentscope.spring.boot.properties.AgentscopeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Scope;

import java.util.List;

/**
 * Replaces the default {@code agentscope.agent} {@link ReActAgent} with one that carries the
 * Polaris skills.
 *
 * <p>{@link AgentscopeAutoConfiguration#agentscopeReActAgent} builds its agent without a
 * {@link SkillBox}, so an {@link AgentSkillRepository} bean alone never reaches the agent. This
 * configuration is ordered before it and declares the same {@link ReActAgent} bean, which makes
 * the AgentScope one back off through its {@code @ConditionalOnMissingBean}.
 *
 * <p>Set {@code agentscope.polaris.skill.attach-to-agent=false} to keep the AgentScope agent.
 */
@AutoConfiguration(
        after = AgentscopePolarisSkillAutoConfiguration.class,
        before = AgentscopeAutoConfiguration.class)
@ConditionalOnClass({AgentscopeAutoConfiguration.class, ReActAgent.class, SkillBox.class})
@ConditionalOnBean(AgentSkillRepository.class)
@Conditional(AgentscopePolarisSkillAgentAutoConfiguration.OnSkillAgentEnabled.class)
@EnableConfigurationProperties(AgentscopeProperties.class)
public class AgentscopePolarisSkillAgentAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(AgentscopePolarisSkillAgentAutoConfiguration.class);

    /**
     * Default ReActAgent wired like the AgentScope one, plus a {@link SkillBox} holding the
     * skills read from Polaris.
     *
     * <p>Prototype-scoped like the bean it replaces: {@link ReActAgent.Builder#skillBox} rebinds
     * the box to the toolkit of the agent being built, so every agent needs its own box.
     *
     * @param model model bean from the AgentScope starter
     * @param memory memory bean from the AgentScope starter
     * @param toolkit toolkit bean from the AgentScope starter
     * @param properties AgentScope agent settings ({@code agentscope.agent})
     * @param skillRepository the Polaris-backed skill repository
     * @return a ReActAgent serving the Polaris skills
     */
    @Bean
    @ConditionalOnMissingBean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public ReActAgent agentscopeReActAgent(
            Model model,
            Memory memory,
            Toolkit toolkit,
            AgentscopeProperties properties,
            AgentSkillRepository skillRepository) {
        AgentProperties config = properties.getAgent();
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(config.getName())
                .sysPrompt(config.getSysPrompt())
                .model(model)
                .memory(memory)
                .toolkit(toolkit)
                .maxIters(config.getMaxIters());
        SkillBox skillBox = loadSkills(toolkit, skillRepository);
        if (skillBox != null) {
            // Registers load_skill_through_path and the SkillHook that injects the catalog.
            builder.skillBox(skillBox);
        }
        return builder.build();
    }

    /**
     * Reads the Polaris skills into a fresh {@link SkillBox}, or returns null when there is
     * nothing to attach. A repository failure leaves the agent without skills instead of
     * breaking the application.
     */
    private static SkillBox loadSkills(Toolkit toolkit, AgentSkillRepository skillRepository) {
        List<AgentSkill> skills;
        try {
            skills = skillRepository.getAllSkills();
        } catch (RuntimeException e) {
            log.warn("Failed to load skills from Polaris, building agent without skills: {}",
                    e.getMessage());
            return null;
        }
        if (skills.isEmpty()) {
            log.info("No skill available from {}", skillRepository.getSource());
            return null;
        }
        SkillBox skillBox = new SkillBox(toolkit);
        // The starter wires no code execution tools, so uploaded skill files would only pile up
        // in a fresh temp dir on every agent build.
        skillBox.setAutoUploadSkill(false);
        for (AgentSkill skill : skills) {
            skillBox.registration().skill(skill).apply();
        }
        if (log.isDebugEnabled()) {
            log.debug("Attached {} skill(s) from {} to the agent",
                    skills.size(), skillRepository.getSource());
        }
        return skillBox;
    }

    /**
     * Only replaces the agent when AgentScope creates one and the override is not turned off.
     * Nested because {@code @ConditionalOnProperty} is not repeatable on Spring Boot 3.2.
     */
    static final class OnSkillAgentEnabled extends AllNestedConditions {

        OnSkillAgentEnabled() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = "agentscope.agent", name = "enabled", havingValue = "true")
        static final class AgentEnabled {
        }

        @ConditionalOnProperty(
                prefix = PolarisConstants.SKILL_POLARIS_PREFIX,
                name = "attach-to-agent",
                havingValue = "true",
                matchIfMissing = true)
        static final class AttachEnabled {
        }
    }
}
