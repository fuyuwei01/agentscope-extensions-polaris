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

import io.a2a.server.requesthandlers.RequestHandler;
import io.a2a.spec.AgentCard;
import io.a2a.spec.TransportProtocol;
import io.a2a.transport.rest.handler.RestHandler;
import io.agentscope.core.a2a.server.transport.TransportWrapperBuilder;

import java.util.concurrent.Executor;

/** AgentScope SPI adapter for the A2A SDK REST transport. */
public class RestTransportWrapperBuilder implements TransportWrapperBuilder<RestTransportWrapper> {

    @Override
    public String getTransportType() {
        return TransportProtocol.HTTP_JSON.asString();
    }

    @Override
    public RestTransportWrapper build(
            AgentCard card, RequestHandler handler, Executor executor, AgentCard extendedCard) {
        return new RestTransportWrapper(new RestHandler(card, handler, executor));
    }
}
