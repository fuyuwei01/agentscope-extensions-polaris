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
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.exception.ServerCodes;
import com.tencent.polaris.api.plugin.skill.SkillDownloadRequest;
import com.tencent.polaris.api.plugin.skill.SkillDownloadResponse;
import com.tencent.polaris.api.plugin.skill.SkillListRequest;
import com.tencent.polaris.api.plugin.skill.SkillListResponse;
import com.tencent.polaris.api.plugin.skill.SkillResource;
import com.tencent.polaris.factory.api.APIFactory;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.SkillUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only {@link AgentSkillRepository} backed by polaris-java {@link SkillAPI}.
 *
 * <p>{@link #getSkill(String)} downloads a skill zip and builds an {@link AgentSkill} via
 * {@link SkillUtil#createFromZip(byte[], String)}. {@link #getAllSkills()} lists published
 * skills (or a configured name set) and caches {@link AgentSkill} by {@code name#version}.
 * Writes are no-ops. {@link #close()} does not destroy {@link SkillAPI} because it shares
 * {@code SDKContext} with {@link PolarisContextManager}.
 */
public class PolarisSkillRepository implements AgentSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(PolarisSkillRepository.class);

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int DEFAULT_MAX_SKILLS = 100;
    private static final long DEFAULT_LIST_REFRESH_INTERVAL_MS = 30_000L;
    private static final String ACTIVE_VERSION = "active";

    private final SkillAPI skillAPI;
    private final String namespace;
    private final String version;
    private final String source;
    private final List<String> configuredNames;
    private final int listLimit;
    private final int maxSkills;
    private final long listRefreshIntervalMs;
    private final ConcurrentHashMap<String, AgentSkill> skillCache = new ConcurrentHashMap<>();
    private final Object listLock = new Object();
    private volatile List<SkillRef> lastRefs = List.of();
    private volatile long lastListAtMs;

    /**
     * Creates a repository that downloads the server-active skill version.
     *
     * @param skillAPI  the Polaris skill API (must not be null)
     * @param namespace the Polaris namespace (blank treated as {@code default})
     */
    public PolarisSkillRepository(SkillAPI skillAPI, String namespace) {
        this(skillAPI, namespace, "");
    }

    /**
     * Creates a repository that downloads a specific skill version.
     *
     * @param skillAPI  the Polaris skill API (must not be null)
     * @param namespace the Polaris namespace (blank treated as {@code default})
     * @param version   skill version; blank means the server-active version
     */
    public PolarisSkillRepository(SkillAPI skillAPI, String namespace, String version) {
        this(skillAPI, namespace, version, List.of(), DEFAULT_LIST_LIMIT, DEFAULT_MAX_SKILLS,
                DEFAULT_LIST_REFRESH_INTERVAL_MS);
    }

    /**
     * Creates a repository with list filters and cache settings.
     *
     * @param skillAPI              the Polaris skill API (must not be null)
     * @param namespace             the Polaris namespace (blank treated as {@code default})
     * @param version               skill version; blank means the server-active version
     * @param names                 if non-empty, only these skill names are loaded (List is skipped)
     * @param listLimit             page size for ListSkills
     * @param maxSkills             maximum skills to load from List
     * @param listRefreshIntervalMs reuse last refs (List or configured names) within this interval
     */
    public PolarisSkillRepository(
            SkillAPI skillAPI,
            String namespace,
            String version,
            List<String> names,
            int listLimit,
            int maxSkills,
            long listRefreshIntervalMs) {
        if (skillAPI == null) {
            throw new IllegalArgumentException("SkillAPI cannot be null");
        }
        this.skillAPI = skillAPI;
        this.namespace = (namespace == null || namespace.isBlank())
                ? PolarisSkillConstants.DEFAULT_NAMESPACE : namespace.trim();
        this.version = version == null ? "" : version.trim();
        this.source = PolarisSkillConstants.SOURCE_PREFIX + this.namespace;
        this.configuredNames = names == null ? List.of() : List.copyOf(names);
        this.listLimit = listLimit;
        this.maxSkills = maxSkills;
        this.listRefreshIntervalMs = listRefreshIntervalMs;
    }

    /**
     * Builds a repository from a shared Polaris context using the server-active version.
     *
     * @param context shared Polaris context
     * @return a repository bound to {@code context}'s namespace
     */
    public static PolarisSkillRepository from(PolarisContextManager context) {
        return from(context, "");
    }

    /**
     * Builds a repository from a shared Polaris context.
     *
     * @param context shared Polaris context
     * @param version skill version; blank means the server-active version
     * @return a repository bound to {@code context}'s namespace
     */
    public static PolarisSkillRepository from(PolarisContextManager context, String version) {
        Objects.requireNonNull(context, "context");
        SkillAPI skillAPI = APIFactory.createSkillAPIByContext(context.getSdkContext());
        return new PolarisSkillRepository(skillAPI, context.getNamespace(), version);
    }

    @Override
    public AgentSkill getSkill(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }
        try {
            SkillDownloadResponse resp = downloadZip(name.trim());
            if (isNotFound(resp) || resp.getZipContent() == null || resp.getZipContent().length == 0) {
                throw new IllegalArgumentException("Skill not found: " + name.trim());
            }
            return SkillUtil.createFromZip(resp.getZipContent(), getSource());
        } catch (PolarisException e) {
            throw new RuntimeException("Failed to load skill from Polaris: " + name.trim(), e);
        }
    }

    @Override
    public boolean skillExists(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        try {
            getSkill(skillName);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        } catch (RuntimeException e) {
            log.warn("Error checking skill existence for {}: {}", skillName, e.getMessage());
            return false;
        }
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo(
                PolarisSkillConstants.REPO_TYPE,
                PolarisSkillConstants.LOCATION_PREFIX + namespace,
                false);
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public void setWriteable(boolean writeable) {
        log.warn("PolarisSkillRepository is read-only, set writeable operation ignored");
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    @Override
    public List<String> getAllSkillNames() {
        refreshRefsIfNeeded();
        List<String> names = new ArrayList<>(lastRefs.size());
        for (SkillRef ref : lastRefs) {
            names.add(ref.name());
        }
        return List.copyOf(names);
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        refreshRefsIfNeeded();
        List<AgentSkill> skills = new ArrayList<>();
        for (SkillRef ref : lastRefs) {
            String key = cacheKey(ref);
            AgentSkill cached = skillCache.get(key);
            if (cached != null) {
                skills.add(cached);
                continue;
            }
            try {
                AgentSkill skill = getSkill(ref.name());
                skillCache.put(key, skill);
                skills.add(skill);
            } catch (RuntimeException e) {
                log.warn("Failed to load skill {} from Polaris, skipping: {}", ref.name(), e.getMessage());
            }
        }
        return List.copyOf(skills);
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        log.warn("PolarisSkillRepository is read-only, save operation ignored");
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        log.warn("PolarisSkillRepository is read-only, delete operation ignored");
        return false;
    }

    @Override
    public void close() {
        // SkillAPI shares SDKContext with PolarisContextManager; do not destroy it.
    }

    private void refreshRefsIfNeeded() {
        long now = System.currentTimeMillis();
        if (lastListAtMs != 0 && now - lastListAtMs < listRefreshIntervalMs) {
            return;
        }
        synchronized (listLock) {
            now = System.currentTimeMillis();
            if (lastListAtMs != 0 && now - lastListAtMs < listRefreshIntervalMs) {
                return;
            }
            if (!configuredNames.isEmpty()) {
                List<SkillRef> refs = toConfiguredRefs();
                lastRefs = refs;
                evictCacheForRefs(refs);
                lastListAtMs = System.currentTimeMillis();
                return;
            }
            lastRefs = listSkillRefs();
            lastListAtMs = System.currentTimeMillis();
        }
    }

    private void evictCacheForRefs(List<SkillRef> refs) {
        for (SkillRef ref : refs) {
            skillCache.remove(cacheKey(ref));
        }
    }

    private List<SkillRef> toConfiguredRefs() {
        List<SkillRef> refs = new ArrayList<>(configuredNames.size());
        for (String name : configuredNames) {
            refs.add(new SkillRef(name, version));
        }
        return List.copyOf(refs);
    }

    private List<SkillRef> listSkillRefs() {
        List<SkillRef> refs = new ArrayList<>();
        int offset = 0;
        int total = Integer.MAX_VALUE;
        while (refs.size() < maxSkills) {
            SkillListRequest req = new SkillListRequest();
            req.setNamespace(namespace);
            req.setOffset(offset);
            req.setLimit(listLimit);
            SkillListResponse resp;
            try {
                resp = skillAPI.listSkills(req);
            } catch (PolarisException e) {
                throw new RuntimeException("Failed to list skills from Polaris", e);
            }
            if (isListFailure(resp)) {
                String info = resp.getInfo() == null ? "" : resp.getInfo();
                throw new RuntimeException("Failed to list skills from Polaris: " + info);
            }
            List<SkillResource> resources = resp.getResources();
            if (resources == null || resources.isEmpty()) {
                break;
            }
            total = resp.getTotal();
            for (SkillResource resource : resources) {
                if (refs.size() >= maxSkills) {
                    break;
                }
                if (resource == null || resource.getName() == null || resource.getName().isBlank()) {
                    continue;
                }
                refs.add(toRef(resource));
            }
            if (resources.size() == listLimit && refs.size() < maxSkills && refs.size() < total) {
                offset += listLimit;
                continue;
            }
            break;
        }
        return List.copyOf(refs);
    }

    private SkillRef toRef(SkillResource resource) {
        String resolved = version;
        if (resolved.isEmpty() && resource.getVersionInfo() != null
                && resource.getVersionInfo().getActiveVersion() != null) {
            resolved = resource.getVersionInfo().getActiveVersion();
        }
        return new SkillRef(resource.getName(), resolved);
    }

    private static String cacheKey(SkillRef ref) {
        String resolved = ref.version();
        if (resolved == null || resolved.isEmpty()) {
            resolved = ACTIVE_VERSION;
        }
        return ref.name() + "#" + resolved;
    }

    private static boolean isListFailure(SkillListResponse resp) {
        int code = resp.getCode();
        return code != 0 && code != ServerCodes.EXECUTE_SUCCESS;
    }

    private SkillDownloadResponse downloadZip(String name) throws PolarisException {
        SkillDownloadRequest req = new SkillDownloadRequest();
        req.setNamespace(namespace);
        req.setName(name);
        if (!version.isEmpty()) {
            req.setVersion(version);
        }
        req.setFormat(PolarisSkillConstants.FORMAT_ZIP);
        return skillAPI.downloadSkill(req);
    }

    private static boolean isNotFound(SkillDownloadResponse resp) {
        return resp.getCode() == ServerCodes.NOT_FOUND_RESOURCE;
    }

    record SkillRef(String name, String version) {}
}
