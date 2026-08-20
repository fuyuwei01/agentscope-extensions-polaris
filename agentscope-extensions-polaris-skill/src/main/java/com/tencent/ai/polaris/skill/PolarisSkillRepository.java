package com.tencent.ai.polaris.skill;

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.polaris.ai.api.core.SkillAPI;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.exception.ServerCodes;
import com.tencent.polaris.api.plugin.skill.SkillDownloadRequest;
import com.tencent.polaris.api.plugin.skill.SkillDownloadResponse;
import com.tencent.polaris.factory.api.APIFactory;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.SkillUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Read-only {@link AgentSkillRepository} backed by polaris-java {@link SkillAPI}.
 *
 * <p>{@link #getSkill(String)} downloads a skill zip and builds an {@link AgentSkill} via
 * {@link SkillUtil#createFromZip(byte[], String)}. Listing is stubbed as empty in this
 * version; writes are no-ops. {@link #close()} does not destroy {@link SkillAPI} because it
 * shares {@code SDKContext} with {@link PolarisContextManager}.
 */
public class PolarisSkillRepository implements AgentSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(PolarisSkillRepository.class);

    private final SkillAPI skillAPI;
    private final String namespace;
    private final String version;
    private final String source;

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
        if (skillAPI == null) {
            throw new IllegalArgumentException("SkillAPI cannot be null");
        }
        this.skillAPI = skillAPI;
        this.namespace = (namespace == null || namespace.isBlank())
                ? PolarisSkillConstants.DEFAULT_NAMESPACE : namespace.trim();
        this.version = version == null ? "" : version.trim();
        this.source = PolarisSkillConstants.SOURCE_PREFIX + this.namespace;
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
        log.warn("PolarisSkillRepository listing is not implemented, getAllSkillNames returns empty list");
        return List.of();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        log.warn("PolarisSkillRepository listing is not implemented, getAllSkills returns empty list");
        return List.of();
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
}
