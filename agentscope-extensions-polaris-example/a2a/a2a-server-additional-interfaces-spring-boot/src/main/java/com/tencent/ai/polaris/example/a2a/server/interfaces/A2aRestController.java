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

import io.a2a.spec.TransportProtocol;
import io.a2a.transport.rest.handler.RestHandler.HTTPRestResponse;
import io.a2a.transport.rest.handler.RestHandler.HTTPRestStreamingResponse;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import org.reactivestreams.FlowAdapters;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

/** A2A 0.3 HTTP+JSON routes. Payloads use the SDK's protobuf JSON mapping. */
@RestController
@ConditionalOnProperty(prefix = "agentscope.a2a.server", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("${agentscope.a2a.server.rest.path:/a2a/rest}")
public class A2aRestController {

    private final RestTransportWrapper wrapper;

    public A2aRestController(AgentScopeA2aServer server) {
        wrapper = server.getTransportWrapper(TransportProtocol.HTTP_JSON.asString(), RestTransportWrapper.class);
        if (wrapper == null) {
            throw new IllegalStateException("A2A HTTP+JSON transport was not built");
        }
    }

    @PostMapping(value = "/v1/message:send", consumes = "application/json")
    public ResponseEntity<?> send(@RequestBody String body, @RequestHeader Map<String, String> headers) {
        return response(wrapper.handleRequest(body, headers, Map.of()));
    }

    @PostMapping(value = "/v1/message:stream", consumes = "application/json", produces = "text/event-stream")
    public ResponseEntity<?> stream(@RequestBody String body, @RequestHeader Map<String, String> headers) {
        return response(wrapper.handler().sendStreamingMessage(body, wrapper.context("message/stream", true, headers)));
    }

    @GetMapping("/v1/tasks/{id}")
    public ResponseEntity<?> getTask(
            @PathVariable("id") String id, @RequestParam(name = "historyLength", defaultValue = "0") int historyLength,
            @RequestHeader Map<String, String> headers) {
        return response(wrapper.handler().getTask(id, historyLength, wrapper.context("tasks/get", false, headers)));
    }

    @PostMapping("/v1/tasks/{id}:cancel")
    public ResponseEntity<?> cancel(@PathVariable("id") String id, @RequestHeader Map<String, String> headers) {
        return response(wrapper.handler().cancelTask(id, wrapper.context("tasks/cancel", false, headers)));
    }

    @PostMapping(value = "/v1/tasks/{id}:subscribe", produces = "text/event-stream")
    public ResponseEntity<?> subscribe(@PathVariable("id") String id, @RequestHeader Map<String, String> headers) {
        return response(wrapper.handler().resubscribeTask(id, wrapper.context("tasks/resubscribe", true, headers)));
    }

    @PostMapping(value = "/v1/tasks/{id}/pushNotificationConfigs", consumes = "application/json")
    public ResponseEntity<?> setPush(
            @PathVariable("id") String id, @RequestBody String body, @RequestHeader Map<String, String> headers) {
        return response(wrapper.handler().setTaskPushNotificationConfiguration(
                id, body, wrapper.context("tasks/pushNotificationConfig/set", false, headers)));
    }

    @GetMapping("/v1/tasks/{id}/pushNotificationConfigs/{configId}")
    public ResponseEntity<?> getPush(
            @PathVariable("id") String id, @PathVariable("configId") String configId, @RequestHeader Map<String, String> headers) {
        return response(wrapper.handler().getTaskPushNotificationConfiguration(
                id, configId, wrapper.context("tasks/pushNotificationConfig/get", false, headers)));
    }

    @GetMapping("/v1/tasks/{id}/pushNotificationConfigs")
    public ResponseEntity<?> listPush(@PathVariable("id") String id, @RequestHeader Map<String, String> headers) {
        return response(wrapper.handler().listTaskPushNotificationConfigurations(
                id, wrapper.context("tasks/pushNotificationConfig/list", false, headers)));
    }

    @DeleteMapping("/v1/tasks/{id}/pushNotificationConfigs/{configId}")
    public ResponseEntity<?> deletePush(
            @PathVariable("id") String id, @PathVariable("configId") String configId, @RequestHeader Map<String, String> headers) {
        return response(wrapper.handler().deleteTaskPushNotificationConfiguration(
                id, configId, wrapper.context("tasks/pushNotificationConfig/delete", false, headers)));
    }

    @GetMapping("/.well-known/agent-card.json")
    public ResponseEntity<?> card() {
        return response(wrapper.handler().getAgentCard());
    }

    private ResponseEntity<?> response(HTTPRestResponse response) {
        if (response instanceof HTTPRestStreamingResponse streaming) {
            Flux<ServerSentEvent<String>> events = Flux.from(FlowAdapters.toPublisher(streaming.getPublisher()))
                    .map(data -> ServerSentEvent.builder(data).build());
            return ResponseEntity.status(response.getStatusCode()).contentType(MediaType.TEXT_EVENT_STREAM).body(events);
        }
        return ResponseEntity.status(response.getStatusCode())
                .contentType(MediaType.parseMediaType(response.getContentType())).body(response.getBody());
    }
}
