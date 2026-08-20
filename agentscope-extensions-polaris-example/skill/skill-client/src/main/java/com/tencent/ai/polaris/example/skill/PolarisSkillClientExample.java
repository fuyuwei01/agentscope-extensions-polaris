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
