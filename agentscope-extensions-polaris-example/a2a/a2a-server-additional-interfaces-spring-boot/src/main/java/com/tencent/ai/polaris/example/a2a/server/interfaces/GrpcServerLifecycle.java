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

package com.tencent.ai.polaris.example.a2a.server.interfaces;

import io.a2a.spec.TransportProtocol;
import io.a2a.transport.grpc.context.GrpcContextKeys;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/** Starts gRPC before ApplicationReadyEvent registers the agent, and stops it on context close. */
@Component
@ConditionalOnProperty(prefix = "agentscope.a2a.server", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GrpcServerLifecycle implements ApplicationListener<WebServerInitializedEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);
    private final AgentScopeA2aServer a2aServer;
    private final String host;
    private final int port;
    private Server grpcServer;

    public GrpcServerLifecycle(
            AgentScopeA2aServer a2aServer,
            @Value("${server.address:0.0.0.0}") String host,
            @Value("${agentscope.a2a.server.grpc.port:8889}") int port) {
        this.a2aServer = a2aServer;
        this.host = host;
        this.port = port;
    }

    @Override
    public synchronized void onApplicationEvent(WebServerInitializedEvent event) {
        if (grpcServer != null || event.getApplicationContext().getParent() != null) {
            return;
        }
        GrpcTransportWrapper wrapper = a2aServer.getTransportWrapper(
                TransportProtocol.GRPC.asString(), GrpcTransportWrapper.class);
        if (wrapper == null) {
            throw new IllegalStateException("A2A gRPC transport was not built");
        }
        grpcServer = NettyServerBuilder.forAddress(new InetSocketAddress(host, port))
                .addService(wrapper.handler())
                .addService(ProtoReflectionServiceV1.newInstance())
                .intercept(new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                            ServerCall<ReqT, RespT> call, Metadata headers,
                            ServerCallHandler<ReqT, RespT> next) {
                        Context context = Context.current()
                                .withValue(GrpcContextKeys.METHOD_NAME_KEY, call.getMethodDescriptor().getFullMethodName())
                                .withValue(GrpcContextKeys.METADATA_KEY, headers);
                        return Contexts.interceptCall(context, call, headers, next);
                    }
                })
                .build();
        try {
            grpcServer.start();
            log.info("A2A gRPC listening on {}:{}", host, port);
        } catch (IOException e) {
            grpcServer.shutdownNow();
            throw new IllegalStateException("Cannot start A2A gRPC on " + host + ":" + port, e);
        }
    }

    @Override
    public synchronized void destroy() throws InterruptedException {
        if (grpcServer != null) {
            grpcServer.shutdown();
            try {
                if (!grpcServer.awaitTermination(5, TimeUnit.SECONDS)) {
                    grpcServer.shutdownNow();
                    grpcServer.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                grpcServer.shutdownNow();
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }
}
