package com.tencent.ai.polaris.a2a.registry;

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
 * <p>Each A2A agent maps to a Polaris service named after {@link AgentCard#name()};
 * each exported transport maps to one Polaris instance. The full AgentCard JSON is
 * stored in instance metadata under {@link PolarisA2aConstants#META_AGENT_CARD} so the
 * discovery side can rebuild it without per-field mapping.
 *
 * <p>Heartbeat is handled by {@code ProviderAPI.registerInstance} (autoHeartbeat=true,
 * ttl defaults to {@link PolarisA2aConstants#DEFAULT_TTL_SECONDS}). The AgentScope SPI
 * has no deregister hook, so this class implements {@link AutoCloseable} and records
 * registered instances for shutdown cleanup.
 */
public class PolarisAgentRegistry implements AgentRegistry, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PolarisAgentRegistry.class);

    private final ProviderAPI providerAPI;
    private final String namespace;
    private final int ttl;
    private final CopyOnWriteArrayList<RegisteredInstance> registered = new CopyOnWriteArrayList<>();

    public PolarisAgentRegistry(PolarisContextManager context) {
        this(context.providerAPI(), context.getNamespace(), PolarisA2aConstants.DEFAULT_TTL_SECONDS);
    }

    PolarisAgentRegistry(ProviderAPI providerAPI, String namespace, int ttl) {
        this.providerAPI = Objects.requireNonNull(providerAPI, "providerAPI");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.ttl = ttl;
    }

    @Override
    public String registryName() {
        return PolarisA2aConstants.REGISTRY_NAME;
    }

    @Override
    public void register(AgentCard agentCard, List<TransportProperties> transports) {
        Objects.requireNonNull(agentCard, "agentCard");
        if (transports == null || transports.isEmpty()) {
            throw new IllegalArgumentException("transports must not be empty for agent: " + agentCard.name());
        }
        String service = agentCard.name();
        String cardJson = AgentCardCodec.toJson(agentCard);

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
            req.setMetadata(buildMetadata(cardJson, tp));

            try {
                InstanceRegisterResponse resp = providerAPI.registerInstance(req);
                registered.add(new RegisteredInstance(service, tp.host(), port, resp.getInstanceId()));
                log.info("Registered agent '{}' transport '{}' instance at {}:{} (instanceId={})",
                        service, tp.transportType(), tp.host(), port, resp.getInstanceId());
            } catch (PolarisException e) {
                throw new IllegalStateException("Failed to register agent '" + service
                        + "' transport '" + tp.transportType() + "' to polaris: " + e.getMessage(), e);
            }
        }
    }

    private static Map<String, String> buildMetadata(String cardJson, TransportProperties tp) {
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

    private static String resolveProtocol(TransportProperties tp) {
        return tp.supportTls() ? "https" : "http";
    }

    @Override
    public void close() {
        List<RegisteredInstance> snapshot = new ArrayList<>(registered);
        registered.clear();
        for (RegisteredInstance ri : snapshot) {
            InstanceDeregisterRequest req = new InstanceDeregisterRequest();
            req.setNamespace(namespace);
            req.setService(ri.service());
            req.setHost(ri.host());
            req.setPort(ri.port());
            if (ri.instanceId() != null) {
                req.setInstanceID(ri.instanceId());
            }
            try {
                providerAPI.deRegister(req);
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

    public static final class Builder {

        private final PolarisContextManager context;
        private int ttl = PolarisA2aConstants.DEFAULT_TTL_SECONDS;

        private Builder(PolarisContextManager context) {
            this.context = Objects.requireNonNull(context, "context");
        }

        public Builder ttl(int ttl) {
            this.ttl = ttl;
            return this;
        }

        public PolarisAgentRegistry build() {
            return new PolarisAgentRegistry(context.providerAPI(), context.getNamespace(), ttl);
        }
    }

    public record RegisteredInstance(String service, String host, int port, String instanceId) {
    }
}
