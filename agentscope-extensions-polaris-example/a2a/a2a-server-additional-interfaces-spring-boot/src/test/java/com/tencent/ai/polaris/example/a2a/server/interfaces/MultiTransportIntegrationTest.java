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

import com.tencent.ai.polaris.a2a.util.AgentCardCodec;
import io.a2a.client.Client;
import io.a2a.client.MessageEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.transport.grpc.GrpcTransport;
import io.a2a.client.transport.grpc.GrpcTransportConfig;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.rest.RestTransport;
import io.a2a.client.transport.rest.RestTransportConfig;
import io.a2a.client.transport.spi.ClientTransport;
import io.a2a.grpc.A2AServiceGrpc;
import io.a2a.grpc.GetAgentCardRequest;
import io.a2a.grpc.utils.ProtoUtils;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentInterface;
import io.a2a.spec.EventKind;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.Task;
import io.a2a.spec.TaskQueryParams;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import io.a2a.util.Utils;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP and gRPC requests against the Spring Boot application with an offline model. */
class MultiTransportIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "/agent"})
    void allAdvertisedInterfacesHandleMessagesAndShareTasks(String contextPath) throws Exception {
        int httpPort = availablePort();
        int grpcPort = availablePort();
        while (grpcPort == httpPort) {
            grpcPort = availablePort();
        }
        SpringApplication app = new SpringApplication(PolarisA2aAdditionalInterfacesSpringBootExample.class);
        try (ConfigurableApplicationContext ignored = app.run(
                "--server.port=" + httpPort, "--server.servlet.context-path=" + contextPath,
                "--agentscope.a2a.server.rest.path=" + (contextPath.isEmpty() ? "/a2a/rest" : "/http"),
                "--agentscope.a2a.server.grpc.port=" + grpcPort, "--agentscope.polaris.enabled=false",
                "--agentscope.model.provider=echo-last-user-input", "--agentscope.polaris.skill.enabled=false")) {
            verifyServer("http://127.0.0.1:" + httpPort + contextPath, grpcPort);
        }
        // Context shutdown releases both listening ports.
        try (ServerSocket http = bindReleasedPort(httpPort); ServerSocket grpc = bindReleasedPort(grpcPort)) {
            assertThat(http.isBound()).isTrue();
            assertThat(grpc.isBound()).isTrue();
        }
    }

    static void verifyServer(String baseUrl, int grpcPort) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> cardResponse = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/.well-known/agent-card.json"))
                        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(cardResponse.statusCode()).isEqualTo(200);
        AgentCard card = AgentCardCodec.fromJson(cardResponse.body());
        assertThat(card.protocolVersion()).isEqualTo("0.3.0");
        assertThat(card.preferredTransport()).isEqualTo("JSONRPC");
        assertThat(card.additionalInterfaces()).extracting(AgentInterface::transport)
                .containsExactly("HTTP+JSON", "GRPC");
        AgentInterface restInterface = card.additionalInterfaces().stream()
                .filter(i -> i.transport().equals("HTTP+JSON")).findFirst().orElseThrow();
        AgentInterface grpcInterface = card.additionalInterfaces().stream()
                .filter(i -> i.transport().equals("GRPC")).findFirst().orElseThrow();
        assertThat(URI.create(grpcInterface.url()).getPort()).isEqualTo(grpcPort);

        assertThat(URI.create(card.url()).getPath()).isEqualTo(URI.create(baseUrl).getPath() + "/");
        String instanceIp = URI.create(card.url()).getHost();
        assertThat(URI.create(restInterface.url()).getHost()).isEqualTo(instanceIp);
        assertThat(URI.create(grpcInterface.url()).getHost()).isEqualTo(instanceIp);
        assertThat(instanceIp).isNotEqualTo("0.0.0.0");
        ManagedChannel channel = ManagedChannelBuilder.forAddress(instanceIp, grpcPort)
                .usePlaintext().build();
        GrpcTransport grpc = new GrpcTransport(channel, card);
        RestTransport rest = new RestTransport(null, card, restInterface.url(), List.of());
        try {
            assertThat(A2AServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS).getAgentCard(GetAgentCardRequest.getDefaultInstance()))
                    .isEqualTo(ProtoUtils.ToProto.agentCard(card));

            List<AgentInterface> interfaces = new ArrayList<>();
            interfaces.add(new AgentInterface(card.preferredTransport(), card.url()));
            interfaces.addAll(card.additionalInterfaces());
            for (AgentInterface iface : interfaces) {
                ClientTransport transport = switch (iface.transport()) {
                    case "JSONRPC" -> new JSONRPCTransport(null, card, iface.url(), List.of());
                    case "HTTP+JSON" -> rest;
                    case "GRPC" -> grpc;
                    default -> throw new AssertionError("Unexpected transport: " + iface.transport());
                };
                try {
                    String marker = "echo-" + UUID.randomUUID();
                    EventKind result = transport.sendMessage(params(marker), null);
                    assertThat(result).isInstanceOf(Message.class);
                    assertThat(Utils.toJsonString(result)).contains(marker);

                    CompletableFuture<String> completed = new CompletableFuture<>();
                    List<String> events = new CopyOnWriteArrayList<>();
                    String streamMarker = "stream-" + UUID.randomUUID();
                    transport.sendMessageStreaming(params(streamMarker), event -> {
                        events.add(Utils.toJsonString(event));
                        if (event instanceof TaskStatusUpdateEvent status && status.isFinal()) {
                            assertThat(status.getStatus().state()).isEqualTo(TaskState.COMPLETED);
                            completed.complete(status.getTaskId());
                        }
                    }, completed::completeExceptionally, null);
                    String taskId = completed.get(20, TimeUnit.SECONDS);
                    assertThat(String.join("\n", events)).contains(streamMarker).contains("completed");
                    // A streamed task created through any interface is queryable through both extra protocols.
                    Task saved = rest.getTask(new TaskQueryParams(taskId), null);
                    assertThat(saved.getId()).isEqualTo(taskId);
                    // SDK 0.3.3 formats a one-digit historyLength with a leading space;
                    // exercise the server's history filter with a valid HTTP URL directly.
                    HttpResponse<String> history = http.send(HttpRequest.newBuilder(URI.create(
                                    restInterface.url() + "/v1/tasks/" + taskId + "?historyLength=1"))
                            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(history.statusCode()).isEqualTo(200);
                    assertThat(Utils.OBJECT_MAPPER.readTree(history.body()).get("history")).hasSize(1);
                    assertThat(grpc.getTask(new TaskQueryParams(taskId), null).getId()).isEqualTo(taskId);
                    System.out.println("PASS " + iface.transport() + " " + iface.url()
                            + " send, stream, shared task");
                } finally {
                    if (iface.transport().equals("JSONRPC")) {
                        transport.close();
                    }
                }
            }

            // Use Client.builder with only the desired protocol: it selects the advertised additional interface.
            for (String protocol : List.of("HTTP+JSON", "GRPC")) {
                var builder = Client.builder(card)
                        .clientConfig(new ClientConfig.Builder().setStreaming(false).build());
                if (protocol.equals("HTTP+JSON")) {
                    builder.withTransport(RestTransport.class, new RestTransportConfig());
                } else {
                    builder.withTransport(GrpcTransport.class, new GrpcTransportConfig(url -> channel));
                }
                Client client = builder.build();
                try {
                    CompletableFuture<String> response = new CompletableFuture<>();
                    String marker = "selection-" + UUID.randomUUID();
                    client.sendMessage(params(marker).message(),
                            List.of((event, updatedCard) -> response.complete(Utils.toJsonString(
                                    event instanceof MessageEvent message ? message.getMessage()
                                            : ((TaskEvent) event).getTask()))),
                            response::completeExceptionally);
                    assertThat(response.get(20, TimeUnit.SECONDS)).contains(marker);
                    System.out.println("PASS Client selects " + protocol + " from additionalInterfaces");
                } finally {
                    client.close();
                }
            }

            HttpResponse<String> bad = http.send(HttpRequest.newBuilder(
                            URI.create(restInterface.url() + "/v1/message:send"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{"))
                    .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(bad.statusCode()).isEqualTo(400);
            assertThat(bad.body()).contains("error");
            System.out.println("PASS HTTP+JSON malformed request returns 400");
            for (String removed : List.of("/a2a/jsonrpc", "/a2a/internal", "/a2a/external")) {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + removed))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(404);
            }
            System.out.println("PASS redundant JSON-RPC paths are absent");
        } finally {
            rest.close();
            grpc.close();
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static MessageSendParams params(String text) {
        return new MessageSendParams.Builder().message(new Message.Builder()
                .role(Message.Role.USER).messageId(UUID.randomUUID().toString())
                .parts(new TextPart(text)).build()).build();
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ServerSocket bindReleasedPort(int port) throws Exception {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        return socket;
    }

    /** Reuses these assertions to smoke-test the packaged JAR. */
    public static void main(String[] args) throws Exception {
        verifyServer(args[0], Integer.parseInt(args[1]));
    }
}
