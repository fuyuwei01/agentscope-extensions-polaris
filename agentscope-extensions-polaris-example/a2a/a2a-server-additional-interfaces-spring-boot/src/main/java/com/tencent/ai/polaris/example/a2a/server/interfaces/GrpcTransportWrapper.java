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

import io.a2a.server.ServerCallContext;
import io.a2a.server.auth.UnauthenticatedUser;
import io.a2a.server.requesthandlers.RequestHandler;
import io.a2a.spec.AgentCard;
import io.a2a.spec.TransportProtocol;
import io.a2a.transport.grpc.context.GrpcContextKeys;
import io.a2a.transport.grpc.handler.CallContextFactory;
import io.a2a.transport.grpc.handler.GrpcHandler;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.transport.TransportWrapper;
import io.grpc.Metadata;
import io.grpc.stub.StreamObserver;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/** gRPC service backed by the shared AgentScope request handler and task store. */
public class GrpcTransportWrapper implements TransportWrapper<Void, GrpcHandler> {

    private final GrpcHandler handler;

    public GrpcTransportWrapper(AgentCard card, RequestHandler requestHandler, Executor executor) {
        handler = new GrpcHandler() {
            @Override
            protected RequestHandler getRequestHandler() {
                return requestHandler;
            }

            @Override
            protected AgentCard getAgentCard() {
                return card;
            }

            @Override
            protected Executor getExecutor() {
                return executor;
            }

            @Override
            protected CallContextFactory getCallContextFactory() {
                return new CallContextFactory() {
                    @Override
                    public <V> ServerCallContext create(StreamObserver<V> observer) {
                        String method = GrpcContextKeys.METHOD_NAME_KEY.get();
                        Map<String, Object> state = new HashMap<>();
                        state.put(A2aServerConstants.ContextKeys.IS_STREAM_KEY,
                                method != null && (method.endsWith("/SendStreamingMessage")
                                        || method.endsWith("/TaskSubscription")));
                        state.put("grpc_response_observer", observer);
                        if (method != null) {
                            state.put("grpc_method_name", method);
                        }
                        Metadata headers = GrpcContextKeys.METADATA_KEY.get();
                        if (headers != null) {
                            state.put("grpc_metadata", headers);
                        }
                        return new ServerCallContext(UnauthenticatedUser.INSTANCE, state, Set.of());
                    }
                };
            }
        };
    }

    public GrpcHandler handler() {
        return handler;
    }

    @Override
    public String getTransportType() {
        return TransportProtocol.GRPC.asString();
    }

    @Override
    public GrpcHandler handleRequest(Void ignored, Map<String, String> headers, Map<String, Object> metadata) {
        return handler;
    }
}
