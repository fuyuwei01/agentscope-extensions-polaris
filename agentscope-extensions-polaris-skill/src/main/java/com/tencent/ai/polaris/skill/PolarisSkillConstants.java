package com.tencent.ai.polaris.skill;

/**
 * Constants used by the Polaris-backed {@link io.agentscope.core.skill.repository.AgentSkillRepository}.
 */
public final class PolarisSkillConstants {

    private PolarisSkillConstants() {}

    public static final String REPO_TYPE = "polaris";
    public static final String SOURCE_PREFIX = "polaris:";
    public static final String LOCATION_PREFIX = "namespace:";
    public static final String FORMAT_ZIP = "zip";
    public static final String DEFAULT_NAMESPACE = "default";
}
