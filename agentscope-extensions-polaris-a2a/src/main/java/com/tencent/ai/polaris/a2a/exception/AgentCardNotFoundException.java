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

package com.tencent.ai.polaris.a2a.exception;

/**
 * Thrown when an {@link io.a2a.spec.AgentCard} cannot be resolved for the given agent
 * name — either the service has no usable instances in polaris, or every candidate
 * failed URL / HTTP / decode checks.
 */
public class AgentCardNotFoundException extends RuntimeException {

    private final String agentName;

    public AgentCardNotFoundException(String agentName) {
        super("AgentCard not found for agent: " + agentName);
        this.agentName = agentName;
    }

    public AgentCardNotFoundException(String agentName, Throwable cause) {
        super("AgentCard not found for agent: " + agentName, cause);
        this.agentName = agentName;
    }

    public String getAgentName() {
        return agentName;
    }
}
