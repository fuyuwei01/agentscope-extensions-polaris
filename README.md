# AgentScope Extensions Polaris

[![License](https://img.shields.io/badge/License-BSD%203--Clause-blue.svg)](https://opensource.org/licenses/BSD-3-Clause)

English | [简体中文](./README-zh.md)

README:

- [Introduction](#introduction)
- [Modules](#modules)
- [A2A Registration](#a2a-registration)
- [Skill Usage](#skill-usage)
- [MCP Discovery](#mcp-discovery)
- [How to Build](#how-to-build)

## Introduction

AgentScope Extensions Polaris is an extension for [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) that integrates with [Polaris](https://polarismesh.cn/) (PolarisMesh) for A2A registration and discovery, read-only loading of published and service-mounted Skills, and MCP discovery and remote tool binding.

- [Polaris Github](https://github.com/polarismesh/polaris)
- [AgentScope Java Github](https://github.com/agentscope-ai/agentscope-java)

Currently supported capabilities:

- **A2A Management** — Register Agent-to-Agent (A2A) endpoints to Polaris and discover peer agents dynamically
- **Skill Loading** — Load published Skills from Polaris, including the versions mounted on a service, and attach them to AgentScope agents
- **MCP Discovery** — Discover registered MCP Servers, bind remote tools to Toolkit, and reconnect to compatible replicas when an instance becomes unavailable. MCP Server registration remains external.
- **Harness example** — One HarnessAgent uses MCP discovery, Polaris skills and A2A registration/discovery. See the [example guide](agentscope-extensions-polaris-example/harness/README.md); enable the separate `harness-example` profile.

## Modules

```
agentscope-extensions-polaris (root)
├── agentscope-extensions-polaris-bom                 # Bill of Materials
├── agentscope-extensions-polaris-core                # Polaris SDK wrapper (polaris-all)
├── agentscope-extensions-polaris-mcp                 # MCP client discovery and session management
├── agentscope-extensions-polaris-a2a                 # A2A registry / discovery
├── agentscope-extensions-polaris-skill               # Read-only Skill repositories
├── agentscope-extensions-polaris-spring-boot-starter # Spring Boot auto-config (Boot 3.2+)
└── agentscope-extensions-polaris-example             # Examples (not published)
```

## A2A Registration

`PolarisAgentRegistry` registers each distinct transport host with an empty protocol and a
Polaris instance port of `1` by default. Transports on the same host share one registration.
The AgentCard retains the A2A endpoint URLs used by clients.

Instance metadata contains `ai-agent-name`, the full AgentCard JSON in `ai-agent-card`, and
`ai-a2a-version` from `AgentCard.protocolVersion` (currently `0.3.0` with AgentScope 1.0.12).
The Polaris instance `version` comes from `AgentCard.version`, the agent's own version.

Set `agentscope.polaris.a2a.registry.port` to override the Polaris instance port:

```yaml
agentscope:
  polaris:
    address: 127.0.0.1:8091
    a2a:
      registry:
        port: 18080        # optional; defaults to 1
```

For pure Java, use `PolarisAgentRegistry.builder(context).port(18080).build()`.
The original plain Java and Spring Boot A2A server examples accept `POLARIS_REGISTRY_PORT` (default `1`). This setting affects
registration and deregistration; configure the A2A server's listen port separately.

### Spring Boot Server Examples

[`a2a-server-spring-boot`](agentscope-extensions-polaris-example/a2a/a2a-server-spring-boot)
uses the official AgentScope Starter to serve JSON-RPC at `/` and register the agent to Polaris.
Start here for the basic Spring Boot integration.

The advanced [`a2a-server-additional-interfaces-spring-boot`](agentscope-extensions-polaris-example/a2a/a2a-server-additional-interfaces-spring-boot)
adds HTTP+JSON at `/a2a/rest` on the HTTP port (default `8888`) and gRPC on `A2A_GRPC_PORT`
(default `8889`). It declares these two protocols in `additionalInterfaces`; all three transports
share one agent and task store. Polaris stores the complete card on one instance.
See each example README for build, offline testing, and registration commands.

The Spring Boot client selects the target Polaris service with the demo-specific property
`polaris.demo.discovery.agent-name` (default `polaris-a2a-example-agent`, also configurable through
`A2A_AGENT_NAME`). For example, pass `--polaris.demo.discovery.agent-name=my-agent` when starting
the client. This property is defined by the example, rather than the official AgentScope Starter.

## Skill Usage

`PolarisSkillRepository` implements AgentScope's `AgentSkillRepository` interface and fetches published Skill packages from Polaris (read-only). See the full example at [`agentscope-extensions-polaris-example/skill/skill-client`](agentscope-extensions-polaris-example/skill/skill-client):

```bash
POLARIS_ADDRESS=127.0.0.1:8091 POLARIS_SKILL_ADDRESS=127.0.0.1:8094 \
  java -jar agentscope-extensions-polaris-example/skill/skill-client/target/*-jar-with-dependencies.jar
```

`POLARIS_SKILL_ADDRESS` is optional in the pure Java example. When it is unset,
`PolarisContextManager.fromAddress(addr)` / `PolarisServerProperties` derive the Skill endpoint by
rewriting the main Polaris address port to `8094`.

To read only the skills declared on a Polaris service (`Service.extended_metadata`), use `PolarisMountedSkillRepository`. It extends `PolarisSkillRepository` and filters reads to the mounted name set. See [`agentscope-extensions-polaris-example/skill/skill-mounted-client`](agentscope-extensions-polaris-example/skill/skill-mounted-client):

```java
try (PolarisContextManager context =
        PolarisContextManager.fromAddress("127.0.0.1:8091", "127.0.0.1:8094")) {
    PolarisMountedSkillRepository repo =
            PolarisMountedSkillRepository.from(context, "demo-agent");
    List<AgentSkill> skills = repo.getAllSkills();
}
```

`serviceName` identifies the Polaris service whose mounted skills should be loaded. In the A2A example below, it matches the agent's registration name. Run the example:

```bash
POLARIS_ADDRESS=127.0.0.1:8091 POLARIS_SKILL_ADDRESS=127.0.0.1:8094 \
POLARIS_SERVICE=demo-agent \
  java -jar agentscope-extensions-polaris-example/skill/skill-mounted-client/target/*-jar-with-dependencies.jar
```

### Running an Agent on Polaris Skills

[`agentscope-extensions-polaris-example/a2a/a2a-server`](agentscope-extensions-polaris-example/a2a/a2a-server)
registers a `ReActAgent` to Polaris over A2A and serves the skills mounted on that same service:
`PolarisAgentRegistry` registers the agent as a Polaris service named after the agent card, and
`PolarisMountedSkillRepository` reads the skills mounted on it, so the agent ends up serving its own
mounted skills.

`ReActAgent.Builder` takes a `SkillBox` rather than a repository, so the example bridges the two:

```java
Toolkit toolkit = new Toolkit();
SkillBox skillBox = new SkillBox(toolkit);
for (AgentSkill skill : repo.getAllSkills()) {
    skillBox.registration().skill(skill).apply();
}
ReActAgent agent = ReActAgent.builder()
        .model(model)
        .toolkit(toolkit)
        .skillBox(skillBox)      // registers load_skill_through_path + SkillHook
        .build();
```

The A2A server builds one agent per request, so this wiring lives in a `BaseReActAgentRunner`
subclass that creates a fresh `Toolkit` and `SkillBox` each time — `skillBox(...)` rebinds the box
to the agent's own toolkit copy, so sharing one box across concurrently built agents would let
skill activation land on the wrong toolkit. Rebuilding per request also refreshes the catalog;
the repositories cache list results and skill zips, so this normally does not hit the Polaris
server. A repository failure is logged and the agent serves without skills.

Talk to it with [`a2a/a2a-client`](agentscope-extensions-polaris-example/a2a/a2a-client), whose
prompt accepts `/skill-list` (list the skills that reached the agent) and `/skill <name>` (print one
skill's SKILL.md through `load_skill_through_path`). Both commands are documented in the system
prompt, so an OpenAI-compatible model answers them itself:

```bash
POLARIS_DISCOVERY_ADDRESS=127.0.0.1:8091 POLARIS_SKILL_ADDRESS=127.0.0.1:8094 \
A2A_AGENT_NAME=demo-agent \
TOKEN_HUB_API_KEY=sk-xxx TOKEN_HUB_BASE_URL=https://api.openai.com/v1 OPENAI_MODEL=gpt-4o \
  java -jar agentscope-extensions-polaris-example/a2a/a2a-server/target/*-jar-with-dependencies.jar
```

Skills come from the agent's own service by default (`POLARIS_SKILL_SOURCE=mounted`); use
`published` for every published skill in the namespace, or `none` to run without skills. Mounting
itself happens on the Polaris side — instance registration cannot mount skills onto a service.

When no LLM is reachable, drop `TOKEN_HUB_API_KEY` (or set `A2A_MOCK_LLM=true`) and an offline mock
model answers the same two commands — it reads the catalog straight out of the injected system
prompt and issues the real `load_skill_through_path` call, so the Polaris → SkillBox → agent path is
still exercised end to end. With skills off, the mock just echoes the last user message.

To let skills refresh without rebuilding the agent yourself, use `HarnessAgent` and its
`DynamicSkillHook`:

```java
try (PolarisContextManager context = PolarisContextManager.fromAddress("127.0.0.1:8091")) {
    AgentSkillRepository repo = PolarisSkillRepository.from(context);
    agent = HarnessAgent.builder().skillRepository(repo) /* ... */ .build();
}
```

### Spring Boot

The Spring Boot starter can auto-configure the same read-only repository from
`agentscope.polaris.skill`. The nested `skill.address` overrides the SkillAPI endpoint; leave it
empty to rewrite the main Polaris address port to `8094`.

```yaml
agentscope:
  polaris:
    address: 127.0.0.1:8091
    namespace: default
    skill:
      enabled: true
      address: ""          # empty => reuse the main host(s) with port 8094
      names: []
      version: ""
      mounted:
        enabled: false     # true => PolarisMountedSkillRepository
```

If `names` is non-empty, the repository loads only those names and skips the list call. When
`mounted.enabled=true`, `names` / list queries are ignored and the bean reads skills mounted on
the current agent service (`Service.extended_metadata`). The service name is
`agentscope.a2a.server.card.name`, or `agentscope.agent.name` if the card name is blank.
`mounted.service-name` can override that.

The default `agentscope.agent` `ReActAgent` of the AgentScope starter is built without a
`SkillBox`, so the repository bean alone never reaches it. This starter therefore declares the
same `ReActAgent` bean ahead of `AgentscopeAutoConfiguration` — same name, system prompt and
`max-iters`, plus a `SkillBox` holding the Polaris skills — and the AgentScope one backs off
through its `@ConditionalOnMissingBean`. Set `agentscope.polaris.skill.attach-to-agent=false` to
keep the AgentScope agent. Applications that build their own agent can keep injecting the
`AgentSkillRepository` bean into `HarnessAgent` / `ReActAgent` as usual.

### Notes

- **Read-only**: `save` / `delete` / `watch` are not supported; Skills are published and managed in the Polaris console.
- **Separate endpoint**: regular Polaris discovery uses port `8091`; Polaris SkillAPI uses port `8094`.
- **Local cache**: Downloaded Skill zip files are cached by polaris-java under `./polaris/backup/skill`.
- **Shared connection**: `SkillAPI` shares the same `SDKContext` as the A2A extension. `PolarisSkillRepository.close()` is a no-op and must **not** destroy `SkillAPI` or the `SDKContext`. `PolarisContextManager.close()` / `SkillAPI.destroy()` **do** destroy the shared `SDKContext` and must outlive the Agent. The try-with-resources snippet above is fine for a short example; production Agents must keep the context for the Agent lifetime.

## MCP Discovery

Run and register the MCP Server with Spring AI Polaris or another compatible provider. The client reads instance `host`, `port`, `protocol` and `metadata[mcp-endpoint-path]`, then obtains tools through MCP.

Add the AgentScope base starter and this project's Polaris starter, keeping the host application's model configuration:

```yaml
agentscope:
  agent:
    enabled: true
  polaris:
    enabled: ${POLARIS_ENABLED:true}
    address: ${POLARIS_DISCOVERY_ADDRESS:${POLARIS_ADDRESS:127.0.0.1:8091}}
    namespace: default
    token: ${POLARIS_TOKEN:}
    mcp:
      discovery:
        attach-to-toolkit: true
        clients:
          - service-name: business-mcp-server
```

The default Agent receives the populated Toolkit. Set `toolkit-bean-name` for a custom Toolkit Bean; multiple Toolkits require an explicit target. Each prototype Toolkit owns separate wrappers. Spring tracks the Toolkit copy created by AgentScope; for manually built agents, call `factory.retain(agent.getToolkit())` after construction.

Release sessions with `PolarisMcpClientFactory.release(agent.getToolkit())`. Releasing one copy removes only its tools; other copies keep their shared connection. The original Toolkit also remains an owner, allowing a singleton Toolkit to create more Agents. When it is no longer needed, call `factory.release(originalToolkit)`; connections close after all owners are released or garbage collected. The default `idle-timeout: 10m` recycles unused connections and discovery leases; the next call initializes again, without guaranteeing preservation of server-side state. Optional client policies default to asynchronous clients, a 30s request timeout and a 10s initialization timeout. Configure `default-scheme: https` for HTTPS and caller-owned `headers` or a Java `headerProvider` for authentication.

Calls reuse the selected instance. An unavailable instance triggers a new connection to a replica with compatible tool definitions. Conflicting tool names fail binding. Sent tool calls are never replayed automatically. Advanced Polaris routing and per-call balancing are outside this initial implementation.

See the [MCP examples](agentscope-extensions-polaris-example/mcp/README.md) for Java and Spring Boot clients, a Spring AI Polaris MCP Server, and an opt-in E2E test against a real Polaris registry.

## How to Build

**Linux and Mac**

```bash
./mvnw clean install
```

**Windows**

```bash
.\mvnw.cmd clean install
```

## License

This project is licensed under the [BSD 3-Clause License](https://opensource.org/licenses/BSD-3-Clause).
