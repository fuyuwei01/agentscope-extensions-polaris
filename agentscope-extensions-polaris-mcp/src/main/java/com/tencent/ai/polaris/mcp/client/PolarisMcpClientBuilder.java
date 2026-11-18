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
import com.tencent.ai.polaris.mcp.model.PolarisMcpEndpoint;
import com.tencent.ai.polaris.mcp.model.PolarisMcpServiceKey;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Builds a lazy AgentScope MCP wrapper backed by Polaris discovery. */
public final class PolarisMcpClientBuilder {
    private final PolarisMcpServerManager manager;
    private final String service;
    private String namespace;
    private String version;
    private String name;
    private boolean async = true;
    private Duration requestTimeout = Duration.ofSeconds(30);
    private Duration initializationTimeout = Duration.ofSeconds(10);
    private String scheme = "http";
    private final Map<String, String> headers = new LinkedHashMap<>();
    private Supplier<Map<String, String>> headerProvider = Map::of;
    private Consumer<HttpClient.Builder> httpCustomizer = builder -> {};
    private Function<PolarisMcpEndpoint, McpClientWrapper> clientFactory;

    private PolarisMcpClientBuilder(String service, PolarisMcpServerManager manager) {
        if (service == null || service.isBlank())
            throw new IllegalArgumentException("MCP service must not be blank");
        this.service = service;
        this.name = service;
        this.manager = Objects.requireNonNull(manager, "manager");
        this.namespace = manager.getNamespace();
    }

    public static PolarisMcpClientBuilder create(String service, PolarisMcpServerManager manager) {
        return new PolarisMcpClientBuilder(service, manager);
    }

    public PolarisMcpClientBuilder namespace(String value) {
        namespace = value;
        return this;
    }

    public PolarisMcpClientBuilder version(String value) {
        version = value;
        return this;
    }

    public PolarisMcpClientBuilder clientName(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("MCP client name must not be blank");
        name = value;
        return this;
    }

    public PolarisMcpClientBuilder asyncClient(boolean value) {
        async = value;
        return this;
    }

    public PolarisMcpClientBuilder requestTimeout(Duration value) {
        requestTimeout = positive(value);
        return this;
    }

    public PolarisMcpClientBuilder initializationTimeout(Duration value) {
        initializationTimeout = positive(value);
        return this;
    }

    public PolarisMcpClientBuilder defaultScheme(String value) {
        if (!"http".equals(value) && !"https".equals(value))
            throw new IllegalArgumentException("MCP scheme must be http or https");
        scheme = value;
        return this;
    }

    public PolarisMcpClientBuilder header(String key, String value) {
        headers.put(Objects.requireNonNull(key), Objects.requireNonNull(value));
        return this;
    }

    /** Invoked on each new connection. Allows refreshing a caller's access credentials. */
    public PolarisMcpClientBuilder headerProvider(Supplier<Map<String, String>> value) {
        headerProvider = Objects.requireNonNull(value);
        return this;
    }

    public PolarisMcpClientBuilder customizeHttpClient(Consumer<HttpClient.Builder> value) {
        httpCustomizer = Objects.requireNonNull(value);
        return this;
    }

    /**
     * Optional transport factory; the returned client must be owned exclusively by this wrapper.
     */
    public PolarisMcpClientBuilder clientFactory(
            Function<PolarisMcpEndpoint, McpClientWrapper> value) {
        clientFactory = Objects.requireNonNull(value);
        return this;
    }

    /**
     * Creates a logical client and stores its connection factory without querying Polaris or
     * initializing MCP. Toolkit registration triggers initialize(); a direct first call to
     * listTools() or callTool() also triggers it.
     */
    public PolarisMcpClientWrapper build() {
        PolarisMcpServiceKey key = new PolarisMcpServiceKey(namespace, service, version);
        if (clientFactory != null)
            return new PolarisMcpClientWrapper(
                    name, manager, key, clientFactory, initializationTimeout);
        String clientName = name, urlScheme = scheme;
        boolean useAsync = async;
        Duration request = requestTimeout, init = initializationTimeout;
        Map<String, String> fixedHeaders = Map.copyOf(headers);
        Supplier<Map<String, String>> provider = headerProvider;
        Consumer<HttpClient.Builder> customizer = httpCustomizer;
        Function<PolarisMcpEndpoint, McpClientWrapper> factory =
                endpoint -> {
                    // Invoked after connect() selects an instance; pass its URL to AgentScope.
                    McpClientBuilder builder = McpClientBuilder.create(clientName);
                    if ("mcp-streamable-http".equals(endpoint.protocol())) {
                        builder.streamableHttpTransport(endpoint.url(urlScheme))
                                .customizeStreamableHttpClient(customizer);
                    } else
                        builder.sseTransport(endpoint.url(urlScheme))
                                .customizeSseClient(customizer);
                    Map<String, String> merged = new LinkedHashMap<>(fixedHeaders);
                    Map<String, String> supplied = provider.get();
                    if (supplied != null) merged.putAll(supplied);
                    builder.headers(merged).timeout(request).initializationTimeout(init);
                    // Build the native client; the wrapper's connect() performs MCP initialization.
                    return useAsync ? builder.buildAsync().block(init) : builder.buildSync();
                };
        return new PolarisMcpClientWrapper(name, manager, key, factory, initializationTimeout);
    }

    private static Duration positive(Duration value) {
        if (value == null || value.isNegative() || value.isZero())
            throw new IllegalArgumentException("MCP timeout must be positive");
        return value;
    }
}
