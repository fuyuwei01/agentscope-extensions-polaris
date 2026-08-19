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

package com.tencent.ai.polaris.spring.boot.constants;

/**
 * Configuration-prefix constants for the Polaris Spring Boot starter.
 *
 * <p>Connection settings live under {@code agentscope.polaris}. Feature-specific settings
 * (currently A2A registry/discovery) are nested under {@code agentscope.polaris.a2a}.
 */
public final class PolarisConstants {

    private PolarisConstants() {
    }

    /** {@code agentscope.polaris} — shared connection config (address/namespace/token). */
    public static final String POLARIS_PREFIX = "agentscope.polaris";

    /** {@code agentscope.polaris.a2a} — A2A total switch + registry/discovery behavior. */
    public static final String A2A_POLARIS_PREFIX = POLARIS_PREFIX + ".a2a";

    /** {@code agentscope.polaris.a2a.registry} — registry behavior (enabled, ttl). */
    public static final String A2A_POLARIS_REGISTRY_PREFIX = A2A_POLARIS_PREFIX + ".registry";

    /** {@code agentscope.polaris.a2a.discovery} — discovery behavior (enabled, refresh-interval-ms). */
    public static final String A2A_POLARIS_DISCOVERY_PREFIX = A2A_POLARIS_PREFIX + ".discovery";
}
