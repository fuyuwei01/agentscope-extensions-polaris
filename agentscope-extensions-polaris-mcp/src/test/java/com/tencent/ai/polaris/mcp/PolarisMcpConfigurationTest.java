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

package com.tencent.ai.polaris.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tencent.ai.polaris.mcp.client.PolarisMcpClientBuilder;
import com.tencent.ai.polaris.mcp.discovery.PolarisMcpServerManager;
import com.tencent.ai.polaris.mcp.exception.PolarisMcpException;
import com.tencent.ai.polaris.mcp.model.PolarisMcpEndpoint;
import com.tencent.ai.polaris.mcp.model.PolarisMcpServiceKey;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.pojo.DefaultInstance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

class PolarisMcpConfigurationTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsBlankDiscoveryIdentity(String value) {
        assertThrows(
                IllegalArgumentException.class, () -> new PolarisMcpServiceKey(value, "tools"));
        assertThrows(
                IllegalArgumentException.class, () -> new PolarisMcpServiceKey("default", value));
        try (var manager = new PolarisMcpServerManager(mock(ConsumerAPI.class), "default")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PolarisMcpClientBuilder.create(value, manager));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PolarisMcpClientBuilder.create("tools", manager).clientName(value));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            PolarisMcpClientBuilder.create("tools", manager)
                                    .namespace(value)
                                    .build());
        }
    }

    @Test
    void normalizesOptionalVersionsWithoutChangingExplicitSelectors() {
        PolarisMcpServiceKey defaultKey = new PolarisMcpServiceKey("demo", "tools");
        assertEquals(defaultKey, new PolarisMcpServiceKey("demo", "tools", " "));
        assertEquals(defaultKey, new PolarisMcpServiceKey("demo", "tools", ""));
        assertEquals("v2", new PolarisMcpServiceKey("demo", "tools", "v2").version());
    }

    @Test
    void validatesClientPoliciesBeforeAcquiringDiscovery() {
        ConsumerAPI consumer = mock(ConsumerAPI.class);
        try (var manager = new PolarisMcpServerManager(consumer, "default")) {
            var builder = PolarisMcpClientBuilder.create("tools", manager);
            for (Duration timeout : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
                assertThrows(IllegalArgumentException.class, () -> builder.requestTimeout(timeout));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> builder.initializationTimeout(timeout));
            }
            assertThrows(IllegalArgumentException.class, () -> builder.defaultScheme("ftp"));
            assertThrows(NullPointerException.class, () -> builder.header(null, "token"));
            assertThrows(NullPointerException.class, () -> builder.header("Authorization", null));
            assertThrows(NullPointerException.class, () -> builder.headerProvider(null));
            assertThrows(NullPointerException.class, () -> builder.customizeHttpClient(null));
            assertThrows(NullPointerException.class, () -> builder.clientFactory(null));
            try (var client =
                    builder.namespace("demo")
                            .version("v2")
                            .clientName("business")
                            .defaultScheme("https")
                            .requestTimeout(Duration.ofSeconds(2))
                            .initializationTimeout(Duration.ofSeconds(1))
                            .build()) {
                assertEquals("business", client.getName());
                assertFalse(client.isInitialized());
                assertNull(client.getSelectedEndpoint());
            }
            verifyNoInteractions(consumer);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "relative",
                "//other/mcp",
                "/mcp?key=value",
                "/mcp#fragment",
                "/bad%escape",
                "/has space"
            })
    void rejectsPathsThatCannotBeUsedAsPublishedEndpoints(String path) {
        var error =
                assertThrows(
                        PolarisMcpException.class,
                        () ->
                                PolarisMcpEndpoint.from(
                                        PolarisMcpDiscoveryTest.instance("a", "mcp-sse", path)));
        assertEquals(PolarisMcpException.Reason.ENDPOINT, error.getReason());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "0.0.0.0", "::", "host/path", "user@host", "invalid host"})
    void rejectsUnusableAdvertiseHosts(String host) {
        DefaultInstance instance = PolarisMcpDiscoveryTest.instance("a", "mcp-sse", "/events");
        instance.setHost(host);
        assertThrows(PolarisMcpException.class, () -> PolarisMcpEndpoint.from(instance));
    }

    @Test
    void retainsEscapedPathsAndDefaultsMissingMetadataForBracketedIpv6() {
        DefaultInstance instance =
                PolarisMcpDiscoveryTest.instance("a", "mcp-sse", "/events%20path");
        instance.setHost("[::1]");
        assertEquals(
                "http://[::1]:18001/events%20path", PolarisMcpEndpoint.from(instance).url("http"));
        instance.setMetadata(null);
        assertEquals("/sse", PolarisMcpEndpoint.from(instance).path());
        instance.setMetadata(Map.of("mcp-endpoint-path", " "));
        assertEquals("/sse", PolarisMcpEndpoint.from(instance).path());
        for (int port : new int[] {0, -1, 65536}) {
            instance.setPort(port);
            assertThrows(PolarisMcpException.class, () -> PolarisMcpEndpoint.from(instance));
        }
        instance.setPort(65535);
        assertEquals(65535, PolarisMcpEndpoint.from(instance).port());
    }

    @Test
    void connectionIdentityIncludesAddressTransportAndPathButNotWeightOrVersion() {
        var endpoint = new PolarisMcpEndpoint("a", "localhost", 80, "mcp-sse", "/sse", "v1", 100);
        assertTrue(
                endpoint.sameConnection(
                        new PolarisMcpEndpoint("a", "localhost", 80, "mcp-sse", "/sse", "v2", 50)));
        assertFalse(endpoint.sameConnection(null));
        for (var changed :
                List.of(
                        new PolarisMcpEndpoint("b", "localhost", 80, "mcp-sse", "/sse", "v1", 100),
                        new PolarisMcpEndpoint("a", "other", 80, "mcp-sse", "/sse", "v1", 100),
                        new PolarisMcpEndpoint("a", "localhost", 81, "mcp-sse", "/sse", "v1", 100),
                        new PolarisMcpEndpoint(
                                "a", "localhost", 80, "mcp-streamable-http", "/sse", "v1", 100),
                        new PolarisMcpEndpoint(
                                "a", "localhost", 80, "mcp-sse", "/events", "v1", 100))) {
            assertFalse(endpoint.sameConnection(changed));
        }
        assertThrows(PolarisMcpException.class, () -> endpoint.url("ftp"));
        assertThrows(
                PolarisMcpException.class,
                () ->
                        new PolarisMcpEndpoint("a", "invalid host", 80, "mcp-sse", "/sse", null, 1)
                                .url("http"));
    }
}
