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

package com.tencent.ai.polaris.example.a2a.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.tencent.ai.polaris.a2a.registry.PolarisAgentRegistry;
import com.tencent.ai.polaris.core.PolarisContextManager;
import com.tencent.ai.polaris.example.a2a.server.tools.ExampleTools;
import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.TransportProtocol;
import io.a2a.util.Utils;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Plain-Java (non-Spring) A2A server example backed by Polaris.
 *
 * <p>Hosts a {@link ReActAgent} powered by an OpenAI-compatible model and a small set of tools, exposes it over a
 * built-in JSON-RPC HTTP endpoint, and registers the agent card + transport endpoint to Polaris via
 * {@link PolarisAgentRegistry}.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code POLARIS_ADDRESS} — Polaris server address, default {@code 127.0.0.1:8091}</li>
 *   <li>{@code A2A_AGENT_NAME} — agent (Polaris service) name, default {@code polaris-a2a-example-agent}</li>
 *   <li>{@code A2A_SERVER_HOST} — exported host written into the card, default {@code localhost}</li>
 *   <li>{@code A2A_SERVER_PORT} — HTTP listen port, default {@code 8888}</li>
 *   <li>{@code OPENAI_API_KEY} — required, LLM api key</li>
 *   <li>{@code OPENAI_BASE_URL} — default {@code https://api.openai.com/v1}</li>
 *   <li>{@code OPENAI_MODEL} — default {@code gpt-4o-mini}</li>
 * </ul>
 *
 * <p>Once running, verify with curl:
 * <pre>{@code
 * # fetch the agent card
 * curl http://localhost:8888/.well-known/agent-card.json
 * # send a streaming message (handled by the JSON-RPC handler below)
 * curl -N -X POST http://localhost:8888/ -H 'Content-Type: application/json' \
 *   -d '{"jsonrpc":"2.0","id":"1","method":"message/stream","params":{"message":{"role":"user","kind":"message","parts":[{"kind":"text","text":"What is 346 * 47?"}],"messageId":"m1","contextId":"c1"}}}'
 * }</pre>
 * Then run {@code PolarisA2aClientExample} to talk to it via Polaris discovery.
 */
public class PolarisA2aServerExample {

    private static final Logger log = LoggerFactory.getLogger(PolarisA2aServerExample.class);

    public static void main(String[] args) throws Exception {
        String polarisAddress = env("POLARIS_ADDRESS", "114.132.133.191:8091");
        String agentName = env("A2A_AGENT_NAME", "polaris-a2a-example-agent");
        String host = env("A2A_SERVER_HOST", "localhost");
        int port = Integer.parseInt(env("A2A_SERVER_PORT", "8888"));

        // 1. Polaris shared context + registry
        PolarisContextManager context = PolarisContextManager.fromAddress(polarisAddress);
        PolarisAgentRegistry registry = PolarisAgentRegistry.builder(context).build();

        // 2. Build the agent: OpenAI-compatible model + a few tools
        Model model = OpenAIChatModel.builder()
                .apiKey(requireEnv("TOKEN_HUB_API_KEY"))
                .baseUrl(env("TOKEN_HUB_BASE_URL", "https://api.openai.com/v1"))
                .modelName(env("OPENAI_MODEL", "deepseek-v4-flash"))
                .build();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExampleTools());
        ReActAgent.Builder agentBuilder = ReActAgent.builder()
                .name(agentName)
                .description("An A2A agent registered to Polaris, able to check weather, calculate, and tell time.")
                .sysPrompt("You are a helpful assistant. Use the provided tools when the user asks about "
                        + "weather, arithmetic, or the current time. Reply concisely.")
                .model(model)
                .toolkit(toolkit)
                .maxIters(10);

        // 3. Assemble the A2A server (AgentScopeA2aServer only assembles handlers, it does NOT listen on a port)
        DeploymentProperties deployment = new DeploymentProperties.Builder()
                .host(host).port(port).path("/").build();
        ConfigurableAgentCard agentCard = new ConfigurableAgentCard.Builder()
                .name(agentName)
                .description("An A2A agent registered to Polaris, able to check weather, calculate, and tell time.")
                .version("1.0.0")
                .url("http://" + host + ":" + port + "/")
                .preferredTransport(TransportProtocol.JSONRPC.asString())
                .build();
        AgentScopeA2aServer a2aServer = AgentScopeA2aServer.builder(agentBuilder)
                .agentCard(agentCard)
                .deploymentProperties(deployment)
                .withAgentRegistry(registry)
                .build();

        // 4. Expose a JSON-RPC HTTP endpoint. Spring uses an MVC controller; here a built-in JDK HttpServer forwards
        //    POST / to the JsonRpcTransportWrapper and renders streaming results as SSE.
        JsonRpcTransportWrapper transportWrapper = a2aServer.getTransportWrapper(
                TransportProtocol.JSONRPC.asString(), JsonRpcTransportWrapper.class);
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/", new JsonRpcHandler(transportWrapper, a2aServer));
        httpServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        httpServer.start();
        log.info("A2A JSON-RPC endpoint listening on http://{}:{}/", host, port);

        // 5. Endpoint ready -> register agent card + transports to Polaris
        a2aServer.postEndpointReady();
        log.info("Agent '{}' registered to Polaris at {}", agentName, polarisAddress);

        // 6. Block until shutdown, then clean up (registry deRegister + context close)
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            httpServer.stop(2);
            try { registry.close(); } catch (Exception e) { log.warn("registry close failed", e); }
            try { context.close(); } catch (Exception e) { log.warn("context close failed", e); }
            stop.countDown();
        }));
        stop.await();
    }

    private static final class JsonRpcHandler implements HttpHandler {

        private static final ObjectMapper MAPPER = Utils.OBJECT_MAPPER;

        private final JsonRpcTransportWrapper wrapper;
        private final AgentScopeA2aServer server;

        JsonRpcHandler(JsonRpcTransportWrapper wrapper, AgentScopeA2aServer server) {
            this.wrapper = wrapper;
            this.server = server;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Optional: serve the agent card at the well-known path (GET /.well-known/agent-card.json)
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())
                    && exchange.getRequestURI().getPath().endsWith("/.well-known/agent-card.json")) {
                byte[] bytes = MAPPER.writeValueAsBytes(server.getAgentCard());
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v == null || v.isEmpty() ? "" : v.get(0)));
            Object result = wrapper.handleRequest(body, headers, Map.of());

            try (OutputStream os = exchange.getResponseBody()) {
                if (result instanceof Flux<?> flux) {
                    // streaming request: one "jsonrpc" SSE event per JSONRPCResponse
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    flux.toIterable().forEach(item -> writeSse(os, item));
                    os.write("event: done\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } else {
                    String json = MAPPER.writeValueAsString(result);
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    os.write(bytes);
                }
            } catch (Exception e) {
                log.error("Failed to write A2A response", e);
            }
        }

        private void writeSse(OutputStream os, Object item) {
            try {
                JSONRPCResponse<?> resp = (JSONRPCResponse<?>) item;
                String data = MAPPER.writeValueAsString(resp);
                os.write(("event: jsonrpc\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Exception e) {
                log.warn("Failed to write SSE chunk", e);
            }
        }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    private static String requireEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + key);
        }
        return v;
    }
}
