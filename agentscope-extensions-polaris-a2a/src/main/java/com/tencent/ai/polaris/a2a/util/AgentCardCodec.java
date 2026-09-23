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

package com.tencent.ai.polaris.a2a.util;

import com.fasterxml.jackson.core.type.TypeReference;
import io.a2a.spec.AgentCard;
import io.a2a.util.Utils;

/**
 * Serializes and deserializes {@link AgentCard} to/from JSON, delegating to
 * {@link io.a2a.util.Utils} so the wire format matches the rest of the A2A SDK.
 */
public final class AgentCardCodec {

    private static final TypeReference<AgentCard> CARD_TYPE = new TypeReference<>() {
    };

    private AgentCardCodec() {
    }

    public static String toJson(AgentCard agentCard) {
        return Utils.toJsonString(agentCard);
    }

    public static AgentCard fromJson(String json) {
        AgentCard agentCard;
        try {
            agentCard = Utils.unmarshalFrom(json, CARD_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize AgentCard JSON: " + e.getMessage(), e);
        }
        if (agentCard == null) {
            throw new IllegalArgumentException("Failed to deserialize AgentCard JSON: decoded value is null");
        }
        return agentCard;
    }
}
