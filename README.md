# AgentScope Extensions Polaris

[![License](https://img.shields.io/badge/License-BSD%203--Clause-blue.svg)](https://opensource.org/licenses/BSD-3-Clause)

English | [简体中文](./README-zh.md)

README:

- [Introduction](#introduction)
- [Modules](#modules)
- [Skill Usage](#skill-usage)
- [How to Build](#how-to-build)

## Introduction

AgentScope Extensions Polaris is an extension for [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) that integrates with [Polaris](https://polarismesh.cn/) (PolarisMesh) for MCP / A2A / Skill registration, discovery and governance.

- [Polaris Github](https://github.com/polarismesh/polaris)
- [AgentScope Java Github](https://github.com/agentscope-ai/agentscope-java)

AgentScope with Polaris can solve these problems:

- **MCP Management** — Register MCP Server instances to Polaris on startup; discover and load-balance MCP Servers from the client side
- **A2A Management** — Register Agent-to-Agent (A2A) endpoints to Polaris and discover peer agents dynamically
- **Skill Management** — Store and version AgentScope Skills in Polaris for centralized distribution

## Modules

```
agentscope-extensions-polaris (root)
├── agentscope-extensions-polaris-bom                 # Bill of Materials
├── agentscope-extensions-polaris-core                # Polaris SDK wrapper (polaris-all)
├── agentscope-extensions-polaris-mcp                 # MCP extension
├── agentscope-extensions-polaris-a2a                 # A2A registry / discovery
├── agentscope-extensions-polaris-skill               # Skill extension
├── agentscope-extensions-polaris-spring-boot-starter # Spring Boot auto-config (Boot 3.2+)
└── agentscope-extensions-polaris-example             # Examples (not published)
```

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

`serviceName` must match the A2A registration name. Run the example:

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
```

If `names` is non-empty, the repository loads only those names and skips the list call. Wire the
resulting `AgentSkillRepository` bean into `HarnessAgent` / `ReActAgent` the same way as other
repository implementations.

### Notes

- **Read-only**: `save` / `delete` / `watch` are not supported; Skills are published and managed in the Polaris console.
- **Separate endpoint**: regular Polaris discovery uses port `8091`; Polaris SkillAPI uses port `8094`.
- **Local cache**: Downloaded Skill zip files are cached by polaris-java under `./polaris/backup/skill`.
- **Shared connection**: `SkillAPI` shares the same `SDKContext` as the A2A extension. `PolarisSkillRepository.close()` is a no-op and must **not** destroy `SkillAPI` or the `SDKContext`. `PolarisContextManager.close()` / `SkillAPI.destroy()` **do** destroy the shared `SDKContext` and must outlive the Agent. The try-with-resources snippet above is fine for a short example; production Agents must keep the context for the Agent lifetime.

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
