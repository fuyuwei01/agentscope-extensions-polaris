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

package com.tencent.ai.polaris.spring.boot.config.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Consumer targets and policies; remote addresses and paths come exclusively from Polaris. */
@ConfigurationProperties("agentscope.polaris.mcp")
public class AgentScopePolarisMcpProperties {
    private boolean enabled = true;
    private Discovery discovery = new Discovery();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public Discovery getDiscovery() {
        return discovery;
    }

    public void setDiscovery(Discovery value) {
        discovery = value;
    }

    public static class Discovery {
        private boolean enabled = true;
        private boolean attachToToolkit;
        private String toolkitBeanName;
        private Duration idleTimeout = Duration.ofMinutes(10);
        private List<Client> clients = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean value) {
            enabled = value;
        }

        public boolean isAttachToToolkit() {
            return attachToToolkit;
        }

        public void setAttachToToolkit(boolean value) {
            attachToToolkit = value;
        }

        public String getToolkitBeanName() {
            return toolkitBeanName;
        }

        public void setToolkitBeanName(String value) {
            toolkitBeanName = value;
        }

        public Duration getIdleTimeout() {
            return idleTimeout;
        }

        public void setIdleTimeout(Duration value) {
            idleTimeout = value;
        }

        public List<Client> getClients() {
            return clients;
        }

        public void setClients(List<Client> value) {
            clients = value;
        }
    }

    public static class Client {
        private String serviceName;
        private String namespace;
        private String name;
        private String version;
        private boolean asyncClient = true;
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Duration initializationTimeout = Duration.ofSeconds(10);
        private String defaultScheme = "http";
        private Map<String, String> headers = new LinkedHashMap<>();

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String value) {
            serviceName = value;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String value) {
            namespace = value;
        }

        public String getName() {
            return name;
        }

        public void setName(String value) {
            name = value;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String value) {
            version = value;
        }

        public boolean isAsyncClient() {
            return asyncClient;
        }

        public void setAsyncClient(boolean value) {
            asyncClient = value;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration value) {
            requestTimeout = value;
        }

        public Duration getInitializationTimeout() {
            return initializationTimeout;
        }

        public void setInitializationTimeout(Duration value) {
            initializationTimeout = value;
        }

        public String getDefaultScheme() {
            return defaultScheme;
        }

        public void setDefaultScheme(String value) {
            defaultScheme = value;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> value) {
            headers = value;
        }
    }
}
