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

package com.tencent.ai.polaris.spring.boot.config.a2a;

import com.tencent.ai.polaris.spring.boot.constants.PolarisConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot properties for AgentScope A2A Polaris integration
 *
 */
@ConfigurationProperties(prefix = PolarisConstants.A2A_POLARIS_PREFIX)
public class AgentScopeA2aPolarisProperties {

    /** Total switch; off disables both registry and discovery beans. Default {@code true}. */
    private boolean enabled = true;

    private PolarisA2aRegistryProperties registry = new PolarisA2aRegistryProperties();

    private PolarisA2aDiscoveryProperties discovery = new PolarisA2aDiscoveryProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public PolarisA2aRegistryProperties getRegistry() {
        return registry;
    }

    public void setRegistry(PolarisA2aRegistryProperties registry) {
        this.registry = registry;
    }

    public PolarisA2aDiscoveryProperties getDiscovery() {
        return discovery;
    }

    public void setDiscovery(PolarisA2aDiscoveryProperties discovery) {
        this.discovery = discovery;
    }
}
