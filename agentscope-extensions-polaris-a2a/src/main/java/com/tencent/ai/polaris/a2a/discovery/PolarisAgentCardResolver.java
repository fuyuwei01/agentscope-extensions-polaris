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

package com.tencent.ai.polaris.a2a.discovery;

import com.tencent.ai.polaris.a2a.constant.PolarisA2aConstants;
import com.tencent.ai.polaris.a2a.exception.AgentCardNotFoundException;
import com.tencent.ai.polaris.a2a.util.AgentCardCodec;
import com.tencent.ai.polaris.core.PolarisContextManager;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.pojo.Instance;
import com.tencent.polaris.api.rpc.GetHealthyInstancesRequest;
import com.tencent.polaris.api.rpc.InstancesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentScope A2A card resolver backed by polaris-java.
 *
 * <p>Service mapping matches {@link com.tencent.ai.polaris.a2a.registry.PolarisAgentRegistry}:
 * the agent name is the Polaris service name. Discovery is pull-based —
 * {@link #getAgentCard(String)} calls {@code ConsumerAPI.getHealthyInstances} and rebuilds
 * the card from instance metadata {@link PolarisA2aConstants#META_AGENT_CARD} (full
 * AgentCard JSON written at register time). Unhealthy and isolated instances are
 * already excluded by that API.
 *
 * <p>Instance stickiness: the first successful lookup picks one usable instance
 * ({@code host:port}) and later calls stay on that identity. Each later lookup still
 * reads the healthy list: if the sticky instance is present with a usable card, the
 * freshly decoded JSON is cached and returned (so in-place card updates on the same
 * instance are visible). If it has left the healthy set (or its metadata is no longer
 * usable), another instance is chosen, an info log is written, and the new identity is
 * cached. Polaris SDK errors after a successful pick keep the cached card (warn log).
 *
 * <p>Candidates are skipped when they are missing {@code a2a.agent.card}, fail JSON
 * decode, or carry a card whose {@code name} does not match the requested agent.
 * When picking (first time or failover) the first remaining instance in the healthy
 * list wins.
 *
 * <p>A true miss throws {@link AgentCardNotFoundException}; Polaris SDK errors on a
 * cold miss throw {@link IllegalStateException} so callers can retry.
 */
public class PolarisAgentCardResolver implements AgentCardResolver, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PolarisAgentCardResolver.class);

    private final ConsumerAPI consumerAPI;
    private final String namespace;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Builds a resolver sharing the given Polaris context.
     *
     * @param context shared SDK context (namespace + {@link ConsumerAPI})
     */
    public PolarisAgentCardResolver(PolarisContextManager context) {
        this(context.consumerAPI(), context.getNamespace());
    }

    PolarisAgentCardResolver(ConsumerAPI consumerAPI, String namespace) {
        this.consumerAPI = Objects.requireNonNull(consumerAPI, "consumerAPI");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    /**
     * Resolve the AgentCard for {@code agentName}, sticky to the previously chosen instance
     * until that instance is no longer healthy / usable.
     *
     * @param agentName Polaris service name / {@link AgentCard#name()}
     * @return the sticky instance's latest card, or a newly chosen card after failover
     * @throws AgentCardNotFoundException if no instance has a usable card
     * @throws IllegalStateException if the Polaris lookup itself fails and nothing is cached
     */
    @Override
    public AgentCard getAgentCard(String agentName) {
        Objects.requireNonNull(agentName, "agentName");
        CacheEntry current = cache.get(agentName);
        Instance[] instances;
        try {
            instances = loadHealthyInstances(agentName);
        } catch (PolarisException e) {
            if (current != null) {
                log.warn("Failed to refresh instances for agent '{}'; keeping instance {}:{}: {}",
                        agentName, current.host(), current.port(), e.getMessage());
                return current.card();
            }
            throw new IllegalStateException(
                    "Failed to discover agent '" + agentName + "' from polaris: " + e.getMessage(), e);
        }
        DecodedInstance sticky = current == null ? null : findStickyUsable(current, instances, agentName);
        if (sticky != null) {
            CacheEntry refreshed = new CacheEntry(sticky.card(), current.host(), current.port());
            cache.put(agentName, refreshed);
            return refreshed.card();
        }
        DecodedInstance picked = pickFirstUsable(instances, agentName);
        if (picked == null) {
            throw new AgentCardNotFoundException(agentName);
        }
        CacheEntry next = new CacheEntry(picked.card(), picked.instance().getHost(), picked.instance().getPort());
        if (current != null) {
            log.info("Agent '{}' instance {}:{} is unavailable; switching to {}:{}",
                    agentName, current.host(), current.port(), next.host(), next.port());
        } else {
            log.debug("Resolved agent '{}' card from instance {}:{}",
                    agentName, next.host(), next.port());
        }
        cache.put(agentName, next);
        return next.card();
    }

    private Instance[] loadHealthyInstances(String agentName) throws PolarisException {
        GetHealthyInstancesRequest req = new GetHealthyInstancesRequest();
        req.setNamespace(namespace);
        req.setService(agentName);
        InstancesResponse resp = consumerAPI.getHealthyInstances(req);
        Instance[] instances = resp.getInstances();
        return instances == null ? new Instance[0] : instances;
    }

    private static DecodedInstance findStickyUsable(CacheEntry current, Instance[] instances, String agentName) {
        for (Instance inst : instances) {
            if (!current.host().equals(inst.getHost()) || current.port() != inst.getPort()) {
                continue;
            }
            AgentCard card = decodeUsableCard(inst, agentName);
            return card == null ? null : new DecodedInstance(inst, card);
        }
        return null;
    }

    private static DecodedInstance pickFirstUsable(Instance[] instances, String agentName) {
        for (Instance inst : instances) {
            AgentCard card = decodeUsableCard(inst, agentName);
            if (card != null) {
                return new DecodedInstance(inst, card);
            }
        }
        return null;
    }

    private static AgentCard decodeUsableCard(Instance inst, String agentName) {
        Map<String, String> metadata = inst.getMetadata();
        if (metadata == null) {
            return null;
        }
        String json = metadata.get(PolarisA2aConstants.META_AGENT_CARD);
        if (json == null || json.isBlank()) {
            return null;
        }
        AgentCard card;
        try {
            card = AgentCardCodec.fromJson(json);
        } catch (IllegalArgumentException e) {
            log.warn("Skipping malformed agent card for '{}' from instance {}:{}",
                    agentName, inst.getHost(), inst.getPort(), e);
            return null;
        }
        if (card.name() != null && !agentName.equals(card.name())) {
            log.warn("Skipping agent card named '{}' while resolving '{}' from instance {}:{}",
                    card.name(), agentName, inst.getHost(), inst.getPort());
            return null;
        }
        return card;
    }

    /** Drop the cached card so the next lookup re-selects an instance. */
    public void invalidate(String agentName) {
        cache.remove(agentName);
    }

    /** Clear the entire cache. */
    public void invalidateAll() {
        cache.clear();
    }

    /** Releases the in-memory card cache; does not close the shared {@link ConsumerAPI}. */
    @Override
    public void close() {
        cache.clear();
    }

    public static Builder builder(PolarisContextManager context) {
        return new Builder(context);
    }

    /** Fluent factory. */
    public static final class Builder {

        private final PolarisContextManager context;

        private Builder(PolarisContextManager context) {
            this.context = Objects.requireNonNull(context, "context");
        }

        public PolarisAgentCardResolver build() {
            return new PolarisAgentCardResolver(context);
        }
    }

    /** Sticky instance identity plus the last decoded card from that instance. */
    private record CacheEntry(AgentCard card, String host, int port) {
    }

    private record DecodedInstance(Instance instance, AgentCard card) {
    }
}
