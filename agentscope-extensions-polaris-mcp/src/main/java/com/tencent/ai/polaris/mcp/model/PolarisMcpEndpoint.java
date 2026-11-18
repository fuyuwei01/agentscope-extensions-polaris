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

package com.tencent.ai.polaris.mcp.model;

import com.tencent.ai.polaris.mcp.constant.PolarisMcpMetadataKeys;
import com.tencent.ai.polaris.mcp.exception.PolarisMcpException;
import com.tencent.polaris.api.pojo.Instance;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;

/** Immutable discovery data. Credentials and client policies are never part of this object. */
public record PolarisMcpEndpoint(
        String id,
        String host,
        int port,
        String protocol,
        String path,
        String version,
        int weight) {
    public static PolarisMcpEndpoint from(Instance instance) {
        String protocol = instance.getProtocol();
        String fallback;
        if (PolarisMcpMetadataKeys.PROTOCOL_STREAMABLE_HTTP.equals(protocol)) fallback = "/mcp";
        else if (PolarisMcpMetadataKeys.PROTOCOL_SSE.equals(protocol)) fallback = "/sse";
        else throw invalid("Unsupported MCP instance protocol");
        Map<String, String> metadata = instance.getMetadata();
        String path = metadata == null ? null : metadata.get(PolarisMcpMetadataKeys.ENDPOINT_PATH);
        if (path == null || path.isBlank()) path = fallback;
        String host = instance.getHost();
        if (host != null && host.startsWith("[") && host.endsWith("]"))
            host = host.substring(1, host.length() - 1);
        if (host == null
                || host.isBlank()
                || "0.0.0.0".equals(host)
                || "::".equals(host)
                || host.contains("/")
                || host.contains("@")
                || instance.getPort() < 1
                || instance.getPort() > 65535) throw invalid("Invalid MCP instance host or port");
        try {
            URI endpoint = URI.create(path);
            if (!path.startsWith("/")
                    || path.startsWith("//")
                    || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null
                    || endpoint.getHost() != null)
                throw invalid("MCP endpoint must be an absolute path without query or fragment");
            new URI("http", null, host, instance.getPort(), path, null, null);
        } catch (IllegalArgumentException | URISyntaxException e) {
            throw invalid("Invalid MCP instance endpoint");
        }
        return new PolarisMcpEndpoint(
                instance.getId(),
                host,
                instance.getPort(),
                protocol,
                path,
                instance.getVersion(),
                instance.getWeight());
    }

    public String url(String scheme) {
        if (!"http".equals(scheme) && !"https".equals(scheme))
            throw invalid("MCP scheme must be http or https");
        try {
            // The published path may already contain percent escapes; retain them verbatim.
            URI authority = new URI(scheme, null, host, port, null, null, null);
            return authority.toASCIIString() + path;
        } catch (URISyntaxException e) {
            throw invalid("Invalid MCP URL");
        }
    }

    public boolean sameConnection(PolarisMcpEndpoint other) {
        return other != null
                && Objects.equals(id, other.id)
                && host.equals(other.host)
                && port == other.port
                && protocol.equals(other.protocol)
                && path.equals(other.path);
    }

    private static PolarisMcpException invalid(String message) {
        return new PolarisMcpException(PolarisMcpException.Reason.ENDPOINT, message);
    }
}
