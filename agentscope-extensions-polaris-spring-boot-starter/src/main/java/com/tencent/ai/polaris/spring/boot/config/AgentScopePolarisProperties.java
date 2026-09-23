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

package com.tencent.ai.polaris.spring.boot.config;

import com.tencent.ai.polaris.core.PolarisServerProperties;
import com.tencent.ai.polaris.spring.boot.constants.PolarisConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared Polaris connection properties bound from {@code agentscope.polaris}.
 *
 * <p>Example:
 * <pre>{@code
 * agentscope:
 *   polaris:
 *     enabled: true
 *     address: 127.0.0.1:8091
 *     skill-address: 127.0.0.1:8094
 *     namespace: default
 *     token: <service_token>
 * }</pre>
 */
@ConfigurationProperties(prefix = PolarisConstants.POLARIS_PREFIX)
public class AgentScopePolarisProperties extends PolarisServerProperties {
}
