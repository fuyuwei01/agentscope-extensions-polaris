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

package com.tencent.ai.polaris.mcp.discovery;

import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.mcp.exception.PolarisMcpException;
import com.tencent.ai.polaris.mcp.model.PolarisMcpEndpoint;
import com.tencent.ai.polaris.mcp.model.PolarisMcpServiceKey;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.listener.ServiceListener;
import com.tencent.polaris.api.pojo.Instance;
import com.tencent.polaris.api.pojo.InstanceType;
import com.tencent.polaris.api.pojo.RetStatus;
import com.tencent.polaris.api.rpc.GetHealthyInstancesRequest;
import com.tencent.polaris.api.rpc.InstancesResponse;
import com.tencent.polaris.api.rpc.ServiceCallResult;
import com.tencent.polaris.api.rpc.UnWatchServiceRequest;
import com.tencent.polaris.api.rpc.WatchServiceRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Shares immutable discovery snapshots, never MCP connections or credentials. */
public class PolarisMcpServerManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PolarisMcpServerManager.class);
    private final Supplier<ConsumerAPI> consumer;
    private final String namespace;
    private final Map<PolarisMcpServiceKey, Subscription> subscriptions = new HashMap<>();
    private final Scheduler io = Schedulers.newBoundedElastic(8, 256, "polaris-mcp-io");
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "polaris-mcp-timer");
                        t.setDaemon(true);
                        return t;
                    });
    private boolean closed;

    public PolarisMcpServerManager(PolarisContextManager context) {
        this(context::consumerAPI, context.getNamespace(), Duration.ofSeconds(30));
    }

    public PolarisMcpServerManager(ConsumerAPI consumer, String namespace) {
        this(consumer, namespace, Duration.ofSeconds(30));
    }

    public PolarisMcpServerManager(
            ConsumerAPI consumer, String namespace, Duration reconcileInterval) {
        this(() -> Objects.requireNonNull(consumer, "consumer"), namespace, reconcileInterval);
    }

    private PolarisMcpServerManager(
            Supplier<ConsumerAPI> consumer, String namespace, Duration reconcileInterval) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        if (reconcileInterval == null
                || reconcileInterval.isZero()
                || reconcileInterval.isNegative())
            throw new IllegalArgumentException("reconcile interval must be positive");
        timer.scheduleWithFixedDelay(
                this::reconcile,
                reconcileInterval.toMillis(),
                reconcileInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public String getNamespace() {
        return namespace;
    }

    public Scheduler scheduler() {
        return io;
    }

    public ScheduledExecutorService timer() {
        return timer;
    }

    public synchronized int subscriptionCount() {
        return subscriptions.size();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public void reportCall(
            PolarisMcpServiceKey key,
            PolarisMcpEndpoint endpoint,
            String tool,
            long elapsedNanos,
            boolean success) {
        ServiceCallResult result = new ServiceCallResult();
        result.setNamespace(key.namespace());
        result.setService(key.service());
        result.setHost(endpoint.host());
        result.setPort(endpoint.port());
        result.setMethod(tool);
        result.setDelay(TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        result.setRetStatus(success ? RetStatus.RetSuccess : RetStatus.RetFail);
        result.setInstanceType(InstanceType.MCP);
        try {
            consumer.get().updateServiceCallResult(result);
        } catch (RuntimeException e) {
            log.debug("MCP call reporting unavailable");
        }
    }

    /** The caller must perform acquisition on an IO thread. */
    public Lease acquire(PolarisMcpServiceKey key, Runnable observer) {
        Subscription sub;
        boolean start;
        synchronized (this) {
            if (closed)
                throw new PolarisMcpException(
                        PolarisMcpException.Reason.CLOSED, "MCP manager is closed");
            sub = subscriptions.get(key);
            start = sub == null;
            if (start) {
                sub = new Subscription(key);
                subscriptions.put(key, sub);
            }
            sub.references++;
            sub.observers.add(observer);
        }
        Lease lease = new Lease(sub, observer);
        if (start) sub.start();
        try {
            sub.ready.join();
            return lease;
        } catch (RuntimeException e) {
            lease.close();
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof PolarisMcpException failure) throw failure;
            throw new PolarisMcpException(
                    PolarisMcpException.Reason.DISCOVERY, "MCP discovery failed", cause);
        }
    }

    private void reconcile() {
        List<Subscription> copy;
        synchronized (this) {
            if (closed) return;
            copy = List.copyOf(subscriptions.values());
        }
        for (Subscription sub : copy) {
            if (sub.querying.compareAndSet(false, true)) {
                try {
                    io.schedule(
                            () -> {
                                try {
                                    sub.fetch();
                                } catch (RuntimeException e) {
                                    log.debug(
                                            "MCP reconciliation unavailable for {}/{}",
                                            sub.key.namespace(),
                                            sub.key.service());
                                } finally {
                                    sub.querying.set(false);
                                }
                            });
                } catch (RuntimeException e) {
                    sub.querying.set(false);
                }
            }
        }
    }

    @Override
    public void close() {
        List<Subscription> copy;
        synchronized (this) {
            if (closed) return;
            closed = true;
            copy = List.copyOf(subscriptions.values());
            subscriptions.clear();
        }
        timer.shutdownNow();
        for (Subscription sub : copy) sub.stop();
        io.dispose();
    }

    public final class Lease implements AutoCloseable {
        private final Subscription sub;
        private final Runnable observer;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(Subscription sub, Runnable observer) {
            this.sub = sub;
            this.observer = observer;
        }

        public List<PolarisMcpEndpoint> endpoints() {
            return sub.endpoints;
        }

        public long generation() {
            return sub.generation;
        }

        public PolarisMcpException endpointFailure() {
            return sub.endpointFailure;
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) return;
            boolean stop;
            synchronized (PolarisMcpServerManager.this) {
                sub.observers.remove(observer);
                stop = --sub.references == 0;
                if (stop) subscriptions.remove(sub.key, sub);
            }
            if (stop) sub.stop();
        }
    }

    private final class Subscription {
        final PolarisMcpServiceKey key;
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final CopyOnWriteArrayList<Runnable> observers = new CopyOnWriteArrayList<>();
        final AtomicBoolean querying = new AtomicBoolean();
        final ServiceListener listener;
        volatile List<PolarisMcpEndpoint> endpoints = List.of();
        volatile long generation;
        volatile PolarisMcpException endpointFailure;
        int references;
        boolean stopped;
        boolean watching;

        Subscription(PolarisMcpServiceKey key) {
            this.key = key;
            // Snapshot publication is quick; observers only schedule work, never perform MCP IO
            // here.
            listener = event -> publish(event.getAllInstances(), -1);
        }

        void start() {
            try {
                consumer.get()
                        .watchService(
                                WatchServiceRequest.builder()
                                        .namespace(key.namespace())
                                        .service(key.service())
                                        .listeners(List.of(listener))
                                        .build());
                boolean undo;
                synchronized (this) {
                    watching = true;
                    undo = stopped;
                }
                if (undo) {
                    unwatch();
                    throw new IllegalStateException("Subscription closed during watch");
                }
                fetch();
                fetch();
                ready.complete(null);
            } catch (RuntimeException e) {
                ready.completeExceptionally(
                        new PolarisMcpException(
                                PolarisMcpException.Reason.DISCOVERY,
                                "Failed to discover MCP service "
                                        + key.namespace()
                                        + "/"
                                        + key.service(),
                                e));
            }
        }

        void fetch() {
            long before;
            synchronized (this) {
                if (stopped) return;
                before = generation;
            }
            GetHealthyInstancesRequest request = new GetHealthyInstancesRequest();
            request.setNamespace(key.namespace());
            request.setService(key.service());
            InstancesResponse result = consumer.get().getHealthyInstances(request);
            List<Instance> instances =
                    result == null || result.getInstances() == null
                            ? List.of()
                            : Arrays.asList(result.getInstances());
            // A watch event arriving during this request wins over the older query result.
            publish(instances, before);
        }

        void publish(List<Instance> instances, long expected) {
            List<PolarisMcpEndpoint> parsed = new ArrayList<>();
            PolarisMcpException invalid = null;
            if (instances != null)
                for (Instance instance : instances) {
                    if (instance == null
                            || !instance.isHealthy()
                            || instance.isIsolated()
                            || instance.getWeight() <= 0
                            || (key.version() != null
                                    && !key.version().equals(instance.getVersion()))) continue;
                    try {
                        parsed.add(PolarisMcpEndpoint.from(instance));
                    } catch (PolarisMcpException e) {
                        invalid = e;
                    }
                }
            synchronized (this) {
                if (stopped || (expected >= 0 && generation != expected)) return;
                endpoints = List.copyOf(parsed);
                endpointFailure = invalid;
                generation++;
            }
            for (Runnable observer : observers) {
                try {
                    observer.run();
                } catch (RuntimeException e) {
                    log.debug("MCP observer failed");
                }
            }
        }

        void stop() {
            boolean remove;
            synchronized (this) {
                if (stopped) return;
                stopped = true;
                endpoints = List.of();
                generation++;
                remove = watching;
            }
            ready.completeExceptionally(new IllegalStateException("Subscription closed"));
            for (Runnable observer : observers) {
                try {
                    observer.run();
                } catch (RuntimeException e) {
                    log.debug("MCP close observer failed");
                }
            }
            if (remove) unwatch();
        }

        void unwatch() {
            try {
                consumer.get()
                        .unWatchService(
                                UnWatchServiceRequest.UnWatchServiceRequestBuilder
                                        .anUnWatchServiceRequest()
                                        .namespace(key.namespace())
                                        .service(key.service())
                                        .listeners(List.of(listener))
                                        .removeAll(false)
                                        .build());
            } catch (RuntimeException e) {
                log.debug("MCP unsubscribe failed for {}/{}", key.namespace(), key.service());
            }
        }
    }
}
