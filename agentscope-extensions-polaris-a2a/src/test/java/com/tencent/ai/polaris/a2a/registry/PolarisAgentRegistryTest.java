package com.tencent.ai.polaris.a2a.registry;

import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.transport.TransportProperties;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
    void register_mapsCardAndTransportToInstanceRequest() throws PolarisException {
        when(providerAPI.registerInstance(any(InstanceRegisterRequest.class)))
                .thenReturn(new InstanceRegisterResponse("inst-1", false));

        PolarisAgentRegistry registry = new PolarisAgentRegistry(providerAPI, NAMESPACE, 5);
        AgentCard card = buildCard("weather-agent", "1.0.0");
        List<TransportProperties> transports = List.of(
                buildTransport("JSONRPC", "10.0.0.1", 8080, false),
                buildTransport("HTTP+JSON", "10.0.0.1", 8443, true));

        registry.register(card, transports);

        verify(providerAPI, times(2)).registerInstance(registerCaptor.capture());
        List<InstanceRegisterRequest> requests = registerCaptor.getAllValues();

        InstanceRegisterRequest first = requests.get(0);
        assertEquals(NAMESPACE, first.getNamespace());
        assertEquals("weather-agent", first.getService());
        assertEquals("10.0.0.1", first.getHost());
        assertEquals(8080, first.getPort());
        assertEquals("1.0.0", first.getVersion());
        assertEquals("http", first.getProtocol());
        assertTrue(first.isAutoHeartbeat());
        assertEquals(5, first.getTtl());
        assertNotNull(first.getMetadata().get("a2a.agent.card"));
        assertEquals("JSONRPC", first.getMetadata().get("a2a.transport"));
        assertEquals("/", first.getMetadata().get("a2a.path"));

        InstanceRegisterRequest second = requests.get(1);
        assertEquals(8443, second.getPort());
        assertEquals("https", second.getProtocol());
        assertEquals("HTTP+JSON", second.getMetadata().get("a2a.transport"));

        assertEquals(2, registry.getRegistered().size());
        assertEquals("inst-1", registry.getRegistered().get(0).instanceId());
    }

    @Test
    void register_emptyTransports_throws() {
        PolarisAgentRegistry registry = new PolarisAgentRegistry(providerAPI, NAMESPACE, 5);
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(buildCard("a", "1"), List.of()));
        verify(providerAPI, never()).registerInstance(any());
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
        assertEquals(9000, req.getPort());
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
    }
}
