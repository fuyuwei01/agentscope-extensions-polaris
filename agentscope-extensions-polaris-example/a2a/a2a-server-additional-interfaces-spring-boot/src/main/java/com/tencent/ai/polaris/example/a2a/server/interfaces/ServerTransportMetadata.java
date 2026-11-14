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

import io.a2a.server.TransportMetadata;
import io.a2a.spec.TransportProtocol;

/** Makes the demo's implemented transports visible to SDK AgentCard validation. */
public final class ServerTransportMetadata {

    private ServerTransportMetadata() {
    }

    public static class Rest implements TransportMetadata {
        @Override
        public String getTransportProtocol() {
            return TransportProtocol.HTTP_JSON.asString();
        }
    }

    public static class Grpc implements TransportMetadata {
        @Override
        public String getTransportProtocol() {
            return TransportProtocol.GRPC.asString();
        }
    }
}
