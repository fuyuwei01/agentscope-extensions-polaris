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

import com.tencent.ai.polaris.spring.boot.constants.PolarisConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill repository settings bound from {@code agentscope.polaris.skill}.
 */
@ConfigurationProperties(prefix = PolarisConstants.SKILL_POLARIS_PREFIX)
public class AgentScopePolarisSkillProperties {

    private boolean enabled = true;
    private String address = "";
    private String version = "";
    private List<String> names = new ArrayList<>();
    private int listLimit = 50;
    private int maxSkills = 100;
    private long listRefreshIntervalMs = 30000;
    private PolarisSkillMountedProperties mounted = new PolarisSkillMountedProperties();
    /**
     * Whether the default {@code agentscope.agent} ReActAgent is replaced by one carrying the
     * Polaris skills.
     */
    private boolean attachToAgent = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public List<String> getNames() {
        return names;
    }

    public void setNames(List<String> names) {
        this.names = names;
    }

    public int getListLimit() {
        return listLimit;
    }

    public void setListLimit(int listLimit) {
        this.listLimit = listLimit;
    }

    public int getMaxSkills() {
        return maxSkills;
    }

    public void setMaxSkills(int maxSkills) {
        this.maxSkills = maxSkills;
    }

    public long getListRefreshIntervalMs() {
        return listRefreshIntervalMs;
    }

    public void setListRefreshIntervalMs(long listRefreshIntervalMs) {
        this.listRefreshIntervalMs = listRefreshIntervalMs;
    }

    public PolarisSkillMountedProperties getMounted() {
        return mounted;
    }

    public void setMounted(PolarisSkillMountedProperties mounted) {
        this.mounted = mounted;
    }

    public boolean isAttachToAgent() {
        return attachToAgent;
    }

    public void setAttachToAgent(boolean attachToAgent) {
        this.attachToAgent = attachToAgent;
    }
}
