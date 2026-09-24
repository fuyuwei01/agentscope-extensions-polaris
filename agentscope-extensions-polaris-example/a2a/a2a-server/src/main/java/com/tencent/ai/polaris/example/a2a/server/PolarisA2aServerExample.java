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
import com.tencent.ai.polaris.example.a2a.model.EchoLastUserInputModel;
import com.tencent.ai.polaris.example.a2a.model.SkillCommandMockModel;
import com.tencent.ai.polaris.skill.PolarisMountedSkillRepository;
import com.tencent.ai.polaris.skill.PolarisSkillRepository;
import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.TransportProtocol;
import io.a2a.util.Utils;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.skill.repository.AgentSkillRepository;
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
 * <p>The agent's skills also come from Polaris. {@link PolarisAgentRegistry} registers the agent as a Polaris service
 * named after the agent card, and {@link PolarisMountedSkillRepository} reads the skills mounted on that same service
 * ({@code Service.extended_metadata}), so by default the agent serves the skills mounted on itself. Mounting is done
 * on the Polaris side; instance registration cannot mount skills.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code POLARIS_DISCOVERY_ADDRESS} — Polaris server address, default {@code 127.0.0.1:8091}</li>
 *   <li>{@code POLARIS_SKILL_ADDRESS} — SkillAPI address; defaults to the discovery host on port 8094</li>
 *   <li>{@code POLARIS_SKILL_SOURCE} — {@code mounted} (default) reads the skills mounted on this agent's service,
 *       {@code published} reads every published skill in the namespace, {@code none} disables skills</li>
 *   <li>{@code A2A_AGENT_NAME} — agent (Polaris service) name, default {@code polaris-a2a-example-agent}</li>
 *   <li>{@code A2A_SERVER_PORT} — HTTP listen port, default {@code 8888}. Advertise host is not set;
 *       AgentScope {@code DeploymentProperties} fills the local IP when host is null.</li>
 *   <li>{@code A2A_MOCK_LLM} — {@code true} skips the LLM; when unset the mock is used automatically if
 *       {@code TOKEN_HUB_API_KEY} is missing. With skills on, the mock answers {@code /skill-list} and
 *       {@code /skill <name>}; with skills off it echoes the last user message.</li>
 *   <li>{@code TOKEN_HUB_API_KEY} — OpenAI-compatible API key</li>
 *   <li>{@code TOKEN_HUB_BASE_URL} — default {@code https://api.openai.com/v1}</li>
 *   <li>{@code OPENAI_MODEL} — default {@code deepseek-v4-flash}</li>
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
 * Then run {@code PolarisA2aClientExample} to talk to it via Polaris discovery; its prompt accepts
 * {@code /skill-list} and {@code /skill <name>} like any other message.
 */
public class PolarisA2aServerExample {

    private static final Logger log = LoggerFactory.getLogger(PolarisA2aServerExample.class);

    private static final String DESCRIPTION =
            "An A2A agent registered to Polaris, able to check weather, calculate, and tell time.";

    public static void main(String[] args) throws Exception {
        String polarisAddress = env("POLARIS_DISCOVERY_ADDRESS", "127.0.0.1:8091");
        String skillAddress = env("POLARIS_SKILL_ADDRESS", "");
        String skillSource = env("POLARIS_SKILL_SOURCE", "mounted");
        String agentName = env("A2A_AGENT_NAME", "polaris-a2a-example-agent");
        int port = Integer.parseInt(env("A2A_SERVER_PORT", "8888"));
        DeploymentProperties deployment = new DeploymentProperties.Builder().port(port).path("/").build();
        String host = deployment.host();

        // 1. Polaris shared context + registry. The context also owns the SkillAPI connection;
        //    without an explicit address SkillAPI uses the discovery host on port 8094.
        PolarisContextManager context = skillAddress.isEmpty()
                ? PolarisContextManager.fromAddress(polarisAddress)
                : PolarisContextManager.fromAddress(polarisAddress, skillAddress);
        PolarisAgentRegistry registry = PolarisAgentRegistry.builder(context).build();

        // 2. Skill source. 'mounted' reads the skills mounted on this agent's own Polaris service,
        //    hence the repository takes the same name the registry registers under.
        AgentSkillRepository skillRepository = openSkillRepository(context, skillSource, agentName);
        boolean skillsEnabled = skillRepository != null;
        log.info("Skill source: {}", skillsEnabled ? skillRepository.getSource() : "disabled");

        // 3. Pick the model. Test environments often cannot reach an LLM; the mock then answers the
        //    skill commands, or echoes the last user message when skills are off.
        boolean mock = useMockModel();
        Model model = mock ? mockModel(skillsEnabled) : openAiModel();
        log.info("Using model {}", model.getModelName());

        // 4. One agent per request, each with its own Toolkit and SkillBox (see the runner).
        AgentRunner agentRunner = new PolarisSkillAgentRunner(
                agentName, DESCRIPTION, sysPrompt(mock, skillsEnabled), model,
                mock ? 3 : 10, skillRepository);

        // 5. Assemble the A2A server (AgentScopeA2aServer only assembles handlers, it does NOT listen on a port)
        ConfigurableAgentCard agentCard = new ConfigurableAgentCard.Builder()
                .name(agentName)
                .description(DESCRIPTION)
                .version("1.0.0")
                .preferredTransport(TransportProtocol.JSONRPC.asString())
                .build();
        AgentScopeA2aServer a2aServer = AgentScopeA2aServer.builder(agentRunner)
                .agentCard(agentCard)
                .deploymentProperties(deployment)
                .withAgentRegistry(registry)
                .build();

        // 6. Expose a JSON-RPC HTTP endpoint. Spring uses an MVC controller; here a built-in JDK HttpServer forwards
        //    POST / to the JsonRpcTransportWrapper and renders streaming results as SSE.
        JsonRpcTransportWrapper transportWrapper = a2aServer.getTransportWrapper(
                TransportProtocol.JSONRPC.asString(), JsonRpcTransportWrapper.class);
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/", new JsonRpcHandler(transportWrapper, a2aServer));
        httpServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        httpServer.start();
        log.info("A2A JSON-RPC endpoint listening on http://{}:{}/", host, port);

        // 7. Endpoint ready -> register agent card + transports to Polaris
        a2aServer.postEndpointReady();
        log.info("Agent '{}' registered to Polaris at {}", agentName, polarisAddress);

        // 8. Block until shutdown, then clean up (registry deRegister + context close)
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            httpServer.stop(2);
            try { registry.close(); } catch (Exception e) { log.warn("registry close failed", e); }
            if (skillRepository != null) {
                try { skillRepository.close(); } catch (Exception e) { log.warn("skill repository close failed", e); }
            }
            try { context.close(); } catch (Exception e) { log.warn("context close failed", e); }
            stop.countDown();
        }));
        stop.await();
    }

    /**
     * Returns the repository for {@code skillSource}, or null when skills are disabled.
     */
    private static AgentSkillRepository openSkillRepository(
            PolarisContextManager context, String skillSource, String agentName) {
        if ("none".equalsIgnoreCase(skillSource)) {
            return null;
        }
        if ("mounted".equalsIgnoreCase(skillSource)) {
            return PolarisMountedSkillRepository.from(context, agentName);
        }
        if ("published".equalsIgnoreCase(skillSource)) {
            return PolarisSkillRepository.from(context);
        }
        throw new IllegalArgumentException(
                "POLARIS_SKILL_SOURCE must be 'mounted', 'published' or 'none': " + skillSource);
    }

    private static boolean useMockModel() {
        String flag = env("A2A_MOCK_LLM", "");
        if (!flag.isEmpty()) {
            return isTruthy(flag);
        }
        return env("TOKEN_HUB_API_KEY", "").isEmpty();
    }

    private static Model mockModel(boolean skillsEnabled) {
        return skillsEnabled ? new SkillCommandMockModel() : new EchoLastUserInputModel();
    }

    private static String sysPrompt(boolean mock, boolean skillsEnabled) {
        if (mock && !skillsEnabled) {
            return "Echo the latest user message. Do not call tools.";
        }
        String base = "You are a helpful assistant. Use the provided tools when the user asks about "
                + "weather, arithmetic, or the current time. Reply concisely.";
        if (!skillsEnabled) {
            return base;
        }
        return base + "\nYour skills are published in Polaris. Command handling:\n"
                + "- When the user sends '/skill-list', list every available skill with its name and "
                + "description. Do not call any tool.\n"
                + "- When the user sends '/skill <name>', call load_skill_through_path(skillId=<the "
                + "skill-id of that skill>, path=\"SKILL.md\") and reply with the returned content "
                + "verbatim.\n"
                + "Otherwise load a skill first when one matches the request.";
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

    private static Model openAiModel() {
        return OpenAIChatModel.builder()
                .apiKey(requireEnv("TOKEN_HUB_API_KEY"))
                .baseUrl(env("TOKEN_HUB_BASE_URL", "https://api.openai.com/v1"))
                .modelName(env("OPENAI_MODEL", "deepseek-v4-flash"))
                .build();
    }

    private static boolean isTruthy(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    private static String requireEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + key);
        }
        return v;
    }
}
