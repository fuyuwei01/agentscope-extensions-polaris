package com.tencent.ai.polaris.a2a.discovery;

/**
 * Thrown when an {@link io.a2a.spec.AgentCard} cannot be resolved for the given agent
 * name — either the service has no instances in polaris, or none carry the serialized
 * card in metadata.
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
