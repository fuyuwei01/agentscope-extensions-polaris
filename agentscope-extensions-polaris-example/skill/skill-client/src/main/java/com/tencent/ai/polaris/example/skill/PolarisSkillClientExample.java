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

package com.tencent.ai.polaris.example.skill;

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.skill.PolarisSkillRepository;
import io.agentscope.core.skill.AgentSkill;
import java.util.List;

/**
 * Lists published skills from Polaris SkillAPI, then loads one zip.
 *
 * <p>Environment / system properties:
 * <ul>
 *   <li>{@code POLARIS_ADDRESS} — discovery/registry, default {@code 127.0.0.1:8091}
 *   <li>{@code POLARIS_SKILL_ADDRESS} — optional SkillAPI address; when unset, the
 *       discovery host is reused with port {@code 8094}
 *   <li>{@code POLARIS_SKILL_NAME} / {@code -Dskill.name} — skill to load; empty loads the first listed name
 * </ul>
 */
public final class PolarisSkillClientExample {

    /**
     * Lists published skills, then loads the selected or first skill.
     *
     * @param args ignored
     * @throws Exception if Polaris SkillAPI access fails
     */
    public static void main(String[] args) throws Exception {
        String address = firstNonBlank(System.getenv("POLARIS_ADDRESS"), "127.0.0.1:8091");
        String skillAddress = System.getenv("POLARIS_SKILL_ADDRESS");
        String skillName = firstNonBlank(
                System.getenv("POLARIS_SKILL_NAME"),
                System.getProperty("skill.name"));

        try (PolarisContextManager context = createContext(address, skillAddress)) {
            PolarisSkillRepository repo = PolarisSkillRepository.from(context);
            System.out.println("listing skills");
            List<String> names = repo.getAllSkillNames();
            System.out.println("discovery=" + address + " skillApi=" + context.getProperties().skillAddressList());
            System.out.println("skills: " + names);
            if (names.isEmpty()) {
                return;
            }
            String toLoad = skillName.isEmpty() ? names.get(0) : skillName;
            AgentSkill skill = repo.getSkill(toLoad);
            System.out.println(
                    "loaded " + skill.getName()
                            + " description=" + skill.getDescription()
                            + " resourceCount=" + skill.getResourcePaths().size());
        }
    }

    private static PolarisContextManager createContext(String address, String skillAddress) {
        if (skillAddress != null && !skillAddress.isBlank()) {
            return PolarisContextManager.fromAddress(address, skillAddress.trim());
        }
        return PolarisContextManager.fromAddress(address);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private PolarisSkillClientExample() {}
}
