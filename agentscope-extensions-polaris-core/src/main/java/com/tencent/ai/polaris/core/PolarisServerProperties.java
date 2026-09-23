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

package com.tencent.ai.polaris.core;

import java.util.Arrays;
import java.util.List;

/**
 * Connection properties for the Polaris server.
 *
 * <p>Maps to {@code Configuration} via {@code ConfigAPIFactory.createConfigurationByAddress}. {@code serverAddress}
 * accepts a single address ({@code host:port}) or a comma-separated list; it is split into a {@code List<String>}
 * when handed to the SDK.
 *
 * <p>Fields are not initialized with defaults so that Spring Boot {@code @ConfigurationProperties} binding writes
 * them cleanly; callers needing a default use {@link #DEFAULT_ADDRESS}.
 */
public class PolarisServerProperties {

    public static final String DEFAULT_ADDRESS = "127.0.0.1:8091";
    public static final String DEFAULT_NAMESPACE = "default";

    /** When {@code false}, Spring Boot auto-config skips creating {@code PolarisContextManager}. */
    private boolean enabled = true;
    private String address;
    private String namespace = DEFAULT_NAMESPACE;
    private String token;

    public PolarisServerProperties() {
    }

    public PolarisServerProperties(String address) {
        this.address = address;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    /**
     * Split {@link #address} into a list for {@code ConfigAPIFactory.createConfigurationByAddress}; never null.
     */
    public List<String> serverAddressList() {
        if (address == null || address.isBlank()) {
            return List.of(DEFAULT_ADDRESS);
        }
        return Arrays.stream(address.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
