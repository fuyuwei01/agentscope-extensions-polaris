package com.tencent.ai.polaris.a2a.discovery;

import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.exception.ErrorCode;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.pojo.Instance;
import com.tencent.polaris.api.rpc.GetAllInstancesRequest;
import com.tencent.polaris.api.rpc.InstancesResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolarisAgentCardResolverTest {

    private static final String NAMESPACE = "default";

    @Mock
    private ConsumerAPI consumerAPI;

    private AgentCard buildCard(String name) {
        return new AgentCard.Builder()
                .name(name)
                .description(name)
                .version("1.0.0")
                .url("http://localhost:8080")
                .capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of())
                .build();
    }

    private Instance buildInstance(String host, int port, boolean healthy, Map<String, String> metadata) {
        Instance inst = mock(Instance.class);
        lenient().when(inst.getHost()).thenReturn(host);
        lenient().when(inst.getPort()).thenReturn(port);
        lenient().when(inst.isHealthy()).thenReturn(healthy);
        lenient().when(inst.getMetadata()).thenReturn(metadata);
        return inst;
    }

    private InstancesResponse responseWith(Instance... instances) {
        InstancesResponse resp = mock(InstancesResponse.class);
        lenient().when(resp.getInstances()).thenReturn(instances);
        return resp;
    }

    @Test
    void getAgentCard_resolvesFromHealthyInstanceMetadata() throws PolarisException {
        String cardJson = com.tencent.ai.polaris.a2a.util.AgentCardCodec.toJson(buildCard("weather-agent"));
        Instance healthy = buildInstance("10.0.0.1", 8080, true, Map.of("a2a.agent.card", cardJson));
        doReturn(responseWith(healthy)).when(consumerAPI).getAllInstances(any(GetAllInstancesRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        AgentCard resolved = resolver.getAgentCard("weather-agent");

        assertEquals("weather-agent", resolved.name());
        assertEquals("1.0.0", resolved.version());
    }

    @Test
    void getAgentCard_skipsUnhealthyInstances() throws PolarisException {
        String cardJson = com.tencent.ai.polaris.a2a.util.AgentCardCodec.toJson(buildCard("echo"));
        Instance unhealthy = buildInstance("10.0.0.2", 8081, false, Map.of("a2a.agent.card", cardJson));
        Instance healthy = buildInstance("10.0.0.1", 8080, true, Map.of("a2a.agent.card", cardJson));
        doReturn(responseWith(unhealthy, healthy)).when(consumerAPI).getAllInstances(any(GetAllInstancesRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        AgentCard resolved = resolver.getAgentCard("echo");

        assertEquals("echo", resolved.name());
    }

    @Test
    void getAgentCard_cachesAfterFirstFetch() throws PolarisException {
        String cardJson = com.tencent.ai.polaris.a2a.util.AgentCardCodec.toJson(buildCard("cached"));
        Instance healthy = buildInstance("10.0.0.1", 8080, true, Map.of("a2a.agent.card", cardJson));
        doReturn(responseWith(healthy)).when(consumerAPI).getAllInstances(any(GetAllInstancesRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        resolver.getAgentCard("cached");
        resolver.getAgentCard("cached");

        verify(consumerAPI, times(1)).getAllInstances(any(GetAllInstancesRequest.class));
    }

    @Test
    void getAgentCard_noInstanceWithCard_throwsNotFound() throws PolarisException {
        Instance healthy = buildInstance("10.0.0.1", 8080, true, Map.of());
        doReturn(responseWith(healthy)).when(consumerAPI).getAllInstances(any(GetAllInstancesRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        assertThrows(AgentCardNotFoundException.class, () -> resolver.getAgentCard("missing"));
    }

    @Test
    void getAgentCard_emptyInstances_throwsNotFound() throws PolarisException {
        doReturn(responseWith()).when(consumerAPI).getAllInstances(any(GetAllInstancesRequest.class));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        assertThrows(AgentCardNotFoundException.class, () -> resolver.getAgentCard("missing"));
    }

    @Test
    void getAgentCard_polarisException_wrapsInNotFound() throws PolarisException {
        when(consumerAPI.getAllInstances(any(GetAllInstancesRequest.class)))
                .thenThrow(new PolarisException(ErrorCode.API_INVALID_ARGUMENT, "server down"));

        PolarisAgentCardResolver resolver = new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
        assertThrows(AgentCardNotFoundException.class, () -> resolver.getAgentCard("down"));
    }
}
