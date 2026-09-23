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

package com.tencent.ai.polaris.skill;

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.polaris.ai.api.core.SkillAPI;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.pojo.ExtendedMetadata;
import com.tencent.polaris.api.pojo.ServiceInfo;
import com.tencent.polaris.api.rpc.GetServicesRequest;
import com.tencent.polaris.api.rpc.ServicesResponse;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only {@link PolarisSkillRepository} limited to skills mounted on a Polaris service.
 *
 * <p>Mounted names and versions come from {@code Service.extended_metadata} entries of type
 * {@code EXTENDED_METADATA_SKILL}: each skill is downloaded at the {@code version} declared by
 * its {@code AgentSkill}, falling back to the repository-level version when that is blank. Reads
 * download through the parent repository; a skill that is not mounted is treated as not found.
 * Writes stay no-ops.
 */
public class PolarisMountedSkillRepository extends PolarisSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(PolarisMountedSkillRepository.class);

    private static final long DEFAULT_REFRESH_INTERVAL_MS = 30_000L;

    private final ConsumerAPI consumerAPI;
    private final String namespace;
    private final String serviceName;
    private final String version;
    private final long refreshIntervalMs;
    private final Object refreshLock = new Object();
    private volatile Map<String, String> mountedSkills = Map.of();
    private volatile long lastRefreshAtMs;

    /**
     * Creates a mounted repository from a shared Polaris context using the server-active version.
     *
     * @param context shared Polaris context (must not be null)
     * @param serviceName the agent service name registered to Polaris (must not be blank)
     */
    public PolarisMountedSkillRepository(PolarisContextManager context, String serviceName) {
        this(context, serviceName, "");
    }

    /**
     * Creates a mounted repository from a shared Polaris context.
     *
     * @param context shared Polaris context (must not be null)
     * @param serviceName the agent service name registered to Polaris (must not be blank)
     * @param version fallback skill version when the mounted metadata carries none;
     *         blank means the server-active version
     */
    public PolarisMountedSkillRepository(
            PolarisContextManager context, String serviceName, String version) {
        super(Objects.requireNonNull(context, "context"), version);
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("Service name cannot be null or empty");
        }
        this.consumerAPI = Objects.requireNonNull(context.consumerAPI(), "consumerAPI");
        this.namespace = (context.getNamespace() == null || context.getNamespace().isBlank())
                ? PolarisSkillConstants.DEFAULT_NAMESPACE : context.getNamespace().trim();
        this.serviceName = serviceName.trim();
        this.version = version == null ? "" : version.trim();
        this.refreshIntervalMs = DEFAULT_REFRESH_INTERVAL_MS;
    }

    /**
     * Test-only constructor that injects {@link SkillAPI} and {@link ConsumerAPI} directly.
     *
     * @param skillAPI the Polaris skill API (must not be null)
     * @param consumerAPI the Polaris consumer API used to read service metadata (must not be null)
     * @param namespace the Polaris namespace (blank treated as {@code default})
     * @param serviceName the agent service name registered to Polaris (must not be blank)
     * @param version fallback skill version when the mounted metadata carries none;
     *         blank means the server-active version
     */
    PolarisMountedSkillRepository(
            SkillAPI skillAPI,
            ConsumerAPI consumerAPI,
            String namespace,
            String serviceName,
            String version) {
        super(skillAPI, namespace, version);
        if (consumerAPI == null) {
            throw new IllegalArgumentException("ConsumerAPI cannot be null");
        }
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("Service name cannot be null or empty");
        }
        this.consumerAPI = consumerAPI;
        this.namespace = (namespace == null || namespace.isBlank())
                ? PolarisSkillConstants.DEFAULT_NAMESPACE : namespace.trim();
        this.serviceName = serviceName.trim();
        this.version = version == null ? "" : version.trim();
        this.refreshIntervalMs = DEFAULT_REFRESH_INTERVAL_MS;
    }

    /**
     * Builds a mounted repository from a shared Polaris context using the server-active version.
     *
     * @param context shared Polaris context
     * @param serviceName the agent service name registered to Polaris
     * @return a repository bound to {@code context}'s namespace
     */
    public static PolarisMountedSkillRepository from(
            PolarisContextManager context, String serviceName) {
        return new PolarisMountedSkillRepository(context, serviceName);
    }

    /**
     * Builds a mounted repository from a shared Polaris context.
     *
     * @param context shared Polaris context
     * @param serviceName the agent service name registered to Polaris
     * @param version fallback skill version when the mounted metadata carries none;
     *         blank means the server-active version
     * @return a repository bound to {@code context}'s namespace
     */
    public static PolarisMountedSkillRepository from(
            PolarisContextManager context, String serviceName, String version) {
        return new PolarisMountedSkillRepository(context, serviceName, version);
    }

    @Override
    public AgentSkill getSkill(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }
        String trimmed = name.trim();
        refreshMountedIfNeeded();
        String mountedVersion = mountedSkills.get(trimmed);
        if (mountedVersion == null) {
            if (log.isDebugEnabled()) {
                log.debug("Skill {} is not mounted on {}/{}", trimmed, namespace, serviceName);
            }
            throw new IllegalArgumentException("Skill not found: " + trimmed);
        }
        if (log.isDebugEnabled()) {
            log.debug("Loading mounted skill {} version={}", trimmed, mountedVersion);
        }
        return loadSkill(trimmed, mountedVersion);
    }

    @Override
    public List<String> getAllSkillNames() {
        refreshMountedIfNeeded();
        List<String> names = List.copyOf(mountedSkills.keySet());
        if (log.isDebugEnabled()) {
            log.debug("Mounted skill names on {}/{}: {}", namespace, serviceName, names);
        }
        return names;
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        refreshMountedIfNeeded();
        if (log.isDebugEnabled()) {
            log.debug("Loading {} mounted skill(s) from {}/{}",
                    mountedSkills.size(), namespace, serviceName);
        }
        List<AgentSkill> skills = new ArrayList<>();
        for (Map.Entry<String, String> mounted : mountedSkills.entrySet()) {
            try {
                if (log.isDebugEnabled()) {
                    log.debug("Loading mounted skill {} version={}",
                            mounted.getKey(), mounted.getValue());
                }
                skills.add(loadSkill(mounted.getKey(), mounted.getValue()));
            } catch (RuntimeException e) {
                log.warn("Failed to load mounted skill {} from Polaris, skipping: {}",
                        mounted.getKey(), e.getMessage());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Loaded {}/{} mounted skill(s) from {}/{}",
                    skills.size(), mountedSkills.size(), namespace, serviceName);
        }
        return List.copyOf(skills);
    }

    @Override
    public boolean skillExists(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        refreshMountedIfNeeded();
        return mountedSkills.containsKey(skillName.trim());
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo(
                PolarisSkillConstants.MOUNTED_REPO_TYPE,
                PolarisSkillConstants.LOCATION_PREFIX + namespace + "/" + serviceName,
                false);
    }

    @Override
    public String getSource() {
        return PolarisSkillConstants.MOUNTED_SOURCE_PREFIX + namespace + "/" + serviceName;
    }

    private void refreshMountedIfNeeded() {
        if (!mountedSkills.isEmpty() && lastRefreshAtMs != 0
                && System.currentTimeMillis() - lastRefreshAtMs < refreshIntervalMs) {
            if (log.isDebugEnabled()) {
                log.debug("Reusing mounted skill cache for {}/{} ({} skill(s))",
                        namespace, serviceName, mountedSkills.size());
            }
            return;
        }
        synchronized (refreshLock) {
            if (!mountedSkills.isEmpty() && lastRefreshAtMs != 0
                    && System.currentTimeMillis() - lastRefreshAtMs < refreshIntervalMs) {
                if (log.isDebugEnabled()) {
                    log.debug("Reusing mounted skill cache for {}/{} ({} skill(s))",
                            namespace, serviceName, mountedSkills.size());
                }
                return;
            }
            if (log.isDebugEnabled()) {
                log.debug("Refreshing mounted skills for {}/{}", namespace, serviceName);
            }
            mountedSkills = loadMountedSkills();
            lastRefreshAtMs = System.currentTimeMillis();
            if (log.isDebugEnabled()) {
                log.debug("Mounted skill cache for {}/{} now has {} skill(s): {}",
                        namespace, serviceName, mountedSkills.size(), mountedSkills.keySet());
            }
        }
    }

    private Map<String, String> loadMountedSkills() {
        ServiceInfo service;
        try {
            service = findService();
        } catch (PolarisException e) {
            throw new RuntimeException(
                    "Failed to load mounted skills from Polaris: " + serviceName, e);
        }
        if (service == null) {
            if (log.isDebugEnabled()) {
                log.debug("Service {}/{} not found, no mounted skills", namespace, serviceName);
            }
            return Map.of();
        }
        return resolveMountedSkills(service, version);
    }

    private ServiceInfo findService() throws PolarisException {
        GetServicesRequest req = new GetServicesRequest();
        req.setNamespace(namespace);
        req.setService(serviceName);
        if (log.isDebugEnabled()) {
            log.debug("Looking up service {}/{} for mounted skills", namespace, serviceName);
        }
        ServicesResponse resp = consumerAPI.getServices(req);
        if (resp == null || resp.getServices() == null) {
            if (log.isDebugEnabled()) {
                log.debug("GetServices returned no services for {}/{}", namespace, serviceName);
            }
            return null;
        }
        for (ServiceInfo info : resp.getServices()) {
            if (info != null && serviceName.equals(info.getService())) {
                if (log.isDebugEnabled()) {
                    int metaCount = info.getExtendedMetadata() == null
                            ? 0 : info.getExtendedMetadata().size();
                    log.debug("Found service {}/{} with {} extended metadata entries",
                            namespace, serviceName, metaCount);
                }
                return info;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Service {}/{} missing from GetServices result ({} entries)",
                    namespace, serviceName, resp.getServices().size());
        }
        return null;
    }

    /**
     * Reads mounted skills from {@code Service.extended_metadata}.
     *
     * <p>{@link com.tencent.polaris.api.pojo.AgentSkill#getName()} is {@code namespace:skillName}. Only the first
     * colon is the separator, so {@code skillName} itself may contain colons. Entries whose
     * namespace does not match this repository are skipped.
     */
    private Map<String, String> resolveMountedSkills(ServiceInfo service, String fallbackVersion) {
        List<ExtendedMetadata> metas = service.getExtendedMetadata();
        if (metas == null || metas.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("Service {}/{} has no extended metadata", namespace, serviceName);
            }
            return Map.of();
        }
        LinkedHashMap<String, String> skills = new LinkedHashMap<>();
        for (ExtendedMetadata meta : metas) {
            if (meta == null
                    || meta.getType()
                    != ExtendedMetadata.ExtendedMetadataType.SKILL) {
                continue;
            }
            com.tencent.polaris.api.pojo.AgentSkill skill = meta.getAgentSkill();
            if (skill == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Skipping EXTENDED_METADATA_SKILL with empty agent_skill on {}/{}",
                            namespace, serviceName);
                }
                continue;
            }
            MountedSkillRef ref = parseMountedSkillRef(skill);
            if (ref == null) {
                continue;
            }
            if (!this.namespace.equals(ref.namespace())) {
                log.warn("Mounted skill {} does not match repository namespace {}, skipping",
                        ref.raw(), this.namespace);
                continue;
            }
            String skillVersion = resolveSkillVersion(skill, fallbackVersion);
            if (skills.putIfAbsent(ref.name(), skillVersion) == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Accepted mounted skill name={} namespace={} version={} raw={}",
                            ref.name(), ref.namespace(), skillVersion, ref.raw());
                }
            } else if (log.isDebugEnabled()) {
                log.debug("Duplicate mounted skill {}, keeping first version {}",
                        ref.name(), skills.get(ref.name()));
            }
        }
        return Collections.unmodifiableMap(skills);
    }

    /**
     * Parses {@code namespace:skillName} from {@link com.tencent.polaris.api.pojo.AgentSkill#getName()}, falling
     * back to {@link com.tencent.polaris.api.pojo.AgentSkill#getId()} when name is blank. Splits on the first
     * colon so {@code skillName} may contain more colons.
     */
    private static MountedSkillRef parseMountedSkillRef(com.tencent.polaris.api.pojo.AgentSkill skill) {
        String raw = skill.getName();
        if (raw == null || raw.isBlank()) {
            raw = skill.getId();
        }
        if (raw == null || raw.isBlank()) {
            log.error("Invalid skill identity in EXTENDED_METADATA_SKILL: blank name and id");
            return null;
        }
        int colon = raw.indexOf(':');
        if (colon < 0) {
            log.error("Invalid skill name in EXTENDED_METADATA_SKILL, expected namespace:skillName: {}",
                    raw);
            return null;
        }
        String skillNamespace = raw.substring(0, colon).trim();
        String skillName = raw.substring(colon + 1).trim();
        if (skillNamespace.isEmpty() || skillName.isEmpty()) {
            log.error("Invalid skill name in EXTENDED_METADATA_SKILL, expected namespace:skillName: {}",
                    raw);
            return null;
        }
        return new MountedSkillRef(skillNamespace, skillName, raw);
    }

    private record MountedSkillRef(String namespace, String name, String raw) {}

    /**
     * Version carried by the mounted {@link com.tencent.polaris.api.pojo.AgentSkill} wins; when it
     * is blank the repository-level version applies, and a blank repository version means the
     * server-active version.
     */
    private static String resolveSkillVersion(
            com.tencent.polaris.api.pojo.AgentSkill skill, String fallbackVersion) {
        String skillVersion = skill.getVersion();
        return (skillVersion == null || skillVersion.isBlank())
                ? fallbackVersion : skillVersion.trim();
    }
}
