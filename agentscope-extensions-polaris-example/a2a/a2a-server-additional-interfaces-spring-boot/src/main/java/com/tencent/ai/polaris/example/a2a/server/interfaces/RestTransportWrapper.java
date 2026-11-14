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
import io.a2a.spec.TransportProtocol;
import io.a2a.transport.rest.context.RestContextKeys;
import io.a2a.transport.rest.handler.RestHandler;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import io.agentscope.core.a2a.server.transport.TransportWrapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** REST handler backed by the same AgentScope request handler as JSON-RPC and gRPC. */
public class RestTransportWrapper implements TransportWrapper<String, RestHandler.HTTPRestResponse> {

    private final RestHandler handler;

    public RestTransportWrapper(RestHandler handler) {
        this.handler = handler;
    }

    public RestHandler handler() {
        return handler;
    }

    public ServerCallContext context(String method, boolean streaming, Map<String, String> headers) {
        Map<String, Object> state = new HashMap<>();
        state.put(RestContextKeys.HEADERS_KEY, headers);
        state.put(RestContextKeys.METHOD_NAME_KEY, method);
        state.put(A2aServerConstants.ContextKeys.IS_STREAM_KEY, streaming);
        return new ServerCallContext(UnauthenticatedUser.INSTANCE, state, Set.of());
    }

    @Override
    public String getTransportType() {
        return TransportProtocol.HTTP_JSON.asString();
    }

    @Override
    public RestHandler.HTTPRestResponse handleRequest(
            String body, Map<String, String> headers, Map<String, Object> metadata) {
        boolean streaming = Boolean.TRUE.equals(metadata.get(A2aServerConstants.ContextKeys.IS_STREAM_KEY));
        ServerCallContext context = context(streaming ? "message/stream" : "message/send", streaming, headers);
        return streaming ? handler.sendStreamingMessage(body, context) : handler.sendMessage(body, context);
    }
}
