# Harness Agent 集成示例自测报告

## 验证结论

符合本次集成示例预期。真实 HarnessAgent 通过现有 Polaris 扩展完成 MCP 工具绑定与调用、Skill 下载加载、A2A 注册发现与委派。完整相关 reactor `verify` 执行 201 项测试，0 失败、0 错误、0 跳过；新增 2 项集成场景分别覆盖服务挂载和 namespace 已发布 Skill。

验证日期：2026-11-16；最终 verify 完成于 12:20:03（Asia/Shanghai），可执行 JAR 与脚本验证完成于 12:21:00 后。分支 `codex/mcp-polaris-discovery`，HEAD `81eebfecdd189759cd074cb5c3e188ce4a26cc5e`，包含未提交改动，见 [源码快照](2026-11-16-harness-integration-source-snapshot.json)。

环境：OpenJDK 25.0.2、Maven 3.9.9、Spring Boot 3.5.13。示例 core / harness 使用本地安装的 `1.1.0-SNAPSHOT`；现有扩展和 A2A Starter 使用项目默认 `1.0.12`。采用独立 profile，仅示例覆盖 core 版本。Mockito 使用显式 Java agent，无本次覆盖率数据。

## 验收点与实际结果

- **R1：真实 HarnessAgent，通过。** Spring Boot A2A AgentRunner 创建 HarnessAgent，每请求独立内存、Toolkit 和 workspace，运行日志记录 Harness 的推理与工具调用。JAR 能独立启动并通过 ask.sh 接收消息。
- **R2：MCP 发现与调用，通过。** ConsumerAPI 提供实例地址与 `mcp-endpoint-path`；真实 MCP HTTP Server 提供 tools/list，Agent 发起 add(19,23)，A2A 响应包含 42。没有把工具列表写入北极星实例元数据。
- **R3：Skill 拉取与使用，通过。** 挂载模式读取 getAllInstances 的 extended_metadata，发布模式读取 listSkills。两者都通过 SkillAPI downloadSkill 得到 ZIP，经实际仓库解析、Harness 绑定与 load_skill_through_path 调用后，响应包含 ZIP 内的 `POLARIS_SKILL_CONTENT`；/skill-list 包含真实加载的技能名。
- **R4：A2A 注册与发现，通过。** 官方 Starter 注册完整 AgentCard，PolarisAgentCardResolver 回读同一张卡片。ask_agent 按服务名发现本服务并嵌套发起真实 A2A HTTP 请求，内层 Agent 调用 MCP 后返回 42。关闭应用验证 deRegister 被调用。
- **R5：请求释放隔离，通过。** 依次执行多个 A2A 请求及嵌套请求后，再次执行 add(1,2) 返回 3；原始单例 Toolkit 的连接不被请求副本释放。底层 MCP 工具调用次数为 3。
- **R6：已有功能回归，通过。** core、MCP、A2A、Skill、starter、共享离线模型的相关测试通过，包括 6 项真实 MCP HTTP 通信测试。

## 复测准备与执行

1. 在本机 Maven 仓库安装与示例对应的 AgentScope core / harness `1.1.0-SNAPSHOT`（参照示例 README），准备 Java、Maven，以及允许本机随机监听端口的运行环境。
2. 在 `$REPO_ROOT` 执行：

```sh
mvn -Pharness-example -pl agentscope-extensions-polaris-example/harness -am verify
```

在限制 JDK 自附加的环境中，设置 `MOCKITO_AGENT` 为实际 mockito-core 5.23.0 JAR，追加 `"-DargLine=-javaagent:$MOCKITO_AGENT"`。本次使用缓存依赖和临时 Maven settings，未修改用户 settings。

`HarnessIntegrationTest` 每种 Skill 来源都执行：启动真实本机 MCP 和 A2A Server → 注册与回读卡片 → /mcp add → /skill-list → /skill integration-guide → /a2a 本服务 /mcp add → 再次 /mcp add → 关闭与注销。无需外部北极星或模型 Key；只有 ProviderAPI、ConsumerAPI、SkillAPI 为测试替身。

可执行 JAR 验证：设置 `POLARIS_ENABLED=false`、`POLARIS_SKILL_ENABLED=false`、`MCP_DISCOVERY_ENABLED=false` 与一个可用的 `A2A_SERVER_PORT`，运行产物，使用同一端口执行示例 `ask.sh /skill-list`。实际返回完整 JSON-RPC message，内容说明未加载 Skill。已停止本次创建的 JAR 进程。

## 问题处理与验证边界

该本地 snapshot 的 DynamicSkillHook 注册目标是构建前 Toolkit，实际运行曾返回 `Tool not found: load_skill_through_path`。示例改用 `disableDynamicSkills()`，每请求构建时静态绑定 Skill，随后真实工具调用测试通过。刷新粒度是请求，仍遵循仓库缓存；没有宣称会话内动态刷新。

A2A Starter 会在启动时根据配置端口生成 AgentCard；测试通过预分配端口，避免配置 0 后卡片 URL 仍指向 0。示例生产默认端口固定为 8889。

本次没有连接远程北极星、发布或挂载真实 Skill，也没有调用真实大模型。测试证据支持本机通信和扩展接线，不替代远程北极星端到端验证。跨请求持久化、取消/重入完整并发矩阵未单独验证。示例保留每请求 workspace 供查看，需按部署策略清理历史目录。

[执行摘要](2026-11-16-harness-integration-verification.txt)；[运行与配置说明](../agentscope-extensions-polaris-example/harness/README.md)。未提交或推送代码。
