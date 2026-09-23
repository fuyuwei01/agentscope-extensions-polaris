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

package com.tencent.ai.polaris.a2a.registry;

import static com.tencent.ai.polaris.a2a.constant.PolarisA2aConstants.MAX_METADATA_VALUE_CHARS;
import static com.tencent.ai.polaris.a2a.constant.PolarisA2aConstants.META_AGENT_NAME;

import com.tencent.ai.polaris.a2a.constant.PolarisA2aConstants;
import com.tencent.ai.polaris.a2a.util.AgentCardCodec;
import com.tencent.ai.polaris.core.PolarisContextManager;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.registry.AgentRegistry;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import com.tencent.polaris.api.core.ProviderAPI;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.rpc.InstanceDeregisterRequest;
import com.tencent.polaris.api.rpc.InstanceRegisterRequest;
import com.tencent.polaris.api.rpc.InstanceRegisterResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AgentScope A2A registry backed by polaris-java.
 *
 * <p>Service mapping: {@link AgentCard#name()} is the Polaris service name; each
 * exported {@link TransportProperties} becomes one Polaris instance (host / port /
 * protocol / version). Discovery ({@link com.tencent.ai.polaris.a2a.discovery.PolarisAgentCardResolver})
 * rebuilds the card from instance metadata.
 *
 * <p>Metadata written on each instance:
 * <ul>
 *   <li>{@link PolarisA2aConstants#META_AGENT_CARD} — full AgentCard JSON</li>
 *   <li>{@link PolarisA2aConstants#META_AGENT_NAME} — agent name</li>
 *   <li>{@link PolarisA2aConstants#META_TRANSPORT} / {@link PolarisA2aConstants#META_PATH} —
 *       locator fields for this endpoint</li>
 * </ul>
 * A single metadata value must be at most {@link PolarisA2aConstants#MAX_METADATA_VALUE_CHARS}
 * characters (64KiB − 1). Oversized values throw {@link IllegalArgumentException}
 * before any Polaris RPC.
 *
 * <p>Heartbeat uses {@code ProviderAPI.registerInstance} with {@code autoHeartbeat=true}
 * and TTL {@link PolarisA2aConstants#DEFAULT_TTL_SECONDS} unless overridden. Register
 * and deregister set the Polaris <em>service_token</em> via {@code setToken} when
 * configured (distinct from the connector {@code X-Polaris-Token}; polaris-java
 * {@code RegisterFlow} reuses the register token for heartbeats).
 *
 * <p>The AgentScope SPI has no deregister hook. This class implements
 * {@link AutoCloseable}: {@link #register} tracks instances, a failed multi-transport
 * register rolls back already-created instances, and {@link #close()} deregisters
 * whatever is still tracked. A failed deregister leaves the instance on the list
 * so a later {@link #close()} can retry.
 */
public class PolarisAgentRegistry implements AgentRegistry, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PolarisAgentRegistry.class);

    private final ProviderAPI providerAPI;
    private final String namespace;
    private final int ttl;
    private final String serviceToken;
    private final CopyOnWriteArrayList<RegisteredInstance> registered = new CopyOnWriteArrayList<>();

    /**
     * Builds a registry sharing the given Polaris context, with the default heartbeat TTL
     * and optional {@code agentscope.polaris.token} as the service token.
     *
     * @param context shared SDK context (namespace + {@link ProviderAPI} + token)
     */
    public PolarisAgentRegistry(PolarisContextManager context) {
        this(context.providerAPI(), context.getNamespace(), PolarisA2aConstants.DEFAULT_TTL_SECONDS,
                tokenOrNull(context));
    }

    PolarisAgentRegistry(ProviderAPI providerAPI, String namespace, int ttl) {
        this(providerAPI, namespace, ttl, null);
    }

    /**
     * Package-visible constructor for tests.
     *
     * @param ttl heartbeat TTL in seconds passed to {@code InstanceRegisterRequest}
     * @param serviceToken Polaris service token, or {@code null} to omit {@code setToken}
     */
    PolarisAgentRegistry(ProviderAPI providerAPI, String namespace, int ttl, String serviceToken) {
        this.providerAPI = Objects.requireNonNull(providerAPI, "providerAPI");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.ttl = ttl;
        this.serviceToken = blankToNull(serviceToken);
    }

    @Override
    public String registryName() {
        return PolarisA2aConstants.REGISTRY_NAME;
    }

    /**
     * Register one Polaris instance per transport. Partial success is rolled back.
     *
     * @param agentCard identity and JSON payload stored under {@code a2a.agent.card}
     * @param transports non-empty list; each entry needs a non-null port
     * @throws IllegalArgumentException if transports are empty, a port is missing, or
     *         any metadata value exceeds {@link PolarisA2aConstants#MAX_METADATA_VALUE_CHARS}
     * @throws IllegalStateException if Polaris {@code registerInstance} fails
     */
    @Override
    public void register(AgentCard agentCard, List<TransportProperties> transports) {
        Objects.requireNonNull(agentCard, "agentCard");
        if (transports == null || transports.isEmpty()) {
            throw new IllegalArgumentException("transports must not be empty for agent: " + agentCard.name());
        }
        String service = agentCard.name();
        String cardJson = AgentCardCodec.toJson(agentCard);
        List<RegisteredInstance> registeredThisCall = new ArrayList<>();

        try {
            for (TransportProperties tp : transports) {
                InstanceRegisterRequest req = new InstanceRegisterRequest();
                req.setNamespace(namespace);
                req.setService(service);
                req.setHost(tp.host());
                Integer port = tp.port();
                if (port == null) {
                    throw new IllegalArgumentException("transport port must not be null for agent: " + service);
                }
                req.setPort(port);
                req.setVersion(agentCard.version());
                req.setProtocol(resolveProtocol(tp));
                req.setTtl(ttl);
                req.setAutoHeartbeat(true);
                if (serviceToken != null) {
                    req.setToken(serviceToken);
                }
                Map<String, String> meta = buildMetadata(cardJson, tp);
                meta.put(META_AGENT_NAME, agentCard.name());
                assertMetadataWithinLimit(service, meta);
                req.setMetadata(meta);

                InstanceRegisterResponse resp = providerAPI.registerInstance(req);
                RegisteredInstance instance =
                        new RegisteredInstance(service, tp.host(), port, resp.getInstanceId());
                registered.add(instance);
                registeredThisCall.add(instance);
                log.info("Registered agent '{}' transport '{}' instance at {}:{} (instanceId={})",
                        service, tp.transportType(), tp.host(), port, resp.getInstanceId());
            }
        } catch (PolarisException e) {
            rollback(registeredThisCall);
            throw new IllegalStateException(
                    "Failed to register agent '" + service + "' to polaris: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            rollback(registeredThisCall);
            throw e;
        }
    }

    /** Best-effort deregister of instances created in a failed {@link #register} call. */
    private void rollback(List<RegisteredInstance> instances) {
        deregister(instances);
    }

    /**
     * Locator + card metadata for one transport. {@link PolarisA2aConstants#META_AGENT_NAME}
     * is added by the caller after this map is built.
     */
    static Map<String, String> buildMetadata(String cardJson, TransportProperties tp) {
        Map<String, String> meta = new HashMap<>();
        meta.put(PolarisA2aConstants.META_AGENT_CARD, cardJson);
        if (tp.transportType() != null) {
            meta.put(PolarisA2aConstants.META_TRANSPORT, tp.transportType());
        }
        if (tp.path() != null) {
            meta.put(PolarisA2aConstants.META_PATH, tp.path());
        }
        return meta;
    }

    /**
     * Reject any metadata value longer than {@link PolarisA2aConstants#MAX_METADATA_VALUE_CHARS}.
     */
    static void assertMetadataWithinLimit(String service, Map<String, String> metadata) {
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String value = entry.getValue();
            if (value == null) {
                continue;
            }
            int length = value.length();
            if (length > MAX_METADATA_VALUE_CHARS) {
                throw new IllegalArgumentException(
                        "Agent '" + service + "' metadata '" + entry.getKey()
                                + "' is " + length + " characters, exceeding the Polaris limit of "
                                + MAX_METADATA_VALUE_CHARS + " (64KiB). Registration aborted.");
            }
        }
    }

    /** {@code https} when the transport advertises TLS, otherwise {@code http}. */
    private static String resolveProtocol(TransportProperties tp) {
        return tp.supportTls() ? "https" : "http";
    }

    /**
     * Deregister every still-tracked instance. Failures are logged and left on the
     * list for a later retry; this does not close the shared {@link ProviderAPI}.
     */
    @Override
    public void close() {
        List<RegisteredInstance> snapshot = new ArrayList<>(registered);
        deregister(snapshot);
    }

    /**
     * Deregister each instance; remove from {@link #registered} only after a successful
     * Polaris call so a failed deregister can be retried on {@link #close()}.
     */
    private void deregister(List<RegisteredInstance> instances) {
        for (RegisteredInstance ri : instances) {
            InstanceDeregisterRequest req = new InstanceDeregisterRequest();
            req.setNamespace(namespace);
            req.setService(ri.service());
            req.setHost(ri.host());
            req.setPort(ri.port());
            if (ri.instanceId() != null) {
                req.setInstanceID(ri.instanceId());
            }
            if (serviceToken != null) {
                req.setToken(serviceToken);
            }
            try {
                providerAPI.deRegister(req);
                registered.remove(ri);
                log.info("Deregistered agent '{}' instance {}:{} (instanceId={})",
                        ri.service(), ri.host(), ri.port(), ri.instanceId());
            } catch (Exception e) {
                log.warn("Failed to deregister agent '{}' instance {}:{} : {}",
                        ri.service(), ri.host(), ri.port(), e.getMessage(), e);
            }
        }
    }

    /** Snapshot of registered instances, mainly for tests/inspection. */
    public List<RegisteredInstance> getRegistered() {
        return new ArrayList<>(registered);
    }

    public static Builder builder(PolarisContextManager context) {
        return new Builder(context);
    }

    private static String tokenOrNull(PolarisContextManager context) {
        if (context.getProperties() == null) {
            return null;
        }
        return blankToNull(context.getProperties().getToken());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    /**
     * Fluent factory. {@link #ttl(int)} defaults to
     * {@link PolarisA2aConstants#DEFAULT_TTL_SECONDS}; the service token is taken
     * from {@link PolarisContextManager#getProperties()}.
     */
    public static final class Builder {

        private final PolarisContextManager context;
        private int ttl = PolarisA2aConstants.DEFAULT_TTL_SECONDS;

        private Builder(PolarisContextManager context) {
            this.context = Objects.requireNonNull(context, "context");
        }

        /** Heartbeat TTL in seconds for {@code InstanceRegisterRequest.setTtl}. */
        public Builder ttl(int ttl) {
            this.ttl = ttl;
            return this;
        }

        public PolarisAgentRegistry build() {
            return new PolarisAgentRegistry(
                    context.providerAPI(), context.getNamespace(), ttl, tokenOrNull(context));
        }
    }

    /** Host/port/service identity of an instance this registry has registered. */
    public record RegisteredInstance(String service, String host, int port, String instanceId) {
    }
}
