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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolarisServerPropertiesTest {

    @Test
    void skillAddressListDerivesPort8094FromDiscoveryHost() {
        PolarisServerProperties props = new PolarisServerProperties("192.168.1.8:8091");
        assertEquals(List.of("192.168.1.8:8094"), props.skillAddressList());
    }

    @Test
    void skillAddressListUsesExplicitOverride() {
        PolarisServerProperties props = new PolarisServerProperties("127.0.0.1:8091");
        props.setSkillAddress("10.0.0.2:8094,10.0.0.3:8094");
        assertEquals(List.of("10.0.0.2:8094", "10.0.0.3:8094"), props.skillAddressList());
    }

    @Test
    void skillAddressListMapsEachDiscoveryHost() {
        PolarisServerProperties props = new PolarisServerProperties("a:8091, b:8091");
        assertEquals(List.of("a:8094", "b:8094"), props.skillAddressList());
    }

    @Test
    void defaultDiscoveryFallsBackToLocalhostAndSkillPort() {
        PolarisServerProperties props = new PolarisServerProperties();
        assertEquals(List.of(PolarisServerProperties.DEFAULT_ADDRESS), props.serverAddressList());
        assertEquals(List.of(PolarisServerProperties.DEFAULT_SKILL_ADDRESS), props.skillAddressList());
    }
}
