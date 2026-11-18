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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tencent.ai.polaris.mcp.client.PolarisMcpClientBuilder;
import com.tencent.ai.polaris.mcp.client.PolarisMcpClientWrapper;
import com.tencent.ai.polaris.mcp.discovery.PolarisMcpServerManager;
import com.tencent.ai.polaris.mcp.exception.PolarisMcpException;
import com.tencent.ai.polaris.mcp.model.PolarisMcpEndpoint;
import com.tencent.ai.polaris.mcp.model.PolarisMcpServiceKey;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.listener.ServiceListener;
import com.tencent.polaris.api.pojo.DefaultInstance;
import com.tencent.polaris.api.pojo.DefaultServiceInstances;
import com.tencent.polaris.api.pojo.Instance;
import com.tencent.polaris.api.pojo.ServiceChangeEvent;
import com.tencent.polaris.api.pojo.ServiceKey;
import com.tencent.polaris.api.rpc.InstancesResponse;
import com.tencent.polaris.api.rpc.UnWatchServiceRequest;
import com.tencent.polaris.api.rpc.WatchServiceRequest;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

class PolarisMcpDiscoveryTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    static DefaultInstance instance(String id, String protocol, String path) {
        DefaultInstance instance = new DefaultInstance();
        instance.setId(id);
        instance.setHost("127.0.0.1");
        instance.setPort("a".equals(id) ? 18001 : 18002);
        instance.setProtocol(protocol);
        instance.setHealthy(true);
        instance.setIsolated(false);
        instance.setWeight(100);
        instance.setMetadata(path == null ? Map.of() : Map.of("mcp-endpoint-path", path));
        return instance;
    }

    static McpSchema.Tool tool(String schema) {
        return McpSchema.Tool.builder()
                .name("lookup")
                .description("Lookup")
                .inputSchema(McpJsonMapper.getDefault(), schema)
                .build();
    }

    static final McpSchema.Tool TOOL =
            tool("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");

    static final class Registry {
        final ConsumerAPI consumer = mock(ConsumerAPI.class);
        final AtomicReference<List<Instance>> instances = new AtomicReference<>(List.of());
        final AtomicReference<ServiceListener> listener = new AtomicReference<>();

        Registry(Instance... initial) {
            instances.set(List.of(initial));
            when(consumer.getHealthyInstances(any()))
                    .thenAnswer(invocation -> response(instances.get()));
            doAnswer(
                            invocation -> {
                                listener.set(
                                        ((WatchServiceRequest) invocation.getArgument(0))
                                                .getListeners()
                                                .get(0));
                                return null;
                            })
                    .when(consumer)
                    .watchService(any());
        }

        void update(Instance... next) {
            instances.set(List.of(next));
            listener.get()
                    .onEvent(ServiceChangeEvent.builder().allInstances(instances.get()).build());
        }

        static InstancesResponse response(List<Instance> instances) {
            return new InstancesResponse(
                    new DefaultServiceInstances(new ServiceKey("default", "tools"), instances),
                    null,
                    null);
        }
    }

    static class FakeClient extends McpClientWrapper {
        final AtomicInteger inits = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final List<McpSchema.Tool> tools;
        volatile boolean failInit;
        volatile boolean failCall;
        CountDownLatch entered;
        CountDownLatch proceed;

        FakeClient(List<McpSchema.Tool> tools) {
            super("native");
            this.tools = tools;
        }

        @Override
        public Mono<Void> initialize() {
            return Mono.fromRunnable(
                    () -> {
                        inits.incrementAndGet();
                        if (entered != null) {
                            entered.countDown();
                            try {
                                assertTrue(proceed.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        if (failInit) throw new IllegalStateException("init failed");
                        initialized = true;
                    });
        }

        @Override
        public Mono<List<McpSchema.Tool>> listTools() {
            return Mono.just(tools);
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> args) {
            calls.incrementAndGet();
            return failCall
                    ? Mono.error(new IllegalStateException("connection lost"))
                    : Mono.just(
                            McpSchema.CallToolResult.builder()
                                    .addTextContent("ok")
                                    .isError(false)
                                    .build());
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    @Test
    void resolvesPublishedPathDefaultsAndIpv6AndRejectsInvalidEndpoints() {
        DefaultInstance a = instance("a", "mcp-streamable-http", "/context/custom%20path");
        a.setHost("::1");
        assertEquals(
                "https://[::1]:18001/context/custom%20path",
                PolarisMcpEndpoint.from(a).url("https"));
        assertEquals("/sse", PolarisMcpEndpoint.from(instance("a", "mcp-sse", null)).path());
        assertEquals(
                "/mcp", PolarisMcpEndpoint.from(instance("a", "mcp-streamable-http", null)).path());
        a.setProtocol("http");
        assertThrows(PolarisMcpException.class, () -> PolarisMcpEndpoint.from(a));
        a.setProtocol("mcp-sse");
        a.setHost("0.0.0.0");
        assertThrows(PolarisMcpException.class, () -> PolarisMcpEndpoint.from(a));
    }

    @Test
    void sharesOnlyDiscoveryAndRetainsSessionUntilItsInstanceDisappears() throws Exception {
        DefaultInstance a = instance("a", "mcp-streamable-http", "/mcp");
        DefaultInstance b = instance("b", "mcp-streamable-http", "/mcp");
        Registry registry = new Registry(a);
        Map<String, FakeClient> delegates = new ConcurrentHashMap<>();
        AtomicInteger count = new AtomicInteger();
        try (PolarisMcpServerManager manager =
                new PolarisMcpServerManager(registry.consumer, "default")) {
            PolarisMcpClientWrapper first =
                    PolarisMcpClientBuilder.create("tools", manager)
                            .clientFactory(
                                    endpoint -> {
                                        FakeClient client = new FakeClient(List.of(TOOL));
                                        delegates.put(endpoint.id(), client);
                                        count.incrementAndGet();
                                        return client;
                                    })
                            .build();
            PolarisMcpClientWrapper second =
                    PolarisMcpClientBuilder.create("tools", manager)
                            .clientFactory(
                                    endpoint -> {
                                        count.incrementAndGet();
                                        return new FakeClient(List.of(TOOL));
                                    })
                            .build();
            try (first;
                    second) {
                first.initialize().block(WAIT);
                second.initialize().block(WAIT);
                assertEquals(2, count.get());
                verify(registry.consumer, times(1)).watchService(any());
                registry.update(a, b);
                first.callTool("lookup", Map.of()).block(WAIT);
                assertEquals("a", first.getSelectedEndpoint().id());
                registry.update(b);
                first.callTool("lookup", Map.of()).block(WAIT);
                assertEquals("b", first.getSelectedEndpoint().id());
                assertEquals(1, delegates.get("b").calls.get());
            }
            // A queued reconnect can still be acquiring a lease when close() returns.
            // It releases that lease as soon as acquisition observes the closed client.
            long closeDeadline = System.nanoTime() + WAIT.toNanos();
            while (manager.subscriptionCount() != 0 && System.nanoTime() < closeDeadline) {
                Thread.sleep(10);
            }
            assertEquals(0, manager.subscriptionCount());
            ArgumentCaptor<UnWatchServiceRequest> request =
                    ArgumentCaptor.forClass(UnWatchServiceRequest.class);
            ArgumentCaptor<WatchServiceRequest> watches =
                    ArgumentCaptor.forClass(WatchServiceRequest.class);
            // An acquisition already in progress at close may briefly create another subscription.
            // Every watch must have a matching unwatch with only its own listener removed.
            verify(registry.consumer, atLeastOnce()).watchService(watches.capture());
            verify(registry.consumer, timeout(5000).times(watches.getAllValues().size()))
                    .unWatchService(request.capture());
            for (UnWatchServiceRequest unwatch : request.getAllValues()) {
                assertFalse(unwatch.isRemoveAll());
                assertEquals(1, unwatch.getListeners().size());
            }
            assertEquals(
                    watches.getAllValues().stream()
                            .flatMap(watch -> watch.getListeners().stream())
                            .collect(Collectors.toSet()),
                    request.getAllValues().stream()
                            .flatMap(unwatch -> unwatch.getListeners().stream())
                            .collect(Collectors.toSet()));
        }
    }

    @Test
    void filtersAllUnhealthyIsolatedAndZeroWeightFallbacks() {
        DefaultInstance a = instance("a", "mcp-streamable-http", null);
        a.setHealthy(false);
        DefaultInstance b = instance("b", "mcp-streamable-http", null);
        b.setIsolated(true);
        DefaultInstance c = instance("c", "mcp-streamable-http", null);
        c.setWeight(0);
        Registry registry = new Registry(a, b, c);
        AtomicInteger creations = new AtomicInteger();
        try (PolarisMcpServerManager manager =
                        new PolarisMcpServerManager(registry.consumer, "default");
                PolarisMcpClientWrapper client =
                        PolarisMcpClientBuilder.create("tools", manager)
                                .clientFactory(
                                        endpoint -> {
                                            creations.incrementAndGet();
                                            return new FakeClient(List.of(TOOL));
                                        })
                                .build()) {
            PolarisMcpException error =
                    assertThrows(PolarisMcpException.class, () -> client.initialize().block(WAIT));
            assertEquals(PolarisMcpException.Reason.NO_INSTANCE, error.getReason());
            assertEquals(0, creations.get());
        }
    }

    @Test
    void retriesFailedInitializationAndCoalescesConcurrentInitialization() throws Exception {
        Registry registry = new Registry(instance("a", "mcp-streamable-http", null));
        FakeClient failed = new FakeClient(List.of(TOOL));
        failed.failInit = true;
        FakeClient success = new FakeClient(List.of(TOOL));
        success.entered = new CountDownLatch(1);
        success.proceed = new CountDownLatch(1);
        AtomicInteger factories = new AtomicInteger();
        try (PolarisMcpServerManager manager =
                        new PolarisMcpServerManager(registry.consumer, "default");
                PolarisMcpClientWrapper client =
                        PolarisMcpClientBuilder.create("tools", manager)
                                .clientFactory(
                                        endpoint ->
                                                factories.incrementAndGet() == 1 ? failed : success)
                                .build()) {
            assertThrows(PolarisMcpException.class, () -> client.initialize().block(WAIT));
            CompletableFuture<Void> one = client.initialize().toFuture();
            assertTrue(success.entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> two = client.initialize().toFuture();
            success.proceed.countDown();
            one.get(5, TimeUnit.SECONDS);
            two.get(5, TimeUnit.SECONDS);
            assertEquals(2, factories.get());
            assertEquals(1, success.inits.get());
            assertEquals(1, failed.closes.get());
        }
    }

    @Test
    void closedClientCannotPublishAnInflightInitialization() throws Exception {
        Registry registry = new Registry(instance("a", "mcp-streamable-http", null));
        FakeClient fake = new FakeClient(List.of(TOOL));
        fake.entered = new CountDownLatch(1);
        fake.proceed = new CountDownLatch(1);
        try (PolarisMcpServerManager manager =
                new PolarisMcpServerManager(registry.consumer, "default")) {
            PolarisMcpClientWrapper client =
                    PolarisMcpClientBuilder.create("tools", manager)
                            .clientFactory(endpoint -> fake)
                            .build();
            CompletableFuture<Void> init = client.initialize().toFuture();
            assertTrue(fake.entered.await(5, TimeUnit.SECONDS));
            client.close();
            fake.proceed.countDown();
            assertThrows(Exception.class, () -> init.get(5, TimeUnit.SECONDS));
            assertFalse(client.isInitialized());
            assertNull(client.getSelectedEndpoint());
            assertEquals(1, fake.closes.get());
            assertEquals(0, manager.subscriptionCount());
        }
    }

    @Test
    void closingDuringDiscoveryAcquisitionReleasesTheLateLease() throws Exception {
        Registry registry = new Registry(instance("a", "mcp-streamable-http", null));
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        FakeClient fake = new FakeClient(List.of(TOOL));
        try (PolarisMcpServerManager manager =
                        new PolarisMcpServerManager(registry.consumer, "default") {
                            @Override
                            public Lease acquire(PolarisMcpServiceKey key, Runnable observer) {
                                Lease lease = super.acquire(key, observer);
                                acquired.countDown();
                                try {
                                    if (!proceed.await(5, TimeUnit.SECONDS)) {
                                        lease.close();
                                        throw new IllegalStateException("Acquisition did not resume");
                                    }
                                } catch (InterruptedException e) {
                                    lease.close();
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                }
                                return lease;
                            }
                        };
                PolarisMcpClientWrapper client =
                        PolarisMcpClientBuilder.create("tools", manager)
                                .clientFactory(endpoint -> fake)
                                .build()) {
            CompletableFuture<Void> init = client.initialize().toFuture();
            try {
                assertTrue(acquired.await(5, TimeUnit.SECONDS));
                client.close();
                // The acquiring worker still owns the lease until acquire() returns.
                assertEquals(1, manager.subscriptionCount());
            } finally {
                proceed.countDown();
            }
            assertThrows(Exception.class, () -> init.get(5, TimeUnit.SECONDS));
            assertEquals(0, manager.subscriptionCount());
            assertFalse(client.isInitialized());
            assertEquals(0, fake.inits.get());
            verify(registry.consumer, times(1)).unWatchService(any());
        } finally {
            proceed.countDown();
        }
    }

    @Test
    void queuedInitializationDoesNotAcquireDiscoveryAfterClose() throws Exception {
        Registry registry = new Registry(instance("a", "mcp-streamable-http", null));
        Scheduler scheduler = Schedulers.newSingle("mcp-queued-close-test");
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        try (PolarisMcpServerManager manager =
                        new PolarisMcpServerManager(registry.consumer, "default") {
                            @Override
                            public Scheduler scheduler() {
                                return scheduler;
                            }
                        };
                PolarisMcpClientWrapper client =
                        PolarisMcpClientBuilder.create("tools", manager)
                                .clientFactory(endpoint -> new FakeClient(List.of(TOOL)))
                                .build()) {
            scheduler.schedule(
                    () -> {
                        occupied.countDown();
                        try {
                            proceed.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            assertTrue(occupied.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> init = client.initialize().toFuture();
            client.close();
            proceed.countDown();
            assertThrows(Exception.class, () -> init.get(5, TimeUnit.SECONDS));
            assertEquals(0, manager.subscriptionCount());
            verify(registry.consumer, never()).watchService(any());
        } finally {
            proceed.countDown();
            scheduler.dispose();
        }
    }

    @Test
    void neverReplaysAFailedToolCall() {
        Registry registry = new Registry(instance("a", "mcp-streamable-http", null));
        AtomicInteger sentCalls = new AtomicInteger();
        try (PolarisMcpServerManager manager =
                        new PolarisMcpServerManager(registry.consumer, "default");
                PolarisMcpClientWrapper client =
                        PolarisMcpClientBuilder.create("tools", manager)
                                .clientFactory(
                                        endpoint ->
                                                new FakeClient(List.of(TOOL)) {
                                                    @Override
                                                    public Mono<McpSchema.CallToolResult> callTool(
                                                            String name, Map<String, Object> args) {
                                                        sentCalls.incrementAndGet();
                                                        return Mono.error(
                                                                new IllegalStateException(
                                                                        "timeout after send"));
                                                    }
                                                })
                                .build()) {
            assertThrows(
                    IllegalStateException.class,
                    () -> client.callTool("lookup", Map.of()).block(WAIT));
            assertEquals(1, sentCalls.get());
        }
    }

    @Test
    void refusesIncompatibleReplicaSchemas() {
        Registry registry = new Registry(instance("a", "mcp-streamable-http", null));
        try (PolarisMcpServerManager manager =
                        new PolarisMcpServerManager(registry.consumer, "default");
                PolarisMcpClientWrapper client =
                        PolarisMcpClientBuilder.create("tools", manager)
                                .clientFactory(
                                        endpoint ->
                                                new FakeClient(
                                                        List.of(
                                                                "a".equals(endpoint.id())
                                                                        ? TOOL
                                                                        : tool(
                                                                                "{\"type\":\"object\",\"required\":[\"id\"]}"))))
                                .build()) {
            client.initialize().block(WAIT);
            registry.update(instance("b", "mcp-streamable-http", null));
            assertThrows(
                    PolarisMcpException.class,
                    () -> client.callTool("lookup", Map.of()).block(WAIT));
            assertNull(client.getSelectedEndpoint());
        }
    }

    @Test
    void preservesEstablishedConnectionWhenRegistryIsTemporarilyUnavailable() {
        Registry registry = new Registry(instance("a", "mcp-streamable-http", null));
        try (PolarisMcpServerManager manager =
                        new PolarisMcpServerManager(
                                registry.consumer, "default", Duration.ofMillis(100));
                PolarisMcpClientWrapper client =
                        PolarisMcpClientBuilder.create("tools", manager)
                                .clientFactory(endpoint -> new FakeClient(List.of(TOOL)))
                                .build()) {
            client.initialize().block(WAIT);
            when(registry.consumer.getHealthyInstances(any()))
                    .thenThrow(new IllegalStateException("offline"));
            assertNotNull(client.callTool("lookup", Map.of()).block(WAIT));
            assertEquals("a", client.getSelectedEndpoint().id());
        }
    }

    @Test
    void rejectsAnInitializationBuiltFromAStaleSnapshot() throws Exception {
        Registry registry = new Registry(instance("a", "mcp-streamable-http", null));
        FakeClient a = new FakeClient(List.of(TOOL));
        a.entered = new CountDownLatch(1);
        a.proceed = new CountDownLatch(1);
        FakeClient b = new FakeClient(List.of(TOOL));
        try (PolarisMcpServerManager manager =
                        new PolarisMcpServerManager(registry.consumer, "default");
                PolarisMcpClientWrapper client =
                        PolarisMcpClientBuilder.create("tools", manager)
                                .clientFactory(endpoint -> "a".equals(endpoint.id()) ? a : b)
                                .build()) {
            CompletableFuture<Void> init = client.initialize().toFuture();
            assertTrue(a.entered.await(5, TimeUnit.SECONDS));
            registry.update(instance("b", "mcp-streamable-http", null));
            a.proceed.countDown();
            init.get(5, TimeUnit.SECONDS);
            assertEquals("b", client.getSelectedEndpoint().id());
            assertEquals(1, a.closes.get());
            assertEquals(1, b.inits.get());
        }
    }

    @Test
    void idleRecycleReconnectsAndDoesNotRecycleAnInflightCall() throws Exception {
        Registry registry = new Registry(instance("a", "mcp-streamable-http", null));
        AtomicInteger creations = new AtomicInteger();
        Sinks.One<McpSchema.CallToolResult> pending = Sinks.one();
        try (PolarisMcpServerManager manager =
                        new PolarisMcpServerManager(registry.consumer, "default");
                PolarisMcpClientWrapper client =
                        PolarisMcpClientBuilder.create("tools", manager)
                                .clientFactory(
                                        endpoint -> {
                                            int number = creations.incrementAndGet();
                                            return new FakeClient(List.of(TOOL)) {
                                                @Override
                                                public Mono<McpSchema.CallToolResult> callTool(
                                                        String name, Map<String, Object> args) {
                                                    return number == 1
                                                            ? pending.asMono()
                                                            : super.callTool(name, args);
                                                }
                                            };
                                        })
                                .build()) {
            client.initialize().block(WAIT);
            CompletableFuture<McpSchema.CallToolResult> inflight =
                    client.callTool("lookup", Map.of()).toFuture();
            client.recycleIdle(Duration.ZERO);
            assertNotNull(client.getSelectedEndpoint());
            assertEquals(1, manager.subscriptionCount());
            pending.tryEmitValue(McpSchema.CallToolResult.builder().addTextContent("done").build());
            inflight.get(5, TimeUnit.SECONDS);
            client.recycleIdle(Duration.ZERO);
            assertNull(client.getSelectedEndpoint());
            assertEquals(0, manager.subscriptionCount());
            client.callTool("lookup", Map.of()).block(WAIT);
            assertEquals(2, creations.get());
            assertEquals(1, manager.subscriptionCount());
        }
    }

    @Test
    void removalLetsAnAlreadySentCallDrainWithoutReplay() throws Exception {
        Registry registry = new Registry(instance("a", "mcp-streamable-http", null));
        Sinks.One<McpSchema.CallToolResult> pending = Sinks.one();
        FakeClient old =
                new FakeClient(List.of(TOOL)) {
                    @Override
                    public Mono<McpSchema.CallToolResult> callTool(
                            String name, Map<String, Object> args) {
                        calls.incrementAndGet();
                        return pending.asMono();
                    }
                };
        FakeClient replacement = new FakeClient(List.of(TOOL));
        try (PolarisMcpServerManager manager =
                        new PolarisMcpServerManager(registry.consumer, "default");
                PolarisMcpClientWrapper client =
                        PolarisMcpClientBuilder.create("tools", manager)
                                .clientFactory(
                                        endpoint -> "a".equals(endpoint.id()) ? old : replacement)
                                .build()) {
            client.initialize().block(WAIT);
            CompletableFuture<McpSchema.CallToolResult> sent =
                    client.callTool("lookup", Map.of()).toFuture();
            registry.update(instance("b", "mcp-streamable-http", null));
            client.initialize().block(WAIT);
            assertEquals("b", client.getSelectedEndpoint().id());
            assertEquals(0, old.closes.get());
            pending.tryEmitValue(
                    McpSchema.CallToolResult.builder().addTextContent("old result").build());
            assertEquals(
                    "old result",
                    ((McpSchema.TextContent) sent.get(5, TimeUnit.SECONDS).content().get(0))
                            .text());
            assertEquals(1, old.calls.get());
            assertEquals(0, replacement.calls.get());
        }
        assertEquals(1, old.closes.get());
    }

    @Test
    void managerShutdownAlsoClosesItsConsumers() {
        Registry registry = new Registry(instance("a", "mcp-streamable-http", null));
        FakeClient fake = new FakeClient(List.of(TOOL));
        PolarisMcpServerManager manager = new PolarisMcpServerManager(registry.consumer, "default");
        PolarisMcpClientWrapper client =
                PolarisMcpClientBuilder.create("tools", manager)
                        .clientFactory(endpoint -> fake)
                        .build();
        client.initialize().block(WAIT);
        manager.close();
        assertFalse(client.isInitialized());
        assertEquals(1, fake.closes.get());
        assertThrows(PolarisMcpException.class, () -> client.initialize().block(WAIT));
    }

    @Test
    void cancellationReleasesTheInflightCountAndAllowsIdleRecycling() {
        Registry registry = new Registry(instance("a", "mcp-streamable-http", null));
        FakeClient fake =
                new FakeClient(List.of(TOOL)) {
                    @Override
                    public Mono<McpSchema.CallToolResult> callTool(
                            String name, Map<String, Object> args) {
                        return Mono.never();
                    }
                };
        try (PolarisMcpServerManager manager =
                        new PolarisMcpServerManager(registry.consumer, "default");
                PolarisMcpClientWrapper client =
                        PolarisMcpClientBuilder.create("tools", manager)
                                .clientFactory(endpoint -> fake)
                                .build()) {
            client.initialize().block(WAIT);
            CompletableFuture<McpSchema.CallToolResult> call =
                    client.callTool("lookup", Map.of()).toFuture();
            call.cancel(true);
            client.recycleIdle(Duration.ZERO);
            assertNull(client.getSelectedEndpoint());
            assertEquals(0, manager.subscriptionCount());
        }
        assertEquals(1, fake.closes.get());
    }

    @Test
    void aVersionSelectorDoesNotFallBackToOtherApplicationVersions() {
        DefaultInstance a = instance("a", "mcp-streamable-http", null);
        a.setVersion("1.0.0");
        DefaultInstance b = instance("b", "mcp-streamable-http", null);
        b.setVersion("2.0.0");
        Registry registry = new Registry(a, b);
        try (PolarisMcpServerManager manager =
                        new PolarisMcpServerManager(registry.consumer, "default");
                PolarisMcpClientWrapper client =
                        PolarisMcpClientBuilder.create("tools", manager)
                                .version("2.0.0")
                                .clientFactory(endpoint -> new FakeClient(List.of(TOOL)))
                                .build()) {
            client.initialize().block(WAIT);
            assertEquals("b", client.getSelectedEndpoint().id());
            registry.update(a);
            assertThrows(
                    PolarisMcpException.class,
                    () -> client.callTool("lookup", Map.of()).block(WAIT));
            assertNull(client.getSelectedEndpoint());
        }
    }

    @Test
    void watchDeleteWinsOverAnInflightReconciliationQuery() throws Exception {
        Registry registry = new Registry(instance("a", "mcp-streamable-http", null));
        CountDownLatch queryEntered = new CountDownLatch(1), queryRelease = new CountDownLatch(1);
        try (PolarisMcpServerManager manager =
                        new PolarisMcpServerManager(
                                registry.consumer, "default", Duration.ofMillis(100));
                PolarisMcpClientWrapper client =
                        PolarisMcpClientBuilder.create("tools", manager)
                                .clientFactory(endpoint -> new FakeClient(List.of(TOOL)))
                                .build()) {
            client.initialize().block(WAIT);
            InstancesResponse stale = Registry.response(registry.instances.get());
            when(registry.consumer.getHealthyInstances(any()))
                    .thenAnswer(
                            invocation -> {
                                queryEntered.countDown();
                                assertTrue(queryRelease.await(5, TimeUnit.SECONDS));
                                return stale;
                            });
            assertTrue(queryEntered.await(5, TimeUnit.SECONDS));
            registry.update();
            queryRelease.countDown();
            assertThrows(
                    PolarisMcpException.class,
                    () -> client.callTool("lookup", Map.of()).block(WAIT));
            assertNull(client.getSelectedEndpoint());
        } finally {
            queryRelease.countDown();
        }
    }
}
