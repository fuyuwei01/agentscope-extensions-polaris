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

package com.tencent.ai.polaris.example.mcp.server;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.mcp.client.PolarisMcpClientBuilder;
import com.tencent.ai.polaris.mcp.client.PolarisMcpClientWrapper;
import com.tencent.ai.polaris.mcp.discovery.PolarisMcpServerManager;
import com.tencent.ai.polaris.spring.boot.AgentscopePolarisAutoConfiguration;
import com.tencent.ai.polaris.spring.boot.mcp.AgentscopeMcpPolarisClientAutoConfiguration;
import com.tencent.ai.polaris.spring.boot.mcp.AgentscopeMcpPolarisToolkitAutoConfiguration;
import com.tencent.ai.polaris.spring.boot.mcp.PolarisMcpClientFactory;
import com.tencent.polaris.api.rpc.GetHealthyInstancesRequest;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import reactor.core.publisher.Flux;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Opt-in tests: real Spring AI Polaris registrar, remote Polaris, and native AgentScope tools. */
class PolarisMcpE2EIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String address = discoveryAddress();
    private final String namespace = System.getenv().getOrDefault("POLARIS_NAMESPACE", "default");
    private final String admin = "http://" + requiredEnvironment("POLARIS_HTTP_ADDRESS");
    private final List<ServerProcess> processes = new ArrayList<>();

    @ParameterizedTest
    @ValueSource(strings = {"SSE", "STREAMABLE", "STATELESS"})
    void registrationDiscoveryToolkitAgentAndFailover(String mode) throws Exception {
        String service =
                "agentscope-mcp-e2e-"
                        + mode.toLowerCase(Locale.ROOT)
                        + "-"
                        + UUID.randomUUID().toString().substring(0, 12);
        assertEquals(0, instances(service).size(), "Only a new, empty service may be used");
        try {
            startServer(service, mode, "replica-a");
            int replicas = "STREAMABLE".equals(mode) ? 2 : 1;
            if (replicas == 2) startServer(service, mode, "replica-b");
            await(
                    "registered healthy replicas",
                    Duration.ofSeconds(90),
                    () ->
                            uncheckedInstances(service).size() == replicas
                                    && uncheckedInstances(service).stream()
                                            .allMatch(
                                                    instance ->
                                                            instance.path("healthy").asBoolean()));
            String path = "/demo" + ("SSE".equals(mode) ? "/events" : "/tools");
            for (JsonNode instance : instances(service)) {
                String host = instance.path("host").asText();
                if ("SSE".equals(mode)) {
                    InetAddress detected = InetAddress.getByName(host);
                    assertFalse(detected.isAnyLocalAddress());
                    assertFalse(detected.isLoopbackAddress());
                    assertNotNull(
                            NetworkInterface.getByInetAddress(detected),
                            "Auto-detected address must belong to a local network interface");
                } else {
                    assertEquals("127.0.0.1", host);
                }
                assertTrue(instance.path("port").asInt() > 0);
                assertEquals(
                        "SSE".equals(mode) ? "mcp-sse" : "mcp-streamable-http",
                        instance.path("protocol").asText());
                assertEquals(path, instance.path("metadata").path("mcp-endpoint-path").asText());
                assertEquals(service, instance.path("metadata").path("mcp-server-name").asText());
                assertFalse(
                        instance.path("metadata").path("mcp-protocol-version").asText().isBlank());
            }
            evidence(service, mode + " registration metadata verified; replicas=" + replicas);
            evidence(
                    service,
                    "SSE".equals(mode)
                            ? "advertise host auto-detected: local non-loopback address verified"
                            : "explicit advertise host override verified");
            try (PolarisContextManager context = PolarisContextManager.fromAddress(address);
                    PolarisMcpServerManager manager = new PolarisMcpServerManager(context);
                    PolarisMcpClientWrapper client =
                            PolarisMcpClientBuilder.create(service, manager)
                                    .namespace(namespace)
                                    .asyncClient(!"SSE".equals(mode))
                                    .build()) {
                Toolkit toolkit = new Toolkit();
                GetHealthyInstancesRequest discoveryRequest = new GetHealthyInstancesRequest();
                discoveryRequest.setNamespace(namespace);
                discoveryRequest.setService(service);
                await(
                        "Polaris SDK observes healthy replicas",
                        Duration.ofSeconds(65),
                        () -> {
                            try {
                                return context.consumerAPI()
                                                .getHealthyInstances(discoveryRequest)
                                                .getInstances()
                                                .length
                                        == replicas;
                            } catch (RuntimeException discoveryPending) {
                                return false;
                            }
                        });
                toolkit.registerMcpClient(client).block(Duration.ofSeconds(45));
                assertTrue(
                        toolkit.getToolNames().containsAll(List.of("serverInfo", "echo", "add")));
                assertEquals(path, client.getSelectedEndpoint().path());
                String selected = call(toolkit, "serverInfo", Map.of());
                for (int i = 0; i < 3; i++) {
                    assertEquals(
                            selected,
                            call(toolkit, "serverInfo", Map.of()),
                            "Repeated calls must stay on the selected replica");
                }
                assertEquals(
                        selected + ":hello", call(toolkit, "echo", Map.of("message", "hello")));
                assertEquals("42", call(toolkit, "add", Map.of("a", 19, "b", 23)));
                evidence(
                        service,
                        "native Toolkit: echo/add/session affinity verified; selected=" + selected);
                verifySpringAgent(service, Set.of("replica-a", "replica-b"));
                if (replicas == 2) {
                    ServerProcess selectedProcess =
                            processes.stream()
                                    .filter(process -> process.instanceId.equals(selected))
                                    .findFirst()
                                    .orElseThrow();
                    stop(selectedProcess);
                    await(
                            "selected instance deregistration",
                            Duration.ofSeconds(65),
                            () -> uncheckedInstances(service).size() == 1);
                    String remaining = "replica-a".equals(selected) ? "replica-b" : "replica-a";
                    await(
                            "client switches after discovery update",
                            Duration.ofSeconds(65),
                            () -> {
                                try {
                                    return remaining.equals(call(toolkit, "serverInfo", Map.of()));
                                } catch (RuntimeException transientFailure) {
                                    return false;
                                }
                            });
                    assertEquals(
                            remaining + ":after-switch",
                            call(toolkit, "echo", Map.of("message", "after-switch")));
                    assertNotNull(client.getSelectedEndpoint());
                    assertNotEquals(selectedProcess.port, client.getSelectedEndpoint().port());
                    verifySpringAgent(service, Set.of(remaining));
                    evidence(
                            service,
                            "selected replica stopped; switched to "
                                    + remaining
                                    + "; existing Toolkit and new Agent call succeeded");
                }
            }
        } finally {
            List<Throwable> failures = new ArrayList<>();
            for (ServerProcess process : processes) {
                try {
                    stop(process);
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }
            try {
                await(
                        "all test instances deregister",
                        Duration.ofSeconds(65),
                        () -> uncheckedInstances(service).isEmpty());
                deleteEmptyService(service);
                evidence(service, "cleanup verified: instances=0; test service removed");
            } catch (Throwable failure) {
                failures.add(failure);
            }
            if (!failures.isEmpty()) {
                AssertionError failure =
                        new AssertionError("E2E cleanup failed; inspect target/e2e");
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
        }
    }

    private void verifySpringAgent(String service, Set<String> allowedReplicas) throws Exception {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "e2e-target",
                                    Map.of(
                                            "e2e.polaris.address",
                                            address,
                                            "e2e.polaris.namespace",
                                            namespace,
                                            "e2e.mcp.service",
                                            service)));
            for (var properties :
                    new YamlPropertySourceLoader()
                            .load("agent-mcp", new ClassPathResource("agentscope-e2e.yml"))) {
                context.getEnvironment().getPropertySources().addLast(properties);
            }
            context.registerBean(Model.class, AgentConfiguration::model);
            context.register(AgentConfiguration.class);
            context.refresh();
            ReActAgent agent = context.getBean(ReActAgent.class);
            assertTrue(agent.getToolkit().getToolNames().contains("serverInfo"));
            Msg response =
                    agent.call(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .textContent("Which replica?")
                                            .build())
                            .block(Duration.ofSeconds(30));
            assertNotNull(response);
            assertTrue(
                    allowedReplicas.contains(response.getTextContent()),
                    "The Agent response must be the actual MCP server's identifier: " + response);
            context.getBean(PolarisMcpClientFactory.class).release(agent.getToolkit());
            assertFalse(agent.getToolkit().getToolNames().contains("serverInfo"));
            evidence(service, "Spring Agent YAML auto-binding, real tool call, release verified");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
        AgentscopeAutoConfiguration.class,
        AgentscopePolarisAutoConfiguration.class,
        AgentscopeMcpPolarisClientAutoConfiguration.class,
        AgentscopeMcpPolarisToolkitAutoConfiguration.class
    })
    static class AgentConfiguration {
        static Model model() {
            return new Model() {
                @Override
                public Flux<ChatResponse> stream(
                        List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                    for (int i = messages.size() - 1; i >= 0; i--) {
                        List<ToolResultBlock> results =
                                messages.get(i).getContentBlocks(ToolResultBlock.class);
                        if (!results.isEmpty()) {
                            String text =
                                    results.get(0).getOutput().stream()
                                            .filter(TextBlock.class::isInstance)
                                            .map(TextBlock.class::cast)
                                            .map(TextBlock::getText)
                                            .reduce("", String::concat);
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(TextBlock.builder().text(text).build()))
                                            .finishReason("stop")
                                            .build());
                        }
                    }
                    assertTrue(
                            tools.stream().anyMatch(tool -> "serverInfo".equals(tool.getName())));
                    return Flux.just(
                            ChatResponse.builder()
                                    .content(
                                            List.of(
                                                    ToolUseBlock.builder()
                                                            .id("e2e-server-info")
                                                            .name("serverInfo")
                                                            .input(Map.of())
                                                            .content("{}")
                                                            .build()))
                                    .finishReason("tool_calls")
                                    .build());
                }

                @Override
                public String getModelName() {
                    return "deterministic-mcp-e2e";
                }
            };
        }
    }

    private void startServer(String service, String mode, String instanceId) throws Exception {
        Path directory = logDirectory();
        Path log = directory.resolve(service + "-" + instanceId + ".log");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command =
                new ArrayList<>(
                        List.of(
                                javaExecutable,
                                "-jar",
                                System.getProperty("mcp.e2e.server-jar"),
                                "--server.port=0",
                                "--server.shutdown=immediate",
                                "--server.servlet.context-path=/demo",
                                "--spring.ai.mcp.server.sse-endpoint=/events",
                                "--spring.ai.mcp.server.sse-message-endpoint=/messages",
                                "--spring.ai.mcp.server.base-url=/demo",
                                "--spring.ai.mcp.server.streamable-http.mcp-endpoint=/tools",
                                "--spring.ai.polaris.mcp.server.contract.enabled=false",
                                "--spring.main.banner-mode=off"));
        if (!"SSE".equals(mode)) {
            command.add("--spring.ai.polaris.mcp.server.host=127.0.0.1");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("POLARIS_ENABLED", "true");
        builder.environment().put("POLARIS_DISCOVERY_ADDRESS", address);
        builder.environment().put("POLARIS_NAMESPACE", namespace);
        builder.environment().put("MCP_SERVICE_NAME", service);
        builder.environment().put("MCP_PROTOCOL", mode);
        builder.environment().put("MCP_INSTANCE_ID", instanceId);
        // SSE exercises the default configuration even if the parent shell sets an override.
        builder.environment().remove("MCP_ADVERTISE_HOST");
        builder.environment().remove("SPRING_AI_POLARIS_MCP_SERVER_HOST");
        Process process =
                builder.directory(directory.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        ServerProcess server = new ServerProcess(instanceId, process, log);
        processes.add(server);
        await(
                "server process ready",
                Duration.ofSeconds(90),
                () -> {
                    if (!process.isAlive()) {
                        throw new AssertionError(
                                "MCP Server exited with " + process.exitValue() + "; log=" + log);
                    }
                    try {
                        String output = Files.readString(log);
                        var matcher =
                                java.util.regex.Pattern.compile("Tomcat started on port (\\d+)")
                                        .matcher(output);
                        if (matcher.find()) server.port = Integer.parseInt(matcher.group(1));
                        return server.port > 0
                                && output.contains("MCP server registered with Polaris");
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                });
    }

    private void stop(ServerProcess server) throws Exception {
        if (!server.process.isAlive()) return;
        server.process.destroy();
        if (!server.process.waitFor(30, TimeUnit.SECONDS)) {
            server.process.destroyForcibly();
            server.process.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError("Server did not stop gracefully; log=" + server.log);
        }
    }

    private List<JsonNode> instances(String service) throws Exception {
        String query = "?namespace=" + encode(namespace) + "&service=" + encode(service);
        JsonNode response = request("GET", "/naming/v1/instances" + query, null);
        List<JsonNode> instances = new ArrayList<>();
        response.path("instances").forEach(instances::add);
        return instances;
    }

    private List<JsonNode> uncheckedInstances(String service) {
        try {
            return instances(service);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void deleteEmptyService(String service) throws Exception {
        assertTrue(service.startsWith("agentscope-mcp-e2e-"));
        assertTrue(
                instances(service).isEmpty(), "Never delete a service with registered instances");
        String query = "?namespace=" + encode(namespace) + "&name=" + encode(service);
        JsonNode existing = request("GET", "/naming/v1/services" + query, null);
        if (existing.path("amount").asInt() == 0) return;
        String body =
                JSON.writeValueAsString(List.of(Map.of("namespace", namespace, "name", service)));
        request("POST", "/naming/v1/services/delete", body);
        await(
                "test service removed",
                Duration.ofSeconds(15),
                () -> {
                    try {
                        return request("GET", "/naming/v1/services" + query, null)
                                        .path("amount")
                                        .asInt()
                                == 0;
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });
    }

    private JsonNode request(String method, String path, String body) throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create(admin + path)).timeout(Duration.ofSeconds(10));
        if (body == null) builder.GET();
        else
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Polaris HTTP request failed for " + path);
        JsonNode json = JSON.readTree(response.body());
        assertEquals(
                200000,
                json.path("code").asInt(),
                "Polaris operation failed: " + json.path("info"));
        return json;
    }

    private static String call(Toolkit toolkit, String name, Map<String, Object> args) {
        var result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .id(UUID.randomUUID().toString())
                                                        .name(name)
                                                        .input(args)
                                                        .content(toJson(args))
                                                        .build())
                                        .input(args)
                                        .build())
                        .block(Duration.ofSeconds(15));
        assertNotNull(result);
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }

    private static String toJson(Map<String, Object> args) {
        try {
            return JSON.writeValueAsString(args);
        } catch (Exception failure) {
            throw new IllegalArgumentException(failure);
        }
    }

    private static void await(String description, Duration timeout, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(500);
        }
        fail("Timed out waiting for " + description);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank())
            throw new IllegalStateException(
                    "The mcp-e2e profile requires environment variable " + name);
        return value;
    }

    private static String discoveryAddress() {
        String address = System.getenv("POLARIS_DISCOVERY_ADDRESS");
        if (address != null && !address.isBlank()) return address;
        address = System.getenv("POLARIS_ADDRESS");
        if (address != null && !address.isBlank()) return address;
        throw new IllegalStateException(
                "The mcp-e2e profile requires POLARIS_DISCOVERY_ADDRESS"
                        + " (or the legacy POLARIS_ADDRESS)");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Path logDirectory() throws java.io.IOException {
        Path path = Path.of(System.getProperty("mcp.e2e.log-directory"));
        Files.createDirectories(path);
        return path;
    }

    private static void evidence(String service, String message) throws java.io.IOException {
        String line = service + " " + message + System.lineSeparator();
        System.out.print("E2E " + line);
        Files.writeString(
                logDirectory().resolve("verification.txt"),
                line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static final class ServerProcess {
        final String instanceId;
        final Process process;
        final Path log;
        int port;

        ServerProcess(String instanceId, Process process, Path log) {
            this.instanceId = instanceId;
            this.process = process;
            this.log = log;
        }
    }
}
