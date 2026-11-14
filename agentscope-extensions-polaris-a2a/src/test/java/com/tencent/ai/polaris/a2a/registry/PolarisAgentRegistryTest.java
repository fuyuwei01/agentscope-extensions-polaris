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

package com.tencent.ai.polaris.a2a.registry;

import com.tencent.ai.polaris.a2a.util.AgentCardCodec;
import com.tencent.ai.polaris.core.PolarisContextManager;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import com.tencent.polaris.api.exception.ErrorCode;
import com.tencent.polaris.api.core.ProviderAPI;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.rpc.InstanceDeregisterRequest;
import com.tencent.polaris.api.rpc.InstanceRegisterRequest;
import com.tencent.polaris.api.rpc.InstanceRegisterResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolarisAgentRegistryTest {

    private static final String NAMESPACE = "default";

    @Mock
    private ProviderAPI providerAPI;

    @Captor
    private ArgumentCaptor<InstanceRegisterRequest> registerCaptor;

    @Captor
    private ArgumentCaptor<InstanceDeregisterRequest> deregisterCaptor;

    private AgentCard buildCard(String name, String version) {
        return new AgentCard.Builder()
                .name(name)
                .description(name)
                .version(version)
                .url("http://localhost:8080")
                .capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of())
                .build();
    }

    private TransportProperties buildTransport(String type, String host, int port, boolean tls) {
        return TransportProperties.builder(type)
                .host(host)
                .port(port)
                .path("/")
                .supportTls(tls)
                .build();
    }

    @Test
    void registryName_returnsPolaris() {
        PolarisAgentRegistry registry = new PolarisAgentRegistry(providerAPI, NAMESPACE, 5);
        assertEquals("Polaris", registry.registryName());
    }

    @Test
    void register_putsAgentCardNameAndA2aVersionInMetadataAndSetsServiceToken() throws PolarisException {
        when(providerAPI.registerInstance(any(InstanceRegisterRequest.class)))
                .thenReturn(new InstanceRegisterResponse("inst-1", false));

        PolarisAgentRegistry registry = new PolarisAgentRegistry(providerAPI, NAMESPACE, 5, "svc-token");
        AgentCard card = buildCard("weather-agent", "1.0.0");
        List<TransportProperties> transports = List.of(
                buildTransport("JSONRPC", "10.0.0.1", 8080, false),
                buildTransport("HTTP+JSON", "10.0.0.2", 8443, true));

        registry.register(card, transports);

        verify(providerAPI, times(2)).registerInstance(registerCaptor.capture());
        List<InstanceRegisterRequest> requests = registerCaptor.getAllValues();

        InstanceRegisterRequest first = requests.get(0);
        assertEquals(NAMESPACE, first.getNamespace());
        assertEquals("weather-agent", first.getService());
        assertEquals("10.0.0.1", first.getHost());
        assertEquals(1, first.getPort());
        assertEquals("1.0.0", first.getVersion());
        assertEquals("", first.getProtocol());
        assertTrue(first.isAutoHeartbeat());
        assertEquals(5, first.getTtl());
        assertEquals("svc-token", first.getToken());
        assertEquals(Map.of(
                "ai-agent-card", AgentCardCodec.toJson(card),
                "ai-agent-name", "weather-agent",
                "ai-a2a-version", "0.3.0"), first.getMetadata());

        InstanceRegisterRequest second = requests.get(1);
        assertEquals("10.0.0.2", second.getHost());
        assertEquals(1, second.getPort());
        assertEquals("", second.getProtocol());
        assertEquals(first.getMetadata(), second.getMetadata());

        assertEquals(2, registry.getRegistered().size());
        assertEquals("inst-1", registry.getRegistered().get(0).instanceId());
    }

    @Test
    void register_usesCardProtocolVersionSeparatelyFromAgentVersion() throws PolarisException {
        when(providerAPI.registerInstance(any(InstanceRegisterRequest.class)))
                .thenReturn(new InstanceRegisterResponse("inst-1", false));
        PolarisAgentRegistry registry = new PolarisAgentRegistry(providerAPI, NAMESPACE, 5);
        AgentCard card = new AgentCard.Builder(buildCard("echo", "2.0.0"))
                .protocolVersion("0.2.9")
                .build();

        registry.register(card, List.of(buildTransport("JSONRPC", "h", 8080, false)));

        verify(providerAPI).registerInstance(registerCaptor.capture());
        InstanceRegisterRequest request = registerCaptor.getValue();
        assertEquals("0.2.9", request.getMetadata().get("ai-a2a-version"));
        assertEquals("2.0.0", request.getVersion());
        assertEquals("0.2.9",
                AgentCardCodec.fromJson(request.getMetadata().get("ai-agent-card")).protocolVersion());
    }

    @Test
    void register_transportsOnSameHost_registersAndDeregistersOnce() throws PolarisException {
        when(providerAPI.registerInstance(any(InstanceRegisterRequest.class)))
                .thenReturn(new InstanceRegisterResponse("inst-1", false));
        PolarisAgentRegistry registry = new PolarisAgentRegistry(providerAPI, NAMESPACE, 5);

        registry.register(buildCard("echo", "1.0.0"), List.of(
                buildTransport("JSONRPC", "h", 8080, false),
                buildTransport("HTTP+JSON", "h", 8443, true)));

        verify(providerAPI).registerInstance(registerCaptor.capture());
        assertEquals(1, registerCaptor.getValue().getPort());
        assertEquals(1, registry.getRegistered().size());

        registry.close();

        verify(providerAPI).deRegister(deregisterCaptor.capture());
        assertEquals(1, deregisterCaptor.getValue().getPort());
        assertTrue(registry.getRegistered().isEmpty());
    }

    @Test
    void register_builderPortOverridesTransportPortAndIsUsedForDeregistration() throws PolarisException {
        when(providerAPI.registerInstance(any(InstanceRegisterRequest.class)))
                .thenReturn(new InstanceRegisterResponse("inst-custom", false));
        PolarisContextManager context = mock(PolarisContextManager.class);
        when(context.providerAPI()).thenReturn(providerAPI);
        when(context.getNamespace()).thenReturn(NAMESPACE);
        PolarisAgentRegistry registry = PolarisAgentRegistry.builder(context).port(18080).build();
        AgentCard card = buildCard("echo", "1.0.0");

        registry.register(card, List.of(buildTransport("JSONRPC", "h", 8080, false)));

        verify(providerAPI).registerInstance(registerCaptor.capture());
        InstanceRegisterRequest request = registerCaptor.getValue();
        assertEquals(18080, request.getPort());
        assertEquals("", request.getProtocol());
        assertEquals(card.url(), AgentCardCodec.fromJson(request.getMetadata().get("ai-agent-card")).url());
        assertEquals(18080, registry.getRegistered().get(0).port());

        registry.close();

        verify(providerAPI).deRegister(deregisterCaptor.capture());
        assertEquals(18080, deregisterCaptor.getValue().getPort());
        assertEquals("inst-custom", deregisterCaptor.getValue().getInstanceID());
    }

    @Test
    void register_missingTransportPort_usesDefaultRegistrationPort() throws PolarisException {
        when(providerAPI.registerInstance(any(InstanceRegisterRequest.class)))
                .thenReturn(new InstanceRegisterResponse("inst-1", false));
        PolarisAgentRegistry registry = new PolarisAgentRegistry(providerAPI, NAMESPACE, 5);
        TransportProperties transport = TransportProperties.builder("JSONRPC").host("h").build();

        registry.register(buildCard("echo", "1.0.0"), List.of(transport));

        verify(providerAPI).registerInstance(registerCaptor.capture());
        assertEquals(1, registerCaptor.getValue().getPort());
    }

    @Test
    void constructor_invalidRegistrationPort_throwsBeforeRegistration() throws PolarisException {
        for (int invalidPort : new int[]{-1, 65_536}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PolarisAgentRegistry(providerAPI, NAMESPACE, 5, null, invalidPort));
        }
        verify(providerAPI, never()).registerInstance(any());
    }

    @Test
    void register_oversizedAgentCardMetadata_throwsAndDoesNotRegister() throws PolarisException {
        AgentCard huge = new AgentCard.Builder()
                .name("huge-agent")
                .description("x".repeat(70_000))
                .version("1.0.0")
                .url("http://localhost:8080")
                .capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of())
                .build();
        PolarisAgentRegistry registry = new PolarisAgentRegistry(providerAPI, NAMESPACE, 5);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registry.register(huge, List.of(buildTransport("JSONRPC", "h", 9000, false))));
        assertTrue(ex.getMessage().contains("64") || ex.getMessage().contains("65535"));
        verify(providerAPI, never()).registerInstance(any());
        assertTrue(registry.getRegistered().isEmpty());
    }

    @Test
    void register_emptyTransports_throws() {
        PolarisAgentRegistry registry = new PolarisAgentRegistry(providerAPI, NAMESPACE, 5);
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(buildCard("a", "1"), List.of()));
        verify(providerAPI, never()).registerInstance(any());
    }

    @Test
    void register_secondTransportFailure_rollsBackFirstTransport() throws PolarisException {
        when(providerAPI.registerInstance(any(InstanceRegisterRequest.class)))
                .thenReturn(new InstanceRegisterResponse("inst-first", false))
                .thenThrow(new PolarisException(ErrorCode.API_INVALID_ARGUMENT, "second failed"));
        PolarisAgentRegistry registry = new PolarisAgentRegistry(providerAPI, NAMESPACE, 5, "tok", 18080);

        assertThrows(IllegalStateException.class, () -> registry.register(
                buildCard("echo", "2.0"),
                List.of(
                        buildTransport("JSONRPC", "h", 9000, false),
                        buildTransport("HTTP+JSON", "other", 9001, false))));

        verify(providerAPI).deRegister(deregisterCaptor.capture());
        assertEquals("inst-first", deregisterCaptor.getValue().getInstanceID());
        assertEquals("tok", deregisterCaptor.getValue().getToken());
        assertEquals(18080, deregisterCaptor.getValue().getPort());
        assertTrue(registry.getRegistered().isEmpty());
        registry.close();
    }

    @Test
    void register_runtimeFailure_rollsBackFirstTransport() throws PolarisException {
        when(providerAPI.registerInstance(any(InstanceRegisterRequest.class)))
                .thenReturn(new InstanceRegisterResponse("inst-first", false))
                .thenThrow(new RuntimeException("second failed"));
        PolarisAgentRegistry registry = new PolarisAgentRegistry(providerAPI, NAMESPACE, 5);

        assertThrows(RuntimeException.class, () -> registry.register(
                buildCard("echo", "2.0"),
                List.of(
                        buildTransport("JSONRPC", "h", 9000, false),
                        buildTransport("HTTP+JSON", "other", 9001, false))));

        verify(providerAPI).deRegister(deregisterCaptor.capture());
        assertEquals("inst-first", deregisterCaptor.getValue().getInstanceID());
        assertTrue(registry.getRegistered().isEmpty());
        registry.close();
    }

    @Test
    void register_failedRollback_remainsTrackedForCloseRetry() throws PolarisException {
        when(providerAPI.registerInstance(any(InstanceRegisterRequest.class)))
                .thenReturn(new InstanceRegisterResponse("inst-first", false))
                .thenThrow(new RuntimeException("second failed"));
        org.mockito.Mockito.doThrow(new RuntimeException("rollback failed"))
                .doNothing()
                .when(providerAPI).deRegister(any(InstanceDeregisterRequest.class));
        PolarisAgentRegistry registry = new PolarisAgentRegistry(providerAPI, NAMESPACE, 5);

        assertThrows(RuntimeException.class, () -> registry.register(
                buildCard("echo", "2.0"),
                List.of(
                        buildTransport("JSONRPC", "h", 9000, false),
                        buildTransport("HTTP+JSON", "other", 9001, false))));

        assertEquals(1, registry.getRegistered().size());
        assertEquals("inst-first", registry.getRegistered().get(0).instanceId());

        registry.close();

        verify(providerAPI, times(2)).deRegister(any(InstanceDeregisterRequest.class));
        assertTrue(registry.getRegistered().isEmpty());
    }

    @Test
    void close_deregistersAllRegisteredInstances() throws PolarisException {
        when(providerAPI.registerInstance(any(InstanceRegisterRequest.class)))
                .thenReturn(new InstanceRegisterResponse("inst-x", false));

        PolarisAgentRegistry registry = new PolarisAgentRegistry(providerAPI, NAMESPACE, 5);
        registry.register(buildCard("echo", "2.0"),
                List.of(buildTransport("JSONRPC", "h", 9000, false)));

        registry.close();

        verify(providerAPI, times(1)).deRegister(deregisterCaptor.capture());
        InstanceDeregisterRequest req = deregisterCaptor.getValue();
        assertEquals(NAMESPACE, req.getNamespace());
        assertEquals("echo", req.getService());
        assertEquals("h", req.getHost());
        assertEquals(1, req.getPort());
        assertEquals("inst-x", req.getInstanceID());
    }

    @Test
    void close_swallowsDeregisterFailure() throws PolarisException {
        when(providerAPI.registerInstance(any(InstanceRegisterRequest.class)))
                .thenReturn(new InstanceRegisterResponse("inst-y", false));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(providerAPI).deRegister(any(InstanceDeregisterRequest.class));

        PolarisAgentRegistry registry = new PolarisAgentRegistry(providerAPI, NAMESPACE, 5);
        registry.register(buildCard("echo", "2.0"),
                List.of(buildTransport("JSONRPC", "h", 9000, false)));

        registry.close();
        verify(providerAPI, times(1)).deRegister(any(InstanceDeregisterRequest.class));
        assertEquals(1, registry.getRegistered().size());
    }
}
