# AgentScope Extensions Polaris

[![License](https://img.shields.io/badge/License-BSD%203--Clause-blue.svg)](https://opensource.org/licenses/BSD-3-Clause)

[English](./README.md) | 简体中文

README:

- [介绍](#介绍)
- [模块](#模块)
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
