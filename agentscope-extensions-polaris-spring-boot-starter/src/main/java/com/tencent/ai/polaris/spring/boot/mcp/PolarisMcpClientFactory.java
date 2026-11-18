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

package com.tencent.ai.polaris.spring.boot.mcp;

import com.tencent.ai.polaris.mcp.client.PolarisMcpClientBuilder;
import com.tencent.ai.polaris.mcp.client.PolarisMcpClientWrapper;
import com.tencent.ai.polaris.mcp.discovery.PolarisMcpServerManager;
import com.tencent.ai.polaris.spring.boot.config.mcp.AgentScopePolarisMcpProperties;
import com.tencent.ai.polaris.spring.boot.config.mcp.AgentScopePolarisMcpProperties.Client;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.modelcontextprotocol.spec.McpSchema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Creates separate wrappers per Toolkit and owns their explicit, idle and shutdown cleanup. */
public class PolarisMcpClientFactory implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PolarisMcpClientFactory.class);
    private final PolarisMcpServerManager manager;
    private final AgentScopePolarisMcpProperties properties;
    private final CopyOnWriteArrayList<Binding> bindings = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reaping = new AtomicBoolean();
    private final ScheduledFuture<?> reaper;

    public PolarisMcpClientFactory(
            PolarisMcpServerManager manager, AgentScopePolarisMcpProperties properties) {
        this.manager = manager;
        this.properties = properties;
        Set<String> names = new HashSet<>();
        for (Client client : properties.getDiscovery().getClients()) {
            String name = name(client);
            if (client.getServiceName() == null
                    || client.getServiceName().isBlank()
                    || !names.add(name))
                throw new IllegalArgumentException(
                        "MCP service-name must be set and client names must be unique");
            builder(client); // Validate all connection policies before creating any connections.
        }
        Duration idle = properties.getDiscovery().getIdleTimeout();
        if (idle == null || idle.isZero() || idle.isNegative())
            throw new IllegalArgumentException("MCP idle-timeout must be positive");
        long interval = Math.max(100, Math.min(30_000, idle.toMillis()));
        reaper =
                manager.timer()
                        .scheduleWithFixedDelay(
                                this::reap, interval, interval, TimeUnit.MILLISECONDS);
    }

    public boolean hasClients() {
        return !properties.getDiscovery().getClients().isEmpty();
    }

    protected PolarisMcpClientWrapper createClient(Client client) {
        return builder(client).build();
    }

    private PolarisMcpClientBuilder builder(Client client) {
        PolarisMcpClientBuilder builder =
                PolarisMcpClientBuilder.create(client.getServiceName(), manager)
                        .clientName(name(client))
                        .asyncClient(client.isAsyncClient())
                        .requestTimeout(client.getRequestTimeout())
                        .initializationTimeout(client.getInitializationTimeout())
                        .defaultScheme(client.getDefaultScheme())
                        .version(client.getVersion());
        if (client.getNamespace() != null && !client.getNamespace().isBlank())
            builder.namespace(client.getNamespace());
        client.getHeaders().forEach(builder::header);
        return builder;
    }

    private static String name(Client client) {
        return client.getName() == null || client.getName().isBlank()
                ? client.getServiceName()
                : client.getName();
    }

    /**
     * Blocks only during startup/session construction. Repeated binding of the same Toolkit is a
     * no-op.
     */
    public void bind(Toolkit toolkit) {
        synchronized (toolkit) {
            if (closed.get()) throw new IllegalStateException("MCP client factory is closed");
            if (!hasClients()) return;
            if (bindings.stream().anyMatch(binding -> binding.contains(toolkit))) return;
            List<PolarisMcpClientWrapper> clients = new ArrayList<>();
            List<String> registered = new ArrayList<>();
            try {
                Set<String> names = new HashSet<>(toolkit.getToolNames());
                int originalToolCount = names.size();
                for (Client config : properties.getDiscovery().getClients()) {
                    PolarisMcpClientWrapper client = createClient(config);
                    clients.add(client);
                    // Select and initialize during binding to obtain tool schemas before model calls.
                    // createClient()/build() alone creates no MCP session.
                    client.initialize().block();
                    List<McpSchema.Tool> tools = client.listTools().block();
                    for (McpSchema.Tool tool : tools)
                        if (!names.add(tool.name()))
                            throw new IllegalStateException(
                                    "MCP tool name conflicts with another tool: " + tool.name());
                }
                if (names.size() == originalToolCount) {
                    // A server with no tools has nothing to bind. Do not retain invisible sessions.
                    clients.forEach(PolarisMcpClientWrapper::close);
                    return;
                }
                for (PolarisMcpClientWrapper client : clients) {
                    registered.add(client.getName());
                    // Native registration also initializes and lists tools, reusing the current client.
                    // Registered McpTool objects retain this wrapper and invoke its callTool().
                    toolkit.registerMcpClient(client).block();
                }
                Binding binding = new Binding(toolkit, List.copyOf(clients));
                bindings.add(binding);
                if (closed.get()) {
                    bindings.remove(binding);
                    binding.close();
                    throw new IllegalStateException("MCP client factory is closed");
                }
            } catch (RuntimeException error) {
                for (String name : registered)
                    try {
                        toolkit.removeMcpClient(name).block();
                    } catch (RuntimeException cleanup) {
                        error.addSuppressed(cleanup);
                    }
                clients.forEach(PolarisMcpClientWrapper::close);
                throw error;
            }
        }
    }

    /** AgentScope copies the Toolkit but retains its tool objects; retain that copy as an owner. */
    public void retain(Toolkit toolkit) {
        for (Binding binding : bindings) {
            synchronized (binding) {
                if (!closed.get()
                        && !binding.retired
                        && binding.tools.entrySet().stream()
                                .anyMatch(entry -> toolkit.getTool(entry.getKey()) == entry.getValue())
                        && !binding.contains(toolkit))
                    binding.toolkits.add(new WeakReference<>(toolkit));
            }
        }
    }

    /**
     * Removes only this owner's MCP tools. Shared connections close after the last owner releases
     * them; the original Toolkit remains an owner until released or garbage collected.
     */
    public void release(Toolkit toolkit) {
        List<Binding> released = new ArrayList<>();
        synchronized (toolkit) {
            for (Binding binding : bindings) {
                boolean lastOwner;
                // Toolkit copies share tools and wrappers, so their ownership changes share a lock.
                synchronized (binding) {
                    if (binding.retired || !binding.contains(toolkit)) continue;
                    for (Map.Entry<String, AgentTool> tool : binding.tools.entrySet())
                        toolkit.removeToolIfSame(tool.getKey(), tool.getValue());
                    binding.toolkits.removeIf(
                            reference -> reference.get() == null || reference.get() == toolkit);
                    lastOwner = binding.toolkits.isEmpty();
                    if (lastOwner) {
                        binding.retired = true;
                        bindings.remove(binding);
                        released.add(binding);
                    }
                }
            }
        }
        // Close outside the owner's lock to avoid locking two different Toolkits at once.
        released.forEach(this::closeReleasedBinding);
    }

    private void closeReleasedBinding(Binding binding) {
        try {
            Toolkit original = binding.original.get();
            if (original != null) {
                // Native client registrations are held only by the original Toolkit, not its copies.
                // Serialize with bind() and leave a newer registration on the same Toolkit intact.
                synchronized (original) {
                    if (bindings.stream().noneMatch(other -> other.original.get() == original))
                        for (PolarisMcpClientWrapper client : binding.clients)
                            original.removeMcpClient(client.getName()).block();
                }
            }
        } finally {
            binding.close();
        }
    }

    private void reap() {
        // Delegate closing/unwatching may block. Keep the timer available to all sessions.
        if (closed.get() || !reaping.compareAndSet(false, true)) return;
        try {
            manager.scheduler()
                    .schedule(
                            () -> {
                                try {
                                    reapOnIo();
                                } finally {
                                    reaping.set(false);
                                }
                            });
        } catch (RuntimeException e) {
            reaping.set(false);
            if (!closed.get()) log.debug("MCP cleanup scheduler unavailable", e);
        }
    }

    private void reapOnIo() {
        if (closed.get()) return;
        for (Binding binding : bindings) {
            boolean lastOwner;
            synchronized (binding) {
                if (binding.retired) continue;
                binding.toolkits.removeIf(reference -> reference.get() == null);
                lastOwner = binding.toolkits.isEmpty();
                if (lastOwner) {
                    binding.retired = true;
                    bindings.remove(binding);
                } else
                    for (PolarisMcpClientWrapper client : binding.clients)
                        client.recycleIdle(properties.getDiscovery().getIdleTimeout());
            }
            if (lastOwner) closeReleasedBinding(binding);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        reaper.cancel(false);
        for (Binding binding : bindings) binding.close();
        bindings.clear();
    }

    private static final class Binding {
        final CopyOnWriteArrayList<WeakReference<Toolkit>> toolkits = new CopyOnWriteArrayList<>();
        final WeakReference<Toolkit> original;
        final Map<String, AgentTool> tools = new HashMap<>();
        final List<PolarisMcpClientWrapper> clients;
        boolean retired;

        Binding(Toolkit toolkit, List<PolarisMcpClientWrapper> clients) {
            original = new WeakReference<>(toolkit);
            toolkits.add(original);
            this.clients = clients;
            for (PolarisMcpClientWrapper client : clients)
                for (String name :
                        client.listTools().block().stream().map(McpSchema.Tool::name).toList())
                    tools.put(name, toolkit.getTool(name));
        }

        synchronized boolean contains(Toolkit toolkit) {
            return toolkits.stream().anyMatch(reference -> reference.get() == toolkit);
        }

        synchronized void close() {
            retired = true;
            clients.forEach(PolarisMcpClientWrapper::close);
        }
    }
}
