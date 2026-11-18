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

package com.tencent.ai.polaris.example.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tencent.ai.polaris.spring.boot.mcp.PolarisMcpClientFactory;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;

class PolarisMcpApplicationTest {
    @Test
    void exampleReleasesItsAgentToolkitAfterDisplayingTools() throws Exception {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("demo")
                        .model(mock(Model.class))
                        .toolkit(new Toolkit())
                        .build();
        ObjectProvider<ReActAgent> agents = mock(ObjectProvider.class);
        ObjectProvider<PolarisMcpClientFactory> factories = mock(ObjectProvider.class);
        PolarisMcpClientFactory factory = mock(PolarisMcpClientFactory.class);
        when(agents.getObject()).thenReturn(agent);
        when(factories.getIfAvailable()).thenReturn(factory);
        new PolarisMcpApplication()
                .showTools(agents, factories)
                .run(new DefaultApplicationArguments());
        verify(factory).release(agent.getToolkit());
    }

    @Test
    void exampleCanRunWhenPolarisDiscoveryIsDisabled() throws Exception {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("demo")
                        .model(mock(Model.class))
                        .toolkit(new Toolkit())
                        .build();
        ObjectProvider<ReActAgent> agents = mock(ObjectProvider.class);
        ObjectProvider<PolarisMcpClientFactory> factories = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        assertDoesNotThrow(
                () ->
                        new PolarisMcpApplication()
                                .showTools(agents, factories)
                                .run(new DefaultApplicationArguments()));
        verify(factories).getIfAvailable();
    }
}
