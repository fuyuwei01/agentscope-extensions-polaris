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

import static com.tencent.polaris.ai.factory.SkillAPIFactory.createSkillAPIByContext;

import com.tencent.polaris.ai.api.core.SkillAPI;
import com.tencent.polaris.api.config.Configuration;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.core.ProviderAPI;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.client.api.SDKContext;
import com.tencent.polaris.factory.ConfigAPIFactory;
import com.tencent.polaris.factory.api.DiscoveryAPIFactory;

import com.tencent.polaris.factory.config.ConfigurationImpl;
import com.tencent.polaris.factory.config.ai.AiConfigImpl;
import com.tencent.polaris.factory.config.global.GlobalConfigImpl;
import com.tencent.polaris.factory.config.global.ServerConnectorConfigImpl;
import com.tencent.polaris.factory.config.provider.LosslessConfigImpl;
import com.tencent.polaris.factory.config.skill.SkillConfigImpl;
import com.tencent.polaris.factory.config.skill.SkillConnectorConfigImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Lazily builds and holds a shared {@link SDKContext} plus the {@link ProviderAPI} and
 * {@link ConsumerAPI} derived from it. Intended to be constructed once and passed
 * (via builders) to the A2A registry/resolver — and later skill/MCP features — so they
 * all share one connection and one set of plugins.
 *
 * <p>Not a static singleton: callers build it explicitly (e.g.
 * {@code PolarisContextManager.fromAddress(addr)}) and pass it down. Discovery uses
 * {@link PolarisServerProperties#getAddress()} (8091); SkillAPI uses
 * {@link PolarisServerProperties#getSkillAddress()} (8094). In a Spring Boot starter it
 * would be a {@code @Bean}.
 */
public class PolarisContextManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PolarisContextManager.class);

    private final PolarisServerProperties properties;
    private final SDKContext sdkContext;
    private final ProviderAPI providerAPI;
    private final ConsumerAPI consumerAPI;
    private final SkillAPI skillAPI;

    /**
     * Build a context from the discovery address (e.g. {@code "127.0.0.1:8091"}).
     * SkillAPI uses the same host on port {@link PolarisServerProperties#DEFAULT_SKILL_PORT}.
     */
    public static PolarisContextManager fromAddress(String address) {
        return fromAddress(List.of(address));
    }

    /**
     * Build a context with an explicit SkillAPI address (e.g. {@code "127.0.0.1:8094"}).
     */
    public static PolarisContextManager fromAddress(String address, String skillAddress) {
        PolarisServerProperties props = new PolarisServerProperties(address);
        props.setSkillAddress(skillAddress);
        return new PolarisContextManager(props);
    }

    public static PolarisContextManager fromAddress(List<String> addresses) {
        PolarisServerProperties props = new PolarisServerProperties(String.join(",", addresses));
        return new PolarisContextManager(props);
    }

    public PolarisContextManager(PolarisServerProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        List<String> addresses = properties.serverAddressList();
        log.info("Initializing Polaris SDK context: namespace={}, addresses={}",
                properties.getNamespace(), addresses);
        log.debug("Polaris server token configured: {}", hasToken(properties.getToken()));

        ConfigurationImpl config = buildConfiguration(properties);

        SDKContext initializedContext = null;
        ProviderAPI initializedProviderAPI = null;
        ConsumerAPI initializedConsumerAPI = null;
        SkillAPI initializedSkillAPI = null;
        try {
            log.debug("Initializing Polaris SDKContext");
            initializedContext = initContext(config);
            log.debug("Creating Polaris ProviderAPI");
            initializedProviderAPI = createProviderAPI(initializedContext);
            log.debug("Creating Polaris ConsumerAPI");
            initializedConsumerAPI = createConsumerAPI(initializedContext);
            log.debug("Creating Polaris SkillAPI");
            initializedSkillAPI = createSkillAPI(initializedContext);
        } catch (RuntimeException e) {
            log.debug("Polaris SDK context initialization failed, closing partial resources: {}",
                    e.getMessage());
            throw closeResourcesAndCollect(
                    initializedProviderAPI, initializedConsumerAPI, initializedContext, e);
        }
        this.sdkContext = initializedContext;
        this.providerAPI = initializedProviderAPI;
        this.consumerAPI = initializedConsumerAPI;
        this.skillAPI = initializedSkillAPI;
        log.info("Polaris SDK context ready: namespace={}, addresses={}",
                properties.getNamespace(), addresses);
    }

    static ConfigurationImpl buildConfiguration(PolarisServerProperties properties) {
        Objects.requireNonNull(properties, "properties");
        List<String> addresses = properties.serverAddressList();
        if (addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("polaris server address must be configured");
        }
        ConfigurationImpl config = (ConfigurationImpl) ConfigAPIFactory.createConfigurationByAddress(addresses);
        GlobalConfigImpl globalConfig = (GlobalConfigImpl) config.getGlobal();
        String token = properties.getToken();
        if (token != null && !token.isBlank()) {
            ((ServerConnectorConfigImpl) globalConfig.getServerConnector()).setToken(token);
            log.debug("Applied Polaris server connector token");
        }
        globalConfig.getStatReporter().setEnable(false);
        applySkillConnectorAddresses(config, properties.skillAddressList());
        LosslessConfigImpl losslessConfig = (LosslessConfigImpl) config.getProvider().getLossless();
        losslessConfig.setEnable(false);
        log.debug("Polaris SDK configuration built: addresses={}, skillAddresses={}, tokenConfigured={}, statReporter=false, lossless=false",
                addresses, properties.skillAddressList(), hasToken(token));
        return config;
    }

    /**
     * Write SkillAPI addresses into {@code ai.skill.serverConnector}.
     */
    static void applySkillConnectorAddresses(ConfigurationImpl config, List<String> skillAddresses) {
        AiConfigImpl aiConfig = config.getAi();
        if (aiConfig == null) {
            aiConfig = new AiConfigImpl();
            config.setAi(aiConfig);
        }
        SkillConfigImpl skillConfig = aiConfig.getSkill();
        if (skillConfig == null) {
            skillConfig = new SkillConfigImpl();
            aiConfig.setSkill(skillConfig);
        }
        SkillConnectorConfigImpl skillConnector = skillConfig.getServerConnector();
        if (skillConnector == null) {
            skillConnector = new SkillConnectorConfigImpl();
            skillConfig.setServerConnector(skillConnector);
        }
        skillConnector.setAddresses(skillAddresses);
    }

    private static boolean hasToken(String token) {
        return token != null && !token.isBlank();
    }

    private static SDKContext initContext(Configuration config) {
        try {
            return SDKContext.initContextByConfig(config);
        } catch (PolarisException e) {
            throw new IllegalStateException("Failed to init polaris SDKContext: " + e.getMessage(), e);
        }
    }

    private static ProviderAPI createProviderAPI(SDKContext context) {
        try {
            return DiscoveryAPIFactory.createProviderAPIByContext(context);
        } catch (PolarisException e) {
            throw new IllegalStateException("Failed to create polaris ProviderAPI: " + e.getMessage(), e);
        }
    }

    private static ConsumerAPI createConsumerAPI(SDKContext context) {
        try {
            return DiscoveryAPIFactory.createConsumerAPIByContext(context);
        } catch (PolarisException e) {
            throw new IllegalStateException("Failed to create polaris ConsumerAPI: " + e.getMessage(), e);
        }
    }

    private static SkillAPI createSkillAPI(SDKContext context) {
        try {
            return createSkillAPIByContext(context);
        } catch (PolarisException e) {
            throw new IllegalStateException("Failed to create polaris SkillAPI: " + e.getMessage(), e);
        }
    }

    public PolarisServerProperties getProperties() {
        return properties;
    }

    public SDKContext getSdkContext() {
        return sdkContext;
    }

    public ProviderAPI providerAPI() {
        return providerAPI;
    }

    public ConsumerAPI consumerAPI() {
        return consumerAPI;
    }

    public SkillAPI skillAPI() {
        return skillAPI;
    }

    public String getNamespace() {
        return properties.getNamespace();
    }

    @Override
    public void close() {
        closeResources(providerAPI, consumerAPI, sdkContext);
    }

    static void closeResources(ProviderAPI providerAPI, ConsumerAPI consumerAPI, SDKContext sdkContext) {
        RuntimeException failure = closeResourcesAndCollect(providerAPI, consumerAPI, sdkContext, null);
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException closeResourcesAndCollect(
            ProviderAPI providerAPI,
            ConsumerAPI consumerAPI,
            SDKContext sdkContext,
            RuntimeException failure) {
        if (providerAPI != null) {
            try {
                providerAPI.close();
            } catch (RuntimeException e) {
                failure = addFailure(failure, e);
            }
        }
        if (consumerAPI != null) {
            try {
                consumerAPI.close();
            } catch (RuntimeException e) {
                failure = addFailure(failure, e);
            }
        }
        if (sdkContext != null) {
            try {
                sdkContext.close();
            } catch (RuntimeException e) {
                failure = addFailure(failure, e);
            }
        }
        return failure;
    }

    private static RuntimeException addFailure(RuntimeException failure, RuntimeException next) {
        if (failure == null) {
            return next;
        }
        if (failure != next) {
            failure.addSuppressed(next);
        }
        return failure;
    }
}
