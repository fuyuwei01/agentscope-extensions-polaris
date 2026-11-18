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

package com.tencent.ai.polaris.mcp.constant;

/** Registration fields shared with Spring AI Polaris. */
public final class PolarisMcpMetadataKeys {
    public static final String PROTOCOL_STREAMABLE_HTTP = "mcp-streamable-http";
    public static final String PROTOCOL_SSE = "mcp-sse";
    public static final String ENDPOINT_PATH = "mcp-endpoint-path";
    public static final String SERVER_NAME = "mcp-server-name";
    public static final String PROTOCOL_VERSION = "mcp-protocol-version";

    private PolarisMcpMetadataKeys() {}
}
