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

package com.tencent.ai.polaris.a2a.constant;

/**
 * Metadata keys and defaults used to carry A2A information on Polaris instances.
 *
 * <p>The serialized {@link io.a2a.spec.AgentCard} is stored under {@link #META_AGENT_CARD}.
 * A single Polaris metadata value may be at most {@link #MAX_METADATA_VALUE_CHARS} characters
 * (64KiB − 1).
 */
public final class PolarisA2aConstants {

    private PolarisA2aConstants() {
    }

    public static final String META_AGENT_NAME = "ai-agent-name";

    /** Holds the full serialized {@link io.a2a.spec.AgentCard} JSON. */
    public static final String META_AGENT_CARD = "ai-agent-card";

    /** A2A protocol version declared by {@link io.a2a.spec.AgentCard#protocolVersion()}. */
    public static final String META_A2A_VERSION = "ai-a2a-version";

    /** Registry name reported to AgentScope. */
    public static final String REGISTRY_NAME = "Polaris";

    /** Default Polaris registration port, independent of the A2A transport endpoint. */
    public static final int DEFAULT_REGISTRATION_PORT = 1;

    /** Default heartbeat ttl (seconds) used by {@code registerInstance}. */
    public static final int DEFAULT_TTL_SECONDS = 5;

    /**
     * Maximum length of a single Polaris instance metadata value (characters).
     * Equal to 64KiB − 1.
     */
    public static final int MAX_METADATA_VALUE_CHARS = 65_535;
}
