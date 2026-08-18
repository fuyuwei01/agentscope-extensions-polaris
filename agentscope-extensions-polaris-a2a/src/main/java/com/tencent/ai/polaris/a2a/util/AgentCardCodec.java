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
        try {
            return Utils.unmarshalFrom(json, CARD_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize AgentCard from metadata: " + e.getMessage(), e);
        }
    }
}
