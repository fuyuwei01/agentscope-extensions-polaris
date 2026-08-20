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
├── agentscope-extensions-polaris-bom      # Bill of Materials
├── agentscope-extensions-polaris-core     # Polaris SDK wrapper (polaris-all)
├── agentscope-extensions-polaris-mcp      # MCP extension
├── agentscope-extensions-polaris-a2a      # A2A extension
└── agentscope-extensions-polaris-skill    # Skill extension
```

## Skill Usage

`PolarisSkillRepository` implements AgentScope's `AgentSkillRepository` interface and fetches published Skill packages from Polaris (read-only). See the full example at [`agentscope-extensions-polaris-example/skill/skill-client`](agentscope-extensions-polaris-example/skill/skill-client).

### Plain Java

```java
try (PolarisContextManager context = PolarisContextManager.fromAddress("127.0.0.1:8091")) {
    AgentSkillRepository repo = PolarisSkillRepository.from(context);
    agent = HarnessAgent.builder().skillRepository(repo) /* ... */ .build();
}
```

### Spring Boot

Add the `agentscope-extensions-polaris-spring-boot-starter` dependency and configure:

```yaml
agentscope:
  polaris:
    address: 127.0.0.1:8091
    namespace: default
    skill:
      enabled: true
      names: []          # empty lists all published skills
      version: ""        # empty uses activeVersion
```

Inject the auto-configured `AgentSkillRepository` bean into `HarnessAgent` / `ReActAgent` (same as existing SkillBox registration).

### Notes

- **Read-only**: `save` / `delete` / `watch` are not supported; Skills are published and managed in the Polaris console.
- **Local cache**: Downloaded Skill zip files are cached by polaris-java under `./polaris/backup/skill`.
- **Shared connection**: `SkillAPI` shares the same `SDKContext` as the A2A extension; closing `PolarisContextManager` does not destroy `SkillAPI` separately.

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
