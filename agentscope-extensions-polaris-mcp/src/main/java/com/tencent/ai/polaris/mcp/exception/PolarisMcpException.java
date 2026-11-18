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

package com.tencent.ai.polaris.mcp.exception;

/** Failure categories distinguish discovery, endpoint validation and MCP connection errors. */
public class PolarisMcpException extends IllegalStateException {
    public enum Reason {
        DISCOVERY,
        NO_INSTANCE,
        ENDPOINT,
        INITIALIZATION,
        INCOMPATIBLE_TOOLS,
        CLOSED
    }

    private final Reason reason;

    public PolarisMcpException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PolarisMcpException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
