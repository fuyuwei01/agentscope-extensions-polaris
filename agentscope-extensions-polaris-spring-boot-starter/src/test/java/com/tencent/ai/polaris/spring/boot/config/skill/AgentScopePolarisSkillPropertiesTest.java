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

package com.tencent.ai.polaris.spring.boot.config.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.tencent.ai.polaris.spring.boot.config.a2a.PolarisA2aRegistryProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentScopePolarisSkillPropertiesTest {

    @Test
    void storesConfiguredValues() {
        AgentScopePolarisSkillProperties properties = new AgentScopePolarisSkillProperties();
        PolarisSkillMountedProperties mounted = new PolarisSkillMountedProperties();
        mounted.setEnabled(true);
        mounted.setServiceName("demo-agent");

        properties.setEnabled(false);
        properties.setAddress("127.0.0.1:8094");
        properties.setVersion("1.2.3");
        properties.setNames(List.of("weather"));
        properties.setListLimit(20);
        properties.setMaxSkills(30);
        properties.setListRefreshIntervalMs(5_000L);
        properties.setMounted(mounted);
        properties.setAttachToAgent(false);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getAddress()).isEqualTo("127.0.0.1:8094");
        assertThat(properties.getVersion()).isEqualTo("1.2.3");
        assertThat(properties.getNames()).containsExactly("weather");
        assertThat(properties.getListLimit()).isEqualTo(20);
        assertThat(properties.getMaxSkills()).isEqualTo(30);
        assertThat(properties.getListRefreshIntervalMs()).isEqualTo(5_000L);
        assertThat(properties.getMounted().isEnabled()).isTrue();
        assertThat(properties.getMounted().getServiceName()).isEqualTo("demo-agent");
        assertThat(properties.isAttachToAgent()).isFalse();
    }

    @Test
    void registryPropertiesStoreEnabledAndTtl() {
        PolarisA2aRegistryProperties registry = new PolarisA2aRegistryProperties();
        registry.setEnabled(false);
        registry.setTtl(15);

        assertThat(registry.isEnabled()).isFalse();
        assertThat(registry.getTtl()).isEqualTo(15);
    }
}
