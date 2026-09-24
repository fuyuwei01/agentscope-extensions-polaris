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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.skill.PolarisMountedSkillRepository;
import com.tencent.polaris.ai.api.core.SkillAPI;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.exception.ServerCodes;
import com.tencent.polaris.api.plugin.skill.SkillDownloadResponse;
import com.tencent.polaris.api.pojo.DefaultServiceInstances;
import com.tencent.polaris.api.pojo.ExtendedMetadata;
import com.tencent.polaris.api.pojo.ServiceInstances;
import com.tencent.polaris.api.pojo.ServiceKey;
import com.tencent.polaris.api.rpc.InstancesResponse;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Tests for {@link AgentscopePolarisSkillAgentAutoConfiguration}.
 */
class AgentscopePolarisSkillAgentAutoConfigurationTest {

    private final AgentSkillRepository skillRepository = stubSkillRepository();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AgentscopePolarisSkillAgentAutoConfiguration.class,
                    AgentscopeAutoConfiguration.class))
            .withBean(Model.class, () -> mock(Model.class))
            .withBean(AgentSkillRepository.class, () -> skillRepository);

    @Test
    void shouldBuildAgentWithPolarisSkills() {
        runner.withPropertyValues("agentscope.agent.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ReActAgent.class);
                    assertThat(context.getBean(ReActAgent.class)).isNotNull();
                    verify(skillRepository).getAllSkills();
                });
    }

    @Test
    void shouldKeepAgentscopeAgentWhenAttachDisabled() {
        runner.withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.polaris.skill.attach-to-agent=false")
                .run(context -> {
                    assertThat(context.getBean(ReActAgent.class)).isNotNull();
                    verify(skillRepository, never()).getAllSkills();
                });
    }

    @Test
    void shouldNotCreateAgentWhenAgentDisabled() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ReActAgent.class));
    }

    @Test
    void shouldNotReplaceAgentWhenRepositoryMissing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AgentscopePolarisSkillAgentAutoConfiguration.class,
                        AgentscopeAutoConfiguration.class))
                .withBean(Model.class, () -> mock(Model.class))
                .withPropertyValues("agentscope.agent.enabled=true")
                .run(context -> assertThat(context.getBean(ReActAgent.class)).isNotNull());
    }

    /**
     * Whole mounted chain: {@code Service.extended_metadata} names the skill, SkillAPI serves its
     * zip, and the agent is built with that skill attached.
     */
    @Test
    void shouldLoadMountedSkillsIntoAgent() throws IOException {
        SkillAPI skillAPI = mock(SkillAPI.class);
        ConsumerAPI consumerAPI = mock(ConsumerAPI.class);
        when(consumerAPI.getAllInstances(any()))
                .thenReturn(serviceWithMountedSkill("default:weather"));
        when(skillAPI.downloadSkill(any()))
                .thenReturn(successZip("weather", "Check the weather", "Ask the weather service."));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AgentscopePolarisSkillAutoConfiguration.class,
                        AgentscopePolarisSkillAgentAutoConfiguration.class,
                        AgentscopeAutoConfiguration.class))
                .withBean(PolarisContextManager.class, () -> stubContextManager(skillAPI, consumerAPI))
                .withBean(Model.class, () -> mock(Model.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.polaris.skill.mounted.enabled=true",
                        "agentscope.a2a.server.card.name=demo-agent")
                .run(context -> {
                    AgentSkillRepository repo = context.getBean(AgentSkillRepository.class);
                    assertThat(repo).isInstanceOf(PolarisMountedSkillRepository.class);
                    assertThat(repo.getAllSkillNames()).containsExactly("weather");
                    assertThat(context.getBean(ReActAgent.class)).isNotNull();
                    verify(skillAPI).downloadSkill(any());
                });
    }

    private static PolarisContextManager stubContextManager(
            SkillAPI skillAPI, ConsumerAPI consumerAPI) {
        PolarisContextManager ctx = mock(PolarisContextManager.class);
        when(ctx.getNamespace()).thenReturn("default");
        when(ctx.skillAPI()).thenReturn(skillAPI);
        when(ctx.consumerAPI()).thenReturn(consumerAPI);
        return ctx;
    }

    private static InstancesResponse serviceWithMountedSkill(String mountedName) {
        List<ExtendedMetadata> extendedMetadata = List.of(ExtendedMetadata.builder()
                .type(ExtendedMetadata.ExtendedMetadataType.SKILL)
                .agentSkill(com.tencent.polaris.api.pojo.AgentSkill.builder()
                        .name(mountedName)
                        .version("")
                        .build())
                .build());
        ServiceInstances instances =
                new DefaultServiceInstances(new ServiceKey("default", "demo-agent"), List.of()) {
                    @Override
                    public List<ExtendedMetadata> getExtendedMetadata() {
                        return extendedMetadata;
                    }
                };
        return new InstancesResponse(instances, null, null);
    }

    private static SkillDownloadResponse successZip(String name, String description, String body)
            throws IOException {
        String md = "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            zout.putNextEntry(new ZipEntry(name + "/SKILL.md"));
            zout.write(md.getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
        }
        SkillDownloadResponse resp = new SkillDownloadResponse();
        resp.setCode(ServerCodes.EXECUTE_SUCCESS);
        resp.setZipContent(bos.toByteArray());
        return resp;
    }

    private static AgentSkillRepository stubSkillRepository() {
        AgentSkillRepository repo = mock(AgentSkillRepository.class);
        AgentSkill skill = AgentSkill.builder()
                .name("weather")
                .description("Check the weather")
                .skillContent("# weather")
                .source("polaris-mounted:default/demo-agent")
                .build();
        when(repo.getAllSkills()).thenReturn(List.of(skill));
        when(repo.getSource()).thenReturn("polaris-mounted:default/demo-agent");
        return repo;
    }
}
