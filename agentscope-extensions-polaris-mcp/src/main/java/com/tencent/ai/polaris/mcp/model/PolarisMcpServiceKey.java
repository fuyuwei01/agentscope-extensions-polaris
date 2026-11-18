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

/** A discovery subscription, optionally restricted to an application version. */
public record PolarisMcpServiceKey(String namespace, String service, String version) {
    public PolarisMcpServiceKey {
        if (namespace == null || namespace.isBlank() || service == null || service.isBlank()) {
            throw new IllegalArgumentException("MCP namespace and service must not be blank");
        }
        version = version == null || version.isBlank() ? null : version;
    }

    public PolarisMcpServiceKey(String namespace, String service) {
        this(namespace, service, null);
    }
}
