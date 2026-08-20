# AgentScope Extensions Polaris

[![License](https://img.shields.io/badge/License-BSD%203--Clause-blue.svg)](https://opensource.org/licenses/BSD-3-Clause)

[English](./README.md) | 简体中文

README:

- [介绍](#介绍)
- [模块](#模块)
- [Skill 用法](#skill-用法)
- [如何构建](#如何构建)

## 介绍

AgentScope Extensions Polaris 是一个 [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) 扩展项目，集成 [北极星](https://polarismesh.cn/)（PolarisMesh）实现 MCP / A2A / Skill 的注册、发现与治理。

- [北极星 Github](https://github.com/polarismesh/polaris)
- [AgentScope Java Github](https://github.com/agentscope-ai/agentscope-java)

AgentScope 集成北极星可以解决以下问题：

- **MCP 管理** — MCP Server 启动时自动注册到北极星；MCP Client 从北极星发现并对实例做负载均衡
- **A2A 管理** — Agent-to-Agent（A2A）端点注册到北极星，并动态发现对端 Agent
- **Skill 管理** — AgentScope Skill 集中存储与版本化在北极星，统一下发

## 模块

```
agentscope-extensions-polaris (root)
├── agentscope-extensions-polaris-bom      # 版本管理 BOM
├── agentscope-extensions-polaris-core     # Polaris SDK 封装（polaris-all）
├── agentscope-extensions-polaris-mcp      # MCP 扩展
├── agentscope-extensions-polaris-a2a      # A2A 扩展
└── agentscope-extensions-polaris-skill    # Skill 扩展
```

## Skill 用法

`PolarisSkillRepository` 实现 AgentScope 的 `AgentSkillRepository` 接口，从北极星拉取已发布的 Skill 包（只读）。完整示例见 [`agentscope-extensions-polaris-example/skill/skill-client`](agentscope-extensions-polaris-example/skill/skill-client)。

### 纯 Java

```java
try (PolarisContextManager context = PolarisContextManager.fromAddress("127.0.0.1:8091")) {
    AgentSkillRepository repo = PolarisSkillRepository.from(context);
    agent = HarnessAgent.builder().skillRepository(repo) /* ... */ .build();
}
```

### Spring Boot

添加依赖 `agentscope-extensions-polaris-spring-boot-starter`，配置：

```yaml
agentscope:
  polaris:
    address: 127.0.0.1:8091
    namespace: default
    skill:
      enabled: true
      names: []          # 空则 List 全部 published
      version: ""        # 空则 activeVersion
```

将自动配置的 `AgentSkillRepository` bean 注入 `HarnessAgent` / `ReActAgent`（与现有 SkillBox 注册方式一致）。

### 说明

- **只读**：不支持 `save` / `delete` / `watch`；Skill 在北极星控制台发布与管理。
- **本地缓存**：下载的 Skill zip 由 polaris-java 缓存在 `./polaris/backup/skill`。
- **共享连接**：`SkillAPI` 与 A2A 扩展共用同一 `SDKContext`。仓库侧不得调用 `PolarisSkillRepository.close()` / `SkillAPI.destroy()`，它们会拆除共享的 `SDKContext`。`PolarisContextManager.close()` **会**销毁 `SDKContext`，其生命周期必须长于 Agent。上面示例中的 try-with-resources 适合短示例；生产环境中 Agent 存活期间必须一直持有该 context。

## 如何构建

**Linux and Mac**

```bash
./mvnw clean install
```

**Windows**

```bash
.\mvnw.cmd clean install
```

## 许可证

本项目基于 [BSD 3-Clause 许可证](https://opensource.org/licenses/BSD-3-Clause) 开源。
