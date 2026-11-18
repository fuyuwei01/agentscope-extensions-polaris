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

package com.tencent.ai.polaris.mcp.client;

import com.tencent.ai.polaris.mcp.discovery.PolarisMcpServerManager;
import com.tencent.ai.polaris.mcp.exception.PolarisMcpException;
import com.tencent.ai.polaris.mcp.model.PolarisMcpEndpoint;
import com.tencent.ai.polaris.mcp.model.PolarisMcpServiceKey;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Logical client retained by Toolkit; current holds the selected instance and native AgentScope
 * client.
 *
 * <p>Toolkit registration initializes an MCP session, and subsequent tool calls reuse the client.
 * If the instance becomes unavailable, the native client can be replaced while registered tools
 * still reference this wrapper. An MCP session does not necessarily correspond to one user
 * conversation, nor does reusing a client guarantee the same TCP connection for every HTTP request.
 */
public final class PolarisMcpClientWrapper extends McpClientWrapper {
    private static final Logger log = LoggerFactory.getLogger(PolarisMcpClientWrapper.class);
    private final PolarisMcpServerManager manager;
    private final PolarisMcpServiceKey key;
    private final Function<PolarisMcpEndpoint, McpClientWrapper> factory;
    private final Duration initTimeout;
    private final Object lock = new Object();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    // Owns a reference to a shared discovery subscription and its local instance snapshot.
    private PolarisMcpServerManager.Lease lease;
    // Reused by tool calls until the selected instance is invalidated or the client is recycled.
    private Connection current;
    private Mono<Void> initializing;
    private Map<String, Signature> toolContract;
    private boolean closed;
    private volatile long lastAccessNanos = System.nanoTime();

    PolarisMcpClientWrapper(
            String name,
            PolarisMcpServerManager manager,
            PolarisMcpServiceKey key,
            Function<PolarisMcpEndpoint, McpClientWrapper> factory,
            Duration initTimeout) {
        super(name);
        this.manager = manager;
        this.key = key;
        this.factory = factory;
        this.initTimeout = initTimeout;
    }

    public long getLastAccessNanos() {
        return lastAccessNanos;
    }

    public PolarisMcpServiceKey getServiceKey() {
        return key;
    }

    public PolarisMcpEndpoint getSelectedEndpoint() {
        synchronized (lock) {
            return valid(current) ? current.endpoint : null;
        }
    }

    @Override
    public Mono<Void> initialize() {
        // Check the connection on subscription, including block() and subscribed tool call chains.
        return Mono.defer(this::ensureConnection);
    }

    private Mono<Void> ensureConnection() {
        synchronized (lock) {
            if (closed || manager.isClosed())
                return Mono.error(
                        failure(
                                PolarisMcpException.Reason.CLOSED,
                                "MCP client or manager is closed"));
            // Common path: reuse the current client without selecting an instance or initializing MCP.
            // This checks local state and snapshots; it neither queries Polaris nor probes the network.
            if (valid(current)) return Mono.empty();
            if (initializing == null) {
                // Connect only when needed; concurrent callers share one initialization result.
                initializing =
                        Mono.fromRunnable(this::connect)
                                .subscribeOn(manager.scheduler())
                                .doOnTerminate(
                                        () -> {
                                            synchronized (lock) {
                                                initializing = null;
                                            }
                                        })
                                .cache()
                                .then();
            }
            return initializing;
        }
    }

    private void connect() {
        PolarisMcpServerManager.Lease acquired;
        synchronized (lock) {
            // A scheduled reconnect may start after close() has already released the lease.
            if (closed) throw failure(PolarisMcpException.Reason.CLOSED, "MCP client is closed");
            acquired = lease;
        }
        if (acquired == null) {
            // Acquire shared discovery on the first connection or after idle recycling.
            acquired = manager.acquire(key, this::instancesChanged);
            synchronized (lock) {
                if (closed) {
                    acquired.close();
                    throw failure(PolarisMcpException.Reason.CLOSED, "MCP client is closed");
                }
                lease = acquired;
            }
        }
        RuntimeException last = null;
        // Retry only connection establishment. Never replay a tool invocation.
        for (int generationAttempt = 0; generationAttempt < 3; generationAttempt++) {
            long generation = acquired.generation();
            List<PolarisMcpEndpoint> candidates = new ArrayList<>(acquired.endpoints());
            if (candidates.isEmpty()) {
                if (acquired.endpointFailure() != null) throw acquired.endpointFailure();
                throw failure(
                        PolarisMcpException.Reason.NO_INSTANCE,
                        "No healthy MCP instance for " + key.service());
            }
            while (!candidates.isEmpty()) {
                // Select an instance only when creating or rebuilding a connection.
                PolarisMcpEndpoint endpoint = weightedRemove(candidates);
                Connection candidate = null;
                try {
                    McpClientWrapper delegate = factory.apply(endpoint);
                    if (delegate == null)
                        throw new IllegalStateException("MCP factory returned no client");
                    candidate = new Connection(endpoint, delegate);
                    connections.add(candidate);
                    synchronized (lock) {
                        if (closed)
                            throw failure(
                                    PolarisMcpException.Reason.CLOSED, "MCP client is closed");
                    }
                    // Initialize MCP and discover tools before sending any tools/call request.
                    delegate.initialize().block(initTimeout);
                    List<McpSchema.Tool> tools = delegate.listTools().block(initTimeout);
                    if (tools == null)
                        throw new IllegalStateException("MCP tools/list returned no result");
                    Map<String, Signature> contract = signature(tools);
                    synchronized (lock) {
                        if (closed)
                            throw failure(
                                    PolarisMcpException.Reason.CLOSED, "MCP client is closed");
                        if (generation != acquired.generation()) throw new StaleConnection();
                        // Registered tools retain their schemas; replacement replicas must match.
                        if (toolContract != null && !toolContract.equals(contract))
                            throw failure(
                                    PolarisMcpException.Reason.INCOMPATIBLE_TOOLS,
                                    "MCP replica tool definitions changed");
                        Connection previous = current;
                        // Retain the selected instance and initialized client for subsequent calls.
                        current = candidate;
                        toolContract = contract;
                        cachedTools.clear();
                        for (McpSchema.Tool tool : tools) cachedTools.put(tool.name(), tool);
                        initialized = true;
                        candidate = null;
                        if (previous != null) previous.retire();
                    }
                    return;
                } catch (RuntimeException e) {
                    if (e instanceof StaleConnection) break;
                    last = e;
                    if (e instanceof PolarisMcpException m
                            && m.getReason() == PolarisMcpException.Reason.CLOSED) throw e;
                } finally {
                    if (candidate != null) candidate.closeNow();
                }
            }
            if (generation == acquired.generation()) break;
        }
        if (last instanceof PolarisMcpException e) throw e;
        throw new PolarisMcpException(
                PolarisMcpException.Reason.INITIALIZATION,
                "Failed to initialize MCP service " + key.service(),
                last);
    }

    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        return initialize()
                .then(
                        Mono.fromSupplier(
                                () -> {
                                    synchronized (lock) {
                                        lastAccessNanos = System.nanoTime();
                                        return List.copyOf(cachedTools.values());
                                    }
                                }));
    }

    /**
     * Ensures a connection exists before sending tools/call through current.delegate.
     * initialize() normally performs only a local check; an invalidated or recycled connection
     * may need rebuilding before the call. A failed tool invocation is reported and schedules
     * reconnection, but is never replayed automatically to avoid duplicating side effects.
     */
    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return Mono.defer(
                () -> {
                    lastAccessNanos = System.nanoTime();
                    return initialize()
                            .then(
                                    Mono.defer(
                                            () -> {
                                                Connection connection;
                                                synchronized (lock) {
                                                    if (!valid(current))
                                                        return Mono.error(
                                                                failure(
                                                                        PolarisMcpException.Reason
                                                                                .NO_INSTANCE,
                                                                        "MCP instance was"
                                                                                + " removed"));
                                                    connection = current;
                                                    connection.calls++;
                                                }
                                                long started = System.nanoTime();
                                                return Mono.defer(
                                                                () ->
                                                                        connection.delegate
                                                                                .callTool(
                                                                                        toolName,
                                                                                        arguments))
                                                        .doOnNext(
                                                                result ->
                                                                        manager.reportCall(
                                                                                key,
                                                                                connection.endpoint,
                                                                                toolName,
                                                                                System.nanoTime()
                                                                                        - started,
                                                                                !Boolean.TRUE
                                                                                        .equals(
                                                                                                result
                                                                                                        .isError())))
                                                        .doOnError(
                                                                error -> {
                                                                    manager.reportCall(
                                                                            key,
                                                                            connection.endpoint,
                                                                            toolName,
                                                                            System.nanoTime()
                                                                                    - started,
                                                                            false);
                                                                    invalidate(connection);
                                                                })
                                                        .doFinally(signal -> connection.release());
                                            }));
                });
    }

    private boolean valid(Connection connection) {
        return !closed
                && connection != null
                && !connection.retired
                && lease != null
                && lease.endpoints().stream().anyMatch(connection.endpoint::sameConnection);
    }

    private void invalidate(Connection connection) {
        synchronized (lock) {
            if (current == connection) {
                current = null;
                initialized = false;
            }
            connection.retire();
        }
        queueRefresh();
    }

    private void instancesChanged() {
        if (manager.isClosed()) {
            close();
            return;
        }
        synchronized (lock) {
            if (closed || lease == null || (current == null && toolContract == null)) return;
            // Changes to other replicas do not interrupt the session while this instance is valid.
            if (valid(current)) return;
            if (current != null) {
                current.retire();
                current = null;
                initialized = false;
            }
        }
        // Reconnect in the background without waiting for a tool call; server session state is not migrated.
        queueRefresh();
    }

    private void queueRefresh() {
        if (!refreshQueued.compareAndSet(false, true)) return;
        try {
            manager.scheduler()
                    .schedule(
                            () ->
                                    initialize()
                                            .subscribe(
                                                    ignored -> {},
                                                    error -> refreshQueued.set(false),
                                                    () -> refreshQueued.set(false)));
        } catch (RuntimeException e) {
            refreshQueued.set(false);
        }
    }

    /**
     * Releases an idle native client and discovery subscription while retaining the logical client
     * referenced by Toolkit. The next tool call reacquires discovery and initializes a selected
     * instance, so a user conversation is not permanently pinned to one instance.
     */
    public void recycleIdle(Duration timeout) {
        PolarisMcpServerManager.Lease release;
        synchronized (lock) {
            if (closed
                    || lease == null
                    || connections.stream().anyMatch(connection -> connection.calls != 0)
                    || initializing != null
                    || refreshQueued.get()
                    || System.nanoTime() - lastAccessNanos < timeout.toNanos()) return;
            if (current != null) current.retire();
            current = null;
            initialized = false;
            release = lease;
            lease = null;
        }
        if (release != null) release.close();
    }

    @Override
    public void close() {
        PolarisMcpServerManager.Lease release;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            initialized = false;
            current = null;
            cachedTools.clear();
            release = lease;
            lease = null;
        }
        if (release != null) release.close();
        for (Connection connection : connections) connection.closeNow();
    }

    private static PolarisMcpEndpoint weightedRemove(List<PolarisMcpEndpoint> endpoints) {
        long total = endpoints.stream().mapToLong(PolarisMcpEndpoint::weight).sum();
        long value = ThreadLocalRandom.current().nextLong(total);
        for (int i = 0; i < endpoints.size(); i++) {
            value -= endpoints.get(i).weight();
            if (value < 0) return endpoints.remove(i);
        }
        return endpoints.remove(endpoints.size() - 1);
    }

    private static Map<String, Signature> signature(List<McpSchema.Tool> tools) {
        Map<String, Signature> result = new HashMap<>();
        for (McpSchema.Tool tool : tools) {
            Signature previous =
                    result.put(
                            tool.name(),
                            new Signature(
                                    tool.description(),
                                    freezeSchema(tool.inputSchema()),
                                    freeze(tool.outputSchema())));
            if (previous != null)
                throw failure(
                        PolarisMcpException.Reason.INCOMPATIBLE_TOOLS, "Duplicate MCP tool name");
        }
        return Map.copyOf(result);
    }

    private static McpSchema.JsonSchema freezeSchema(McpSchema.JsonSchema schema) {
        if (schema == null) return null;
        return new McpSchema.JsonSchema(
                schema.type(),
                freezeMap(schema.properties()),
                schema.required() == null ? null : List.copyOf(schema.required()),
                schema.additionalProperties(),
                freezeMap(schema.defs()),
                freezeMap(schema.definitions()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> freezeMap(Map<String, Object> value) {
        return (Map<String, Object>) freeze(value);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new HashMap<>();
            map.forEach((key, item) -> copy.put(key, freeze(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list)
            return Collections.unmodifiableList(
                    list.stream().map(PolarisMcpClientWrapper::freeze).toList());
        return value;
    }

    private record Signature(String description, McpSchema.JsonSchema input, Object output) {}

    private static final class StaleConnection extends RuntimeException {}

    private static PolarisMcpException failure(PolarisMcpException.Reason reason, String message) {
        return new PolarisMcpException(reason, message);
    }

    private final class Connection {
        final PolarisMcpEndpoint endpoint;
        final McpClientWrapper delegate;
        final AtomicBoolean disposed = new AtomicBoolean();
        int calls;
        boolean retired;

        Connection(PolarisMcpEndpoint endpoint, McpClientWrapper delegate) {
            this.endpoint = endpoint;
            this.delegate = delegate;
        }

        void retire() {
            if (retired) return;
            retired = true;
            if (calls == 0) asyncClose();
            else
                try {
                    manager.timer().schedule(this::asyncClose, 10, TimeUnit.SECONDS);
                } catch (RuntimeException e) {
                    asyncClose();
                }
        }

        void release() {
            synchronized (lock) {
                calls--;
                lastAccessNanos = System.nanoTime();
                if (retired && calls == 0) asyncClose();
            }
        }

        void asyncClose() {
            try {
                manager.scheduler().schedule(this::closeNow);
            } catch (RuntimeException e) {
                closeNow();
            }
        }

        void closeNow() {
            if (disposed.compareAndSet(false, true)) {
                try {
                    delegate.close();
                } catch (RuntimeException e) {
                    log.debug("Failed to close retired MCP connection", e);
                } finally {
                    connections.remove(this);
                }
            }
        }
    }
}
