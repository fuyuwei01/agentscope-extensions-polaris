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

public final class PolarisSkillClientExample {
    public static void main(String[] args) throws Exception {
        String address = System.getenv().getOrDefault("POLARIS_ADDRESS", "127.0.0.1:8091");
        try (PolarisContextManager context = PolarisContextManager.fromAddress(address)) {
            PolarisSkillRepository repo = PolarisSkillRepository.from(context);
            List<String> names = repo.getAllSkillNames();
            System.out.println("skills: " + names);
            if (!names.isEmpty()) {
                AgentSkill skill = repo.getSkill(names.get(0));
                System.out.println("loaded " + skill.getName() + " resources=" + skill.getResourcePaths());
            }
        }
    }
}
