package com.tencent.ai.polaris.core;

import java.util.Arrays;
import java.util.List;

/**
 * Connection properties for the Polaris server.
 *
 * <p>Maps to {@code Configuration} via {@code ConfigAPIFactory.createConfigurationByAddress}.
 */
public class PolarisServerProperties {

    private static final String DEFAULT_ADDRESS = "127.0.0.1:8091";
    private static final String DEFAULT_NAMESPACE = "default";

    private List<String> serverAddresses;
    private String namespace = DEFAULT_NAMESPACE;
    private String token;

    public PolarisServerProperties() {
        this.serverAddresses = Arrays.asList(DEFAULT_ADDRESS);
    }

    public PolarisServerProperties(List<String> serverAddresses) {
        this.serverAddresses = serverAddresses;
    }

    public List<String> getServerAddresses() {
        return serverAddresses;
    }

    public void setServerAddresses(List<String> serverAddresses) {
        this.serverAddresses = serverAddresses;
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
}
