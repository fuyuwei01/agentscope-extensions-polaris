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

import io.agentscope.spring.boot.a2a.controller.A2aJsonRpcController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** JSON-RPC aliases sharing the starter's request handling and SSE conversion. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdditionalJsonRpcController {

    private final A2aJsonRpcController jsonRpcController;

    public AdditionalJsonRpcController(A2aJsonRpcController jsonRpcController) {
        this.jsonRpcController = jsonRpcController;
    }

    @PostMapping(
            value = {"/a2a/jsonrpc", "/a2a/internal", "/a2a/external"},
            consumes = "application/json",
            produces = {"application/json", "text/event-stream"})
    public Object handleRequest(@RequestBody String body, @RequestHeader Map<String, String> headers) {
        return jsonRpcController.handleRequest(body, headers);
    }
}
