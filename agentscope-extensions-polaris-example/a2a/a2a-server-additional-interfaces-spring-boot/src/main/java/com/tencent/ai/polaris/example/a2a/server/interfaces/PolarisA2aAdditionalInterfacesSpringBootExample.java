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

package com.tencent.ai.polaris.example.a2a.server.interfaces;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A2A 0.3 server exposing the same agent at several JSON-RPC URLs.
 *
 * <p>The main interface and three aliases are configured through
 * {@code agentscope.a2a.server.card.additional-interfaces}. Polaris stores the complete
 * card on one instance, and the AgentScope JSON-RPC controller handles every interface.
 * The default model echoes user messages without requiring an LLM API key.
 */
@SpringBootApplication
public class PolarisA2aAdditionalInterfacesSpringBootExample {

    public static void main(String[] args) {
        SpringApplication.run(PolarisA2aAdditionalInterfacesSpringBootExample.class, args);
    }
}
