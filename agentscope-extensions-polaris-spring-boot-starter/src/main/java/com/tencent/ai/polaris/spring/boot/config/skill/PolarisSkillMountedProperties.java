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

/**
 * Mounted-skill repository settings ({@code agentscope.polaris.skill.mounted}).
 */
public class PolarisSkillMountedProperties {

    /**
     * When true, bind {@link com.tencent.ai.polaris.skill.PolarisMountedSkillRepository}
     * instead of listing all published skills.
     */
    private boolean enabled = false;

    /**
     * Optional Polaris service override. When blank, falls back to
     * {@code agentscope.a2a.server.card.name}, then {@code agentscope.agent.name}.
     */
    private String serviceName = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }
}
