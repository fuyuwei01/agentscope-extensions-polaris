package com.tencent.ai.polaris.a2a.constant;

/**
 * Metadata keys and defaults used to carry A2A information on Polaris instances.
 */
public final class PolarisA2aConstants {

    private PolarisA2aConstants() {
    }

    /** Holds the full serialized {@link io.a2a.spec.AgentCard} JSON. */
    public static final String META_AGENT_CARD = "a2a.agent.card";

    /** Transport type of this instance endpoint (e.g. JSONRPC, HTTP+JSON). */
    public static final String META_TRANSPORT = "a2a.transport";

    /** URL path of this instance endpoint. */
    public static final String META_PATH = "a2a.path";

    /** Registry name reported to AgentScope. */
    public static final String REGISTRY_NAME = "Polaris";

    /** Default heartbeat ttl (seconds) used by {@code registerInstance}. */
    public static final int DEFAULT_TTL_SECONDS = 5;
}
