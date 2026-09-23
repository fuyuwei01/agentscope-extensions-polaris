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
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.SkillUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Read-only {@link AgentSkillRepository} backed by polaris-java {@link SkillAPI}.
 *
 * <p>{@link #getSkill(String)} downloads a skill zip, wraps flat Polaris packages under
 * {@code name/} when needed, then builds an {@link AgentSkill} via
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
     * Creates a repository from a shared Polaris context using the server-active version.
     *
     * @param context shared Polaris context (must not be null)
     */
    public PolarisSkillRepository(PolarisContextManager context) {
        this(context, "");
    }

    /**
     * Creates a repository from a shared Polaris context.
     *
     * @param context shared Polaris context (must not be null)
     * @param version skill version; blank means the server-active version
     */
    public PolarisSkillRepository(PolarisContextManager context, String version) {
        this(context, version, List.of(), DEFAULT_LIST_LIMIT, DEFAULT_MAX_SKILLS,
                DEFAULT_LIST_REFRESH_INTERVAL_MS);
    }

    /**
     * Creates a repository from a shared Polaris context with list filters and cache settings.
     *
     * @param context shared Polaris context (must not be null)
     * @param version skill version; blank means the server-active version
     * @param names if non-empty, only these skill names are loaded (List is skipped)
     * @param listLimit page size for ListSkills
     * @param maxSkills maximum skills to load from List
     * @param listRefreshIntervalMs reuse the last ListSkills result within this interval;
     *         zip cache is keyed by {@code name#version} and is not TTL-evicted
     */
    public PolarisSkillRepository(
            PolarisContextManager context,
            String version,
            List<String> names,
            int listLimit,
            int maxSkills,
            long listRefreshIntervalMs) {
        this(requireContext(context).skillAPI(), context.getNamespace(), version, names, listLimit,
                maxSkills, listRefreshIntervalMs);
    }

    /**
     * Test-only constructor that injects {@link SkillAPI} directly.
     *
     * @param skillAPI the Polaris skill API (must not be null)
     * @param namespace the Polaris namespace (blank treated as {@code default})
     */
    PolarisSkillRepository(SkillAPI skillAPI, String namespace) {
        this(skillAPI, namespace, "");
    }

    /**
     * Test-only constructor that injects {@link SkillAPI} and a fixed version.
     *
     * @param skillAPI the Polaris skill API (must not be null)
     * @param namespace the Polaris namespace (blank treated as {@code default})
     * @param version skill version; blank means the server-active version
     */
    PolarisSkillRepository(SkillAPI skillAPI, String namespace, String version) {
        this(skillAPI, namespace, version, List.of(), DEFAULT_LIST_LIMIT, DEFAULT_MAX_SKILLS,
                DEFAULT_LIST_REFRESH_INTERVAL_MS);
    }

    /**
     * Test-only constructor that injects {@link SkillAPI} with list filters and cache settings.
     *
     * @param skillAPI the Polaris skill API (must not be null)
     * @param namespace the Polaris namespace (blank treated as {@code default})
     * @param version skill version; blank means the server-active version
     * @param names if non-empty, only these skill names are loaded (List is skipped)
     * @param listLimit page size for ListSkills
     * @param maxSkills maximum skills to load from List
     * @param listRefreshIntervalMs reuse the last ListSkills result within this interval;
     *         zip cache is keyed by {@code name#version} and is not TTL-evicted
     */
    PolarisSkillRepository(
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
        this.listLimit = listLimit > 0 ? listLimit : DEFAULT_LIST_LIMIT;
        this.maxSkills = maxSkills > 0 ? maxSkills : DEFAULT_MAX_SKILLS;
        this.listRefreshIntervalMs = listRefreshIntervalMs < 0
                ? DEFAULT_LIST_REFRESH_INTERVAL_MS : listRefreshIntervalMs;
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
        return new PolarisSkillRepository(context, version);
    }

    private static PolarisContextManager requireContext(PolarisContextManager context) {
        return Objects.requireNonNull(context, "context");
    }

    @Override
    public AgentSkill getSkill(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }
        return loadSkill(name.trim(), version);
    }

    /**
     * Downloads a skill at an explicit version and builds an {@link AgentSkill} from its zip.
     *
     * @param name the skill name (must already be trimmed and non-blank)
     * @param skillVersion skill version; blank means the server-active version
     * @return the downloaded skill
     */
    protected AgentSkill loadSkill(String name, String skillVersion) {
        if (log.isDebugEnabled()) {
            log.debug("Downloading skill {} namespace={} version={}",
                    name, namespace, skillVersion);
        }
        try {
            SkillDownloadResponse resp = downloadZip(name, skillVersion);
            if (isNotFound(resp) || resp.getZipContent() == null || resp.getZipContent().length == 0) {
                if (log.isDebugEnabled()) {
                    log.debug("Skill {} not found in namespace {} (code={}, zipBytes={})",
                            name, namespace,
                            resp == null ? -1 : resp.getCode(),
                            resp == null || resp.getZipContent() == null ? 0 : resp.getZipContent().length);
                }
                throw new IllegalArgumentException("Skill not found: " + name);
            }
            if (log.isDebugEnabled()) {
                log.debug("Downloaded skill {} namespace={} zipBytes={} code={}",
                        name, namespace, resp.getZipContent().length, resp.getCode());
            }
            return SkillUtil.createFromZip(
                    adaptZipForSkillUtil(resp.getZipContent(), name), getSource());
        } catch (PolarisException e) {
            throw new RuntimeException("Failed to load skill from Polaris: " + name, e);
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
        if (log.isDebugEnabled()) {
            log.debug("Published skill names in namespace {}: {}", namespace, names);
        }
        return List.copyOf(names);
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        refreshRefsIfNeeded();
        if (log.isDebugEnabled()) {
            log.debug("Loading {} published skill(s) from namespace {}", lastRefs.size(), namespace);
        }
        List<AgentSkill> skills = new ArrayList<>();
        for (SkillRef ref : lastRefs) {
            String key = cacheKey(ref);
            AgentSkill cached = skillCache.get(key);
            if (cached != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Using cached skill {}", key);
                }
                skills.add(cached);
                continue;
            }
            try {
                if (log.isDebugEnabled()) {
                    log.debug("Cache miss for skill {}, downloading", key);
                }
                AgentSkill skill = getSkill(ref.name());
                skillCache.put(key, skill);
                skills.add(skill);
            } catch (RuntimeException e) {
                log.warn("Failed to load skill {} from Polaris, skipping: {}", ref.name(), e.getMessage());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Loaded {}/{} published skill(s) from namespace {}",
                    skills.size(), lastRefs.size(), namespace);
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
        if (!configuredNames.isEmpty()) {
            lastRefs = toConfiguredRefs();
            if (log.isDebugEnabled()) {
                log.debug("Using configured skill names in namespace {}: {}", namespace, configuredNames);
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (lastListAtMs != 0 && now - lastListAtMs < listRefreshIntervalMs) {
            if (log.isDebugEnabled()) {
                log.debug("Reusing skill list cache for namespace {} ({} skill(s))",
                        namespace, lastRefs.size());
            }
            return;
        }
        synchronized (listLock) {
            now = System.currentTimeMillis();
            if (lastListAtMs != 0 && now - lastListAtMs < listRefreshIntervalMs) {
                if (log.isDebugEnabled()) {
                    log.debug("Reusing skill list cache for namespace {} ({} skill(s))",
                            namespace, lastRefs.size());
                }
                return;
            }
            if (log.isDebugEnabled()) {
                log.debug("Refreshing skill list for namespace {} limit={} maxSkills={}",
                        namespace, listLimit, maxSkills);
            }
            lastRefs = listSkillRefs();
            lastListAtMs = System.currentTimeMillis();
            if (log.isDebugEnabled()) {
                log.debug("Skill list cache for namespace {} now has {} skill(s)",
                        namespace, lastRefs.size());
            }
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
                if (log.isDebugEnabled()) {
                    log.debug("Listing skills namespace={} offset={} limit={}",
                            namespace, offset, listLimit);
                }
                resp = skillAPI.listSkills(req);
            } catch (PolarisException e) {
                throw new RuntimeException("Failed to list skills from Polaris", e);
            }
            if (isListFailure(resp)) {
                String info = resp.getInfo() == null ? "" : resp.getInfo();
                throw new RuntimeException("Failed to list skills from Polaris: " + info);
            }
            if (log.isDebugEnabled()) {
                int pageSize = resp.getResources() == null ? 0 : resp.getResources().size();
                log.debug("ListSkills namespace={} offset={} code={} total={} pageSize={}",
                        namespace, offset, resp.getCode(), resp.getTotal(), pageSize);
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
        if (resolved == null) {
            resolved = "";
        }
        return ref.name() + "#" + resolved;
    }

    private static boolean isListFailure(SkillListResponse resp) {
        int code = resp.getCode();
        return code != 0 && code != ServerCodes.EXECUTE_SUCCESS;
    }

    private SkillDownloadResponse downloadZip(String name, String skillVersion) throws PolarisException {
        SkillDownloadRequest req = new SkillDownloadRequest();
        req.setNamespace(namespace);
        req.setName(name);
        if (skillVersion != null && !skillVersion.isEmpty()) {
            req.setVersion(skillVersion);
        }
        req.setFormat(PolarisSkillConstants.FORMAT_ZIP);
        if (log.isDebugEnabled()) {
            log.debug("DownloadSkill namespace={} name={} version={} format={}",
                    namespace, name, req.getVersion(), PolarisSkillConstants.FORMAT_ZIP);
        }
        return skillAPI.downloadSkill(req);
    }

    private static boolean isNotFound(SkillDownloadResponse resp) {
        return resp.getCode() == ServerCodes.NOT_FOUND_RESOURCE;
    }

    /**
     * Polaris persists skill folder contents at zip root ({@code SKILL.md}, {@code assets/}...).
     * {@link SkillUtil#createFromZip} requires a single wrapper directory; wrap only when a
     * file sits at the zip root. Already-rooted or multi-root zips are left unchanged.
     */
    private static byte[] adaptZipForSkillUtil(byte[] zipBytes, String skillName) {
        Map<String, byte[]> entries;
        try {
            entries = readZipFileEntries(zipBytes);
        } catch (IOException | IllegalArgumentException e) {
            if (log.isDebugEnabled()) {
                log.debug("Leaving skill {} zip unchanged, failed to read entries: {}",
                        skillName, e.getMessage());
            }
            return zipBytes;
        }
        if (entries.isEmpty() || !hasRootLevelFile(entries)) {
            if (log.isDebugEnabled()) {
                log.debug("Skill {} zip already rooted or empty, entries={}",
                        skillName, entries.size());
            }
            return zipBytes;
        }
        try {
            if (log.isDebugEnabled()) {
                log.debug("Wrapping skill {} zip under {}/, entries={}",
                        skillName, skillName, entries.size());
            }
            return wrapEntriesUnder(entries, skillName);
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("Leaving skill {} zip unchanged, wrap failed: {}",
                        skillName, e.getMessage());
            }
            return zipBytes;
        }
    }

    private static boolean hasRootLevelFile(Map<String, byte[]> entries) {
        for (String entryName : entries.keySet()) {
            if (entryName.indexOf('/') < 0) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, byte[]> readZipFileEntries(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zipIn = new ZipInputStream(
                new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.put(normalizeZipEntryName(entry.getName()), zipIn.readAllBytes());
            }
        }
        return entries;
    }

    private static String normalizeZipEntryName(String entryName) {
        if (entryName == null || entryName.isEmpty()) {
            throw new IllegalArgumentException("Zip entry name cannot be null or empty.");
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("Zip entry name must be a relative path.");
        }
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException(
                        "Zip entry name must not contain parent directory segments.");
            }
        }
        return normalized;
    }

    private static byte[] wrapEntriesUnder(Map<String, byte[]> entries, String skillName)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zipOut = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zipOut.putNextEntry(new ZipEntry(skillName + "/" + entry.getKey()));
                zipOut.write(entry.getValue());
                zipOut.closeEntry();
            }
        }
        return output.toByteArray();
    }

    record SkillRef(String name, String version) {}
}
