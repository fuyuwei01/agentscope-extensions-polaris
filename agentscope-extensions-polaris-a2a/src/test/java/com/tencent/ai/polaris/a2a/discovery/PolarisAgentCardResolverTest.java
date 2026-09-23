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

package com.tencent.ai.polaris.a2a.discovery;

import com.tencent.ai.polaris.a2a.exception.AgentCardNotFoundException;
import com.tencent.ai.polaris.a2a.util.AgentCardCodec;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import com.tencent.polaris.api.core.ConsumerAPI;
import com.tencent.polaris.api.exception.ErrorCode;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.pojo.Instance;
import com.tencent.polaris.api.rpc.GetHealthyInstancesRequest;
import com.tencent.polaris.api.rpc.InstancesResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        return buildCard(name, "1.0.0", "http://10.0.0.1:8080/");
    }

    private AgentCard buildCard(String name, String version, String url) {
        return new AgentCard.Builder()
                .name(name)
                .description(name)
                .version(version)
                .url(url)
                .capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of())
                .build();
    }

    private Instance buildInstance(String host, int port, Map<String, String> metadata) {
        Instance inst = mock(Instance.class);
        lenient().when(inst.getHost()).thenReturn(host);
        lenient().when(inst.getPort()).thenReturn(port);
        lenient().when(inst.getMetadata()).thenReturn(metadata);
        return inst;
    }

    private InstancesResponse responseWith(Instance... instances) {
        InstancesResponse resp = mock(InstancesResponse.class);
        lenient().when(resp.getInstances()).thenReturn(instances);
        return resp;
    }

    private PolarisAgentCardResolver resolver() {
        return new PolarisAgentCardResolver(consumerAPI, NAMESPACE);
    }

    @Test
    void getAgentCard_resolvesFromHealthyInstanceMetadata() throws PolarisException {
        String cardJson = AgentCardCodec.toJson(buildCard("weather-agent"));
        Instance healthy = buildInstance("10.0.0.1", 8080, Map.of("a2a.agent.card", cardJson));
        doReturn(responseWith(healthy)).when(consumerAPI).getHealthyInstances(any(GetHealthyInstancesRequest.class));

        AgentCard resolved = resolver().getAgentCard("weather-agent");

        assertEquals("weather-agent", resolved.name());
        assertEquals("1.0.0", resolved.version());
        assertEquals("http://10.0.0.1:8080/", resolved.url());
    }

    @Test
    void getAgentCard_sticksToFirstInstanceEvenIfAnotherAppearsFirst() throws PolarisException {
        String firstJson = AgentCardCodec.toJson(buildCard("sticky", "1.0.0", "http://10.0.0.1:8080/"));
        String secondJson = AgentCardCodec.toJson(buildCard("sticky", "2.0.0", "http://10.0.0.2:8080/"));
        Instance first = buildInstance("10.0.0.1", 8080, Map.of("a2a.agent.card", firstJson));
        Instance second = buildInstance("10.0.0.2", 8080, Map.of("a2a.agent.card", secondJson));
        InstancesResponse firstResponse = responseWith(first, second);
        InstancesResponse reversedResponse = responseWith(second, first);
        when(consumerAPI.getHealthyInstances(any(GetHealthyInstancesRequest.class)))
                .thenReturn(firstResponse, reversedResponse);

        PolarisAgentCardResolver resolver = resolver();
        AgentCard firstCard = resolver.getAgentCard("sticky");
        AgentCard secondCard = resolver.getAgentCard("sticky");

        assertEquals("http://10.0.0.1:8080/", firstCard.url());
        assertEquals("1.0.0", firstCard.version());
        assertEquals("http://10.0.0.1:8080/", secondCard.url());
        assertEquals("1.0.0", secondCard.version());
        verify(consumerAPI, times(2)).getHealthyInstances(any(GetHealthyInstancesRequest.class));
    }

    @Test
    void getAgentCard_refreshesCardWhenStickyInstanceMetadataChanges() throws PolarisException {
        String v1 = AgentCardCodec.toJson(buildCard("sticky", "1.0.0", "http://10.0.0.1:8080/"));
        String v2 = AgentCardCodec.toJson(buildCard("sticky", "1.1.0", "http://10.0.0.1:8080/"));
        String other = AgentCardCodec.toJson(buildCard("sticky", "9.0.0", "http://10.0.0.2:8080/"));
        Instance firstV1 = buildInstance("10.0.0.1", 8080, Map.of("a2a.agent.card", v1));
        Instance firstV2 = buildInstance("10.0.0.1", 8080, Map.of("a2a.agent.card", v2));
        Instance second = buildInstance("10.0.0.2", 8080, Map.of("a2a.agent.card", other));
        InstancesResponse initial = responseWith(firstV1, second);
        InstancesResponse updated = responseWith(second, firstV2);
        when(consumerAPI.getHealthyInstances(any(GetHealthyInstancesRequest.class)))
                .thenReturn(initial, updated);

        PolarisAgentCardResolver resolver = resolver();
        AgentCard firstCard = resolver.getAgentCard("sticky");
        AgentCard refreshed = resolver.getAgentCard("sticky");

        assertEquals("1.0.0", firstCard.version());
        assertEquals("1.1.0", refreshed.version());
        assertEquals("http://10.0.0.1:8080/", refreshed.url());
        assertNotSame(firstCard, refreshed);
    }

    @Test
    void getAgentCard_switchesWhenStickyInstanceLeavesHealthyList() throws PolarisException {
        String firstJson = AgentCardCodec.toJson(buildCard("failover", "1.0.0", "http://10.0.0.1:8080/"));
        String secondJson = AgentCardCodec.toJson(buildCard("failover", "2.0.0", "http://10.0.0.2:8080/"));
        Instance first = buildInstance("10.0.0.1", 8080, Map.of("a2a.agent.card", firstJson));
        Instance second = buildInstance("10.0.0.2", 8080, Map.of("a2a.agent.card", secondJson));
        InstancesResponse both = responseWith(first, second);
        InstancesResponse onlySecond = responseWith(second);
        when(consumerAPI.getHealthyInstances(any(GetHealthyInstancesRequest.class)))
                .thenReturn(both, onlySecond, onlySecond);

        PolarisAgentCardResolver resolver = resolver();
        assertEquals("http://10.0.0.1:8080/", resolver.getAgentCard("failover").url());
        AgentCard switched = resolver.getAgentCard("failover");
        assertEquals("http://10.0.0.2:8080/", switched.url());
        assertEquals("2.0.0", switched.version());
        assertEquals("http://10.0.0.2:8080/", resolver.getAgentCard("failover").url());
    }

    @Test
    void getAgentCard_polarisFailureAfterCache_keepsStickyCard() throws PolarisException {
        String firstJson = AgentCardCodec.toJson(buildCard("sticky", "1.0.0", "http://10.0.0.1:8080/"));
        Instance first = buildInstance("10.0.0.1", 8080, Map.of("a2a.agent.card", firstJson));
        InstancesResponse firstResponse = responseWith(first);
        when(consumerAPI.getHealthyInstances(any(GetHealthyInstancesRequest.class)))
                .thenReturn(firstResponse)
                .thenThrow(new PolarisException(ErrorCode.API_TIMEOUT, "timeout"));

        PolarisAgentCardResolver resolver = resolver();
        AgentCard cached = resolver.getAgentCard("sticky");
        assertSame(cached, resolver.getAgentCard("sticky"));
    }

    @Test
    void getAgentCard_skipsMalformedCardAndUsesNext() throws PolarisException {
        String validJson = AgentCardCodec.toJson(buildCard("healthy"));
        Instance malformed = buildInstance("10.0.0.1", 8080, Map.of("a2a.agent.card", "{not-json"));
        Instance valid = buildInstance("10.0.0.2", 8080, Map.of("a2a.agent.card", validJson));
        doReturn(responseWith(malformed, valid))
                .when(consumerAPI).getHealthyInstances(any(GetHealthyInstancesRequest.class));

        assertEquals("healthy", resolver().getAgentCard("healthy").name());
    }

    @Test
    void getAgentCard_skipsCardWithDifferentName() throws PolarisException {
        String wrongJson = AgentCardCodec.toJson(buildCard("other"));
        Instance wrong = buildInstance("10.0.0.1", 8080, Map.of("a2a.agent.card", wrongJson));
        doReturn(responseWith(wrong)).when(consumerAPI).getHealthyInstances(any(GetHealthyInstancesRequest.class));

        assertThrows(AgentCardNotFoundException.class, () -> resolver().getAgentCard("requested"));
    }

    @Test
    void getAgentCard_noUsableCandidate_throwsNotFound() throws PolarisException {
        Instance healthy = buildInstance("10.0.0.1", 8080, Map.of());
        doReturn(responseWith(healthy)).when(consumerAPI).getHealthyInstances(any(GetHealthyInstancesRequest.class));

        assertThrows(AgentCardNotFoundException.class, () -> resolver().getAgentCard("missing"));
    }

    @Test
    void getAgentCard_emptyInstances_throwsNotFound() throws PolarisException {
        doReturn(responseWith()).when(consumerAPI).getHealthyInstances(any(GetHealthyInstancesRequest.class));

        assertThrows(AgentCardNotFoundException.class, () -> resolver().getAgentCard("missing"));
    }

    @Test
    void getAgentCard_polarisException_wrapsInIllegalState() throws PolarisException {
        when(consumerAPI.getHealthyInstances(any(GetHealthyInstancesRequest.class)))
                .thenThrow(new PolarisException(ErrorCode.API_INVALID_ARGUMENT, "server down"));

        assertThrows(IllegalStateException.class, () -> resolver().getAgentCard("down"));
    }
}
