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
├── agentscope-extensions-polaris-bom                 # 版本管理 BOM
├── agentscope-extensions-polaris-core                # Polaris SDK 封装（polaris-all）
├── agentscope-extensions-polaris-mcp                 # MCP 扩展
├── agentscope-extensions-polaris-a2a                 # A2A 注册 / 发现
├── agentscope-extensions-polaris-skill               # Skill 扩展
├── agentscope-extensions-polaris-spring-boot-starter # Spring Boot 自动装配（Boot 3.2+）
└── agentscope-extensions-polaris-example             # 示例（不发布）
```

## Skill 用法

`PolarisSkillRepository` 实现 AgentScope 的 `AgentSkillRepository` 接口，从北极星拉取已发布的 Skill 包（只读）。完整示例见 [`agentscope-extensions-polaris-example/skill/skill-client`](agentscope-extensions-polaris-example/skill/skill-client)：

```bash
POLARIS_ADDRESS=127.0.0.1:8091 POLARIS_SKILL_ADDRESS=127.0.0.1:8094 \
  java -jar agentscope-extensions-polaris-example/skill/skill-client/target/*-jar-with-dependencies.jar
```

纯 Java 示例里的 `POLARIS_SKILL_ADDRESS` 是可选项。不设置时，
`PolarisContextManager.fromAddress(addr)` / `PolarisServerProperties` 会把主北极星地址的端口改写为
`8094` 作为 Skill 服务地址。

只读取挂到某个北极星服务上的技能（`Service.extended_metadata`），用 `PolarisMountedSkillRepository`。它继承 `PolarisSkillRepository`，读操作按挂载名集合过滤。示例见 [`agentscope-extensions-polaris-example/skill/skill-mounted-client`](agentscope-extensions-polaris-example/skill/skill-mounted-client)：

```java
try (PolarisContextManager context =
        PolarisContextManager.fromAddress("127.0.0.1:8091", "127.0.0.1:8094")) {
    PolarisMountedSkillRepository repo =
            PolarisMountedSkillRepository.from(context, "demo-agent");
    List<AgentSkill> skills = repo.getAllSkills();
}
```

`serviceName` 必须与 A2A 注册名一致。运行示例：

```bash
POLARIS_ADDRESS=127.0.0.1:8091 POLARIS_SKILL_ADDRESS=127.0.0.1:8094 \
POLARIS_SERVICE=demo-agent \
  java -jar agentscope-extensions-polaris-example/skill/skill-mounted-client/target/*-jar-with-dependencies.jar
```

### 让 Agent 用上北极星里的技能

[`agentscope-extensions-polaris-example/a2a/a2a-server`](agentscope-extensions-polaris-example/a2a/a2a-server)
把一个 `ReActAgent` 通过 A2A 注册到北极星，并使用挂在同一个服务上的技能：`PolarisAgentRegistry`
以 agent card 名字注册 Polaris 服务，`PolarisMountedSkillRepository` 按同一个名字读取挂载的技能，
于是 agent 提供的正是挂在它自己身上的技能。

`ReActAgent.Builder` 接收的是 `SkillBox` 而不是 repository，所以示例里自己做了桥接：

```java
Toolkit toolkit = new Toolkit();
SkillBox skillBox = new SkillBox(toolkit);
for (AgentSkill skill : repo.getAllSkills()) {
    skillBox.registration().skill(skill).apply();
}
ReActAgent agent = ReActAgent.builder()
        .model(model)
        .toolkit(toolkit)
        .skillBox(skillBox)      // 自动注册 load_skill_through_path 与 SkillHook
        .build();
```

A2A server 每个请求都会新建 agent，因此这段接线放在一个 `BaseReActAgentRunner` 子类里，每次都新建
`Toolkit` 与 `SkillBox`——`skillBox(...)` 会把该 box 重新绑定到当前 agent 的 toolkit 副本上，多个
agent 共享一个 box 时并发构建会让技能激活作用到别的 toolkit 上。每请求重建同时也刷新了技能目录；
两个 repository 都会缓存列表结果与技能 zip，正常不会真的打到北极星服务端。读取失败只记警告，agent
在无技能状态下继续服务。

用 [`a2a/a2a-client`](agentscope-extensions-polaris-example/a2a/a2a-client) 与它对话，其输入支持
`/skill-list`（列出已进入 Agent 的技能）和 `/skill <name>`（通过 `load_skill_through_path` 打印某个
技能的 SKILL.md）。两个命令的语义写在 system prompt 里，因此接真模型时由模型自己完成：

```bash
POLARIS_DISCOVERY_ADDRESS=127.0.0.1:8091 POLARIS_SKILL_ADDRESS=127.0.0.1:8094 \
A2A_AGENT_NAME=demo-agent \
TOKEN_HUB_API_KEY=sk-xxx TOKEN_HUB_BASE_URL=https://api.openai.com/v1 OPENAI_MODEL=gpt-4o \
  java -jar agentscope-extensions-polaris-example/a2a/a2a-server/target/*-jar-with-dependencies.jar
```

技能默认取自 agent 自己的服务（`POLARIS_SKILL_SOURCE=mounted`）；`published` 表示 namespace 下所有
已发布技能，`none` 表示不用技能。挂载动作本身在北极星侧完成——实例注册无法把技能挂到服务上。

测试环境接不上大模型时，不设 `TOKEN_HUB_API_KEY`（或设 `A2A_MOCK_LLM=true`），会切到离线 mock 模型
响应同样两个命令：它直接解析注入到 system prompt 里的技能目录，并真的发出
`load_skill_through_path` 工具调用，北极星 → SkillBox → Agent 这条链路依然被完整验证。关闭技能时，
mock 只回显最后一条用户消息。

若希望技能自动刷新而不自己重建 agent，可以用 `HarnessAgent` 及其 `DynamicSkillHook`：

```java
try (PolarisContextManager context = PolarisContextManager.fromAddress("127.0.0.1:8091")) {
    AgentSkillRepository repo = PolarisSkillRepository.from(context);
    agent = HarnessAgent.builder().skillRepository(repo) /* ... */ .build();
}
```

### Spring Boot

Spring Boot starter 可以根据 `agentscope.polaris.skill` 自动装配同一个只读仓库。
嵌套的 `skill.address` 会覆盖 SkillAPI 地址；留空时会把主北极星地址端口改写为 `8094`。

```yaml
agentscope:
  polaris:
    address: 127.0.0.1:8091
    namespace: default
    skill:
      enabled: true
      address: ""          # 空则复用主地址的 host，并把端口改为 8094
      names: []
      version: ""
      mounted:
        enabled: false     # true 时切到 PolarisMountedSkillRepository
```

`names` 非空时，仓库只加载这些名字并跳过列表查询。`mounted.enabled=true` 时忽略 `names` / 列表查询，
只读取当前 Agent 对应服务 `extended_metadata` 上挂载的技能。服务名默认取
`agentscope.a2a.server.card.name`，为空再取 `agentscope.agent.name`；可用
`mounted.service-name` 覆盖。

AgentScope starter 里 `agentscope.agent` 生成的 `ReActAgent` 不带 `SkillBox`，只有仓库 bean 技能
到不了 Agent。因此本 starter 会在 `AgentscopeAutoConfiguration` 之前声明同一个 `ReActAgent` bean
（名字、system prompt、`max-iters` 都保持一致，额外挂上装有北极星技能的 `SkillBox`），AgentScope
那个会因为 `@ConditionalOnMissingBean` 自动退让。想保留原来的 Agent，设
`agentscope.polaris.skill.attach-to-agent=false`。自己构建 Agent 的应用照旧把
`AgentSkillRepository` bean 接到 `HarnessAgent` / `ReActAgent` 上即可。

### 说明

- **只读**：不支持 `save` / `delete` / `watch`；Skill 在北极星控制台发布与管理。
- **独立端口**：普通 Polaris 服务发现使用 `8091`；Polaris SkillAPI 使用 `8094`。
- **本地缓存**：下载的 Skill zip 由 polaris-java 缓存在 `./polaris/backup/skill`。
- **共享连接**：`SkillAPI` 与 A2A 扩展共用同一 `SDKContext`。`PolarisSkillRepository.close()` 是空操作，不得销毁 `SkillAPI` / `SDKContext`。`PolarisContextManager.close()` / `SkillAPI.destroy()` **会**销毁共享的 `SDKContext`，其生命周期必须长于 Agent。上面示例中的 try-with-resources 适合短示例；生产环境中 Agent 存活期间必须一直持有该 context。

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
