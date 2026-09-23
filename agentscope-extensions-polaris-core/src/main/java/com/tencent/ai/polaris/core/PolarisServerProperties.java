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
 * <p>Maps to {@code Configuration} via {@code ConfigAPIFactory.createConfigurationByAddress}.
 * {@code address} is discovery/registry (default port 8091). {@code skillAddress} is SkillAPI
 * (default port {@value #DEFAULT_SKILL_PORT}); when omitted, discovery hosts are reused with
 * that port. Each field accepts a single {@code host:port} or a comma-separated list.
 *
 * <p>Fields are not initialized with defaults so that Spring Boot {@code @ConfigurationProperties} binding writes
 * them cleanly; callers needing a default use {@link #DEFAULT_ADDRESS}.
 */
public class PolarisServerProperties {

    public static final String DEFAULT_ADDRESS = "127.0.0.1:8091";
    public static final int DEFAULT_SKILL_PORT = 8094;
    public static final String DEFAULT_SKILL_ADDRESS = "127.0.0.1:" + DEFAULT_SKILL_PORT;
    public static final String DEFAULT_NAMESPACE = "default";

    /** When {@code false}, Spring Boot auto-config skips creating {@code PolarisContextManager}. */
    private boolean enabled = true;
    private String address;
    private String skillAddress;
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

    /**
     * SkillAPI addresses (comma-separated {@code host:port}). Distinct from discovery
     * {@link #address} (default port {@value #DEFAULT_SKILL_PORT}).
     *
     * @return configured skill addresses, or {@code null} to derive from {@link #address}
     */
    public String getSkillAddress() {
        return skillAddress;
    }

    public void setSkillAddress(String skillAddress) {
        this.skillAddress = skillAddress;
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

    /**
     * Addresses for {@code SkillAPI}. Uses {@link #skillAddress} when set; otherwise
     * the discovery hosts with port {@value #DEFAULT_SKILL_PORT}.
     *
     * @return a non-empty address list
     */
    public List<String> skillAddressList() {
        if (skillAddress != null && !skillAddress.isBlank()) {
            return Arrays.stream(skillAddress.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return serverAddressList().stream().map(PolarisServerProperties::withSkillPort).toList();
    }

    static String withSkillPort(String discoveryAddress) {
        return hostOf(discoveryAddress) + ":" + DEFAULT_SKILL_PORT;
    }

    private static String hostOf(String address) {
        if (address.startsWith("[")) {
            int end = address.indexOf(']');
            if (end > 0) {
                return address.substring(0, end + 1);
            }
        }
        int colon = address.lastIndexOf(':');
        if (colon <= 0) {
            return address;
        }
        String port = address.substring(colon + 1);
        if (!port.isEmpty() && port.chars().allMatch(Character::isDigit)) {
            return address.substring(0, colon);
        }
        return address;
    }
}
