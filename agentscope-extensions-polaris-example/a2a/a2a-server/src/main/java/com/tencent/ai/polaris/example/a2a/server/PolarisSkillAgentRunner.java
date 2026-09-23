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

package com.tencent.ai.polaris.example.a2a.server;

import com.tencent.ai.polaris.example.a2a.server.tools.ExampleTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.executor.runner.BaseReActAgentRunner;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Builds one {@link ReActAgent} per A2A request, each with its own {@link Toolkit} and
 * {@link SkillBox} filled from Polaris.
 *
 * <p>{@link ReActAgent.Builder#skillBox(SkillBox)} rebinds the given {@link SkillBox} to the
 * toolkit copy of the agent being built, so sharing one box across concurrently built agents lets
 * skill activation land on the wrong toolkit. Building both per request avoids that and refreshes
 * the catalog on every request; the repositories cache list results and skill zips, so repeated
 * builds normally do not hit the Polaris server.
 *
 * <p>A repository failure is logged and leaves the agent without skills, so the A2A endpoint keeps
 * serving when the SkillAPI is unreachable.
 */
final class PolarisSkillAgentRunner extends BaseReActAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(PolarisSkillAgentRunner.class);

    private final String agentName;
    private final String description;
    private final String sysPrompt;
    private final Model model;
    private final int maxIters;
    private final AgentSkillRepository skillRepository;

    /**
     * @param skillRepository source of skills, or null to run without skills
     */
    PolarisSkillAgentRunner(
            String agentName,
            String description,
            String sysPrompt,
            Model model,
            int maxIters,
            AgentSkillRepository skillRepository) {
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.description = description;
        this.sysPrompt = sysPrompt;
        this.model = Objects.requireNonNull(model, "model");
        this.maxIters = maxIters;
        this.skillRepository = skillRepository;
    }

    @Override
    protected ReActAgent buildReActAgent() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExampleTools());
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(agentName)
                .description(description)
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .maxIters(maxIters);
        SkillBox skillBox = loadSkills(toolkit);
        if (skillBox != null) {
            // Registers load_skill_through_path and the SkillHook that injects the catalog.
            builder.skillBox(skillBox);
        }
        return builder.build();
    }

    private SkillBox loadSkills(Toolkit toolkit) {
        if (skillRepository == null) {
            return null;
        }
        List<AgentSkill> skills;
        try {
            skills = skillRepository.getAllSkills();
        } catch (RuntimeException e) {
            log.warn("Failed to load skills from Polaris, serving without skills: {}", e.getMessage());
            return null;
        }
        if (skills.isEmpty()) {
            log.info("No skill available from {}", skillRepository.getSource());
            return null;
        }
        SkillBox skillBox = new SkillBox(toolkit);
        // Default-on upload writes skill files to a fresh temp dir on every build, which this
        // example never reads because code execution is off.
        skillBox.setAutoUploadSkill(false);
        for (AgentSkill skill : skills) {
            skillBox.registration().skill(skill).apply();
        }
        log.info("Loaded {} skill(s) from {}", skills.size(), skillRepository.getSource());
        return skillBox;
    }
}
