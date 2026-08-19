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

package com.tencent.ai.polaris.spring.boot.config.a2a;

/**
 * Discovery behavior properties ({@code agentscope.polaris.a2a.discovery}).
 */
public class PolarisA2aDiscoveryProperties {

    /** Whether to discover agents from Polaris. Default {@code true}. */
    private boolean enabled = true;

    /**
     * Cache refresh interval in milliseconds. {@code 0} (default) = no proactive refresh, the card is fetched once
     * and cached. {@code >0} = a scheduled pull refreshes the cache.
     */
    private long refreshIntervalMs = 0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getRefreshIntervalMs() {
        return refreshIntervalMs;
    }

    public void setRefreshIntervalMs(long refreshIntervalMs) {
        this.refreshIntervalMs = refreshIntervalMs;
    }
}
