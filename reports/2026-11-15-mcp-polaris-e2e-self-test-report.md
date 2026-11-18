# AgentScope MCP 北极星发现端到端自测报告

## 验证结论

**本次已验证的端到端接入行为符合预期。** 使用用户提供的北极星 `43.144.18.55` 重新构建当前工作区并执行真实注册、服务发现、MCP 工具获取和调用、Spring 自动绑定及副本切换验证。SSE、STREAMABLE、STATELESS 三个远程 E2E 场景均通过。

本轮于 **2026-11-15 01:08–01:09（Asia/Shanghai）**执行构建和测试，`clean verify` 在 **01:09:10**完成，耗时 **55.900 秒**；E2E 测试耗时 **25.53 秒**。13 个相关模块构建成功，**198 个普通测试 + 3 个远程 E2E = 201 个测试**，失败、错误、跳过均为 0。普通测试数量包含公共模块回归，不能解释为 201 个远程场景。01:10:10 独立复查确认本轮三个随机服务均已删除、实例数为零，相关测试 JVM 已退出。

分支：`codex/mcp-polaris-discovery`；基线提交：`81eebfecdd189759cd074cb5c3e188ce4a26cc5e`。验证包含未提交实现和最新英文注释；[源码 SHA-256 快照](2026-11-15-mcp-polaris-e2e-source-snapshot.json)记录了 137 个文件，执行前后哈希一致。本轮未修改产品实现，未提交代码。

环境：OpenJDK 25.0.2、Maven 3.9.9；项目以 Java 17 为编译目标。实际服务端组合为 Spring AI Polaris 0.1.0、Spring AI 1.1.4、Spring Boot 3.5.13、MCP Java SDK 0.17.0、Polaris Java SDK 2.2.0.0-RC1；客户端 AgentScope 1.0.12。注册中心是远程北极星，MCP Server 和客户端运行在同一测试机。

证据：[执行摘要](2026-11-15-mcp-polaris-e2e-verification.txt)、[逐测试套件结果](2026-11-15-mcp-polaris-e2e-test-results.json)、[Failsafe 原始汇总](2026-11-15-mcp-polaris-e2e-failsafe-summary.xml)。

## 需求对照

- **R1：Spring AI Polaris Server demo 自动注册。通过（T1）。** 独立 JAR 启动并由外部 Server Starter 注册健康实例、发布协议与入口元数据；运行包没有 AgentScope 客户端依赖。
- **R2：AgentScope 通过北极星找到服务器，并从 MCP 服务器获取工具。通过（T1、T2）。** 客户端只配置服务标识，无需手填实例 IP、端口、协议和入口路径；原生 MCP 客户端初始化并获取 `serverInfo`、`echo`、`add`，实际调用成功。北极星提供连接信息，工具列表通过 MCP 获取。
- **R3：Spring 配置自动绑定 Agent Toolkit。通过（T3）。** YAML 配置触发自动绑定，Agent 真正执行远程工具并返回副本标识；释放 Agent Toolkit 后工具移除。
- **R4：重复工具调用复用选定实例，副本下线后继续调用。通过（T2、T4）。** 连续查询保持同一副本；STREAMABLE 双副本场景停止选中的进程后，原 Toolkit 与新 Agent 均成功调用剩余副本。
- **R5：可重复验证并清理本轮资源。通过（T5）。** 测试使用随机服务名和端口，只停止自己创建的进程，并在实例为空后删除本轮服务；另行只读查询确认清理完成。

## 公共准备与复测入口

`$REPO_ROOT` 指仓库根目录。需要 Maven、JDK，以及能读写既有命名空间的北极星环境。本轮使用 `default`，匿名注册、查询和清理成功；不依赖模型 API Key。

```sh
cd "$REPO_ROOT"
export POLARIS_DISCOVERY_ADDRESS='43.144.18.55:8091'
export POLARIS_HTTP_ADDRESS='43.144.18.55:8090'
export POLARIS_NAMESPACE='default'
export POLARIS_TOKEN=''

mvn -Pmcp-e2e \
  -pl agentscope-extensions-polaris-example/mcp/mcp-server-spring-ai-polaris,agentscope-extensions-polaris-example/mcp/mcp-client,agentscope-extensions-polaris-example/mcp/mcp-client-spring-boot \
  -am clean verify
```

默认构建不访问远程北极星；`mcp-e2e` profile 才会执行本报告的远程场景。本轮为避开本机仓库配置差异，使用临时 Maven settings 将依赖和插件仓库指向 Maven Central，没有改动用户 settings。复测环境如有同样需要，在命令中附加 `-s '<MAVEN_SETTINGS_XML>'`。

本轮通过 `-DargLine=-javaagent:<MOCKITO_CORE_JAR>` 显式加载 Mockito 5.23.0，避免受限 JVM 的自附加问题。复测时把占位符替换为该 JAR 的路径。该参数替代了 JaCoCo agent，因此本报告不提供覆盖率结论。

统一脚本位于 `agentscope-extensions-polaris-example/mcp/mcp-server-spring-ai-polaris/src/test/java/com/tencent/ai/polaris/example/mcp/server/PolarisMcpE2EIT.java`；Spring 配置位于同模块 `src/test/resources/agentscope-e2e.yml`。原始日志在同模块 `target/e2e/`，JUnit 结果在 `target/failsafe-reports/`；原始日志可能包含机器路径，移交优先使用本报告附件。测试会自动清理本轮资源。

## 复测场景

### T1：真实注册、元数据与发布地址

- 对应需求：R1、R2。运行统一入口，依次启动 SSE、STREAMABLE、STATELESS 独立 Server JVM；服务名随机、端口由系统分配。
- 预期：北极星出现健康实例，包含 host、port、正确 protocol、完整 endpoint 路径及非空协议版本；客户端能使用这些信息连接。
- 本次实际结果：三种模式均通过。SSE 发布 `mcp-sse` 和 `/demo/events`；HTTP 两种模式发布 `mcp-streamable-http` 和 `/demo/tools`。SSE 不配置发布 IP，自动检测出的地址属于本机非回环网卡，且实际调用成功；HTTP 使用 `127.0.0.1` 验证显式覆盖。
- 状态与证据：**通过**；执行摘要中的三条 `registration metadata verified`、自动检测及显式覆盖记录。JAR 检查确认注册来自 Spring AI Polaris Server Starter。

### T2：工具发现、执行与实例复用

- 对应需求：R2、R4。统一入口以原生 Toolkit 注册发现到的客户端，执行 `serverInfo`、`echo(message=hello)`、`add(a=19,b=23)`。
- 预期：Toolkit 包含上述三个工具，`echo` 返回副本标识加 `:hello`，`add` 返回 `42`；连续三次追加查询 `serverInfo` 与首次结果一致。
- 本次实际结果：三个模式均满足断言。SSE 使用同步原生客户端，HTTP 使用异步原生客户端。MCP 初始化协商版本分别为 SSE 的 `2024-11-05`、HTTP 的 `2025-06-18`。双副本初次选中 `replica-b`，切换前重复调用保持该标识。
- 状态与证据：**通过**；三条 `native Toolkit: echo/add/session affinity verified`。本场景证明重复调用保持实例，不是 TCP 连接数量或握手次数测量。

### T3：YAML 自动绑定与 Agent 真实工具调用

- 对应需求：R3。统一入口加载测试 YAML 和真实自动配置，创建 ReActAgent，发送 `Which replica?`。
- 预期：Agent Toolkit 自动包含 `serverInfo`；模型产生工具调用，Agent 执行远程 MCP 工具，返回实际副本标识；调用 `release(agent.getToolkit())` 后工具移除。
- 本次实际结果：三个模式均通过；双副本切换后额外创建的新 Agent 也通过，共四条成功记录。测试模型仅确定选择 `serverInfo`，Polaris 注册发现、MCP 通信及服务端执行均为真实实现。
- 状态与证据：**通过**；四条 `Spring Agent YAML auto-binding, real tool call, release verified`。

### T4：选中副本退出后的切换

- 对应需求：R4。STREAMABLE 场景启动两个副本，停止当前选中的副本，等待北极星实例从两个变为一个。
- 预期：发现更新后，原 Toolkit 后续查询及 `echo(message=after-switch)` 通过剩余副本成功执行；新建 Agent 也能调用。
- 本次实际结果：停止 `replica-b` 后切到 `replica-a`；原 Toolkit 的 `echo` 返回 `replica-a:after-switch`，新 Agent 返回剩余副本标识。
- 状态与证据：**通过**；`selected replica stopped; switched to replica-a; existing Toolkit and new Agent call succeeded`。
- 边界：发现更新期间允许调用失败；测试通过重新发起无副作用查询等待恢复，不代表自动重放已发送的业务调用，也不证明服务端业务会话状态迁移。

### T5：注销、空服务删除与进程清理

- 对应需求：R5。每个场景在 `finally` 中关闭客户端、终止保存的 Server Process，等待实例注销后删除对应随机空服务。
- 预期：本轮三个服务均无实例且已删除，相关 Server JVM 全部退出。
- 本次实际结果：三个模式均记录清理成功。01:10:10 独立 HTTP 查询三个服务均返回 `instances=0; services=0`，进程检查未发现对应 Java Server。
- 状态与证据：**通过**；执行摘要中的三条 `cleanup verified`、三条 `independent cleanup verified` 和进程检查结果。

## 问题与验证缺口

本轮没有失败或阻塞的验收场景。编译与 Javadoc 存在非阻断告警，包括 Java 17 编译目标未使用 `--release` 的提示、注释标签缺失及依赖使用 JDK 弃用 API；构建成功不等于这些告警已经消除。独立清理检查首次被进程输出中的非 UTF-8 字符中断，采用容错解码后复查成功，不涉及产品修复。

以下项目**未执行**，不纳入已通过结论：

- MCP 服务端与客户端跨机器通信、容器/NAT/VPN、多网卡路由和 IPv6。
- TLS、非空 Polaris Token、MCP Authorization 及真实模型 API 请求。
- WebFlux、JDK 17 运行时，以及上游默认 Polaris SDK 2.1.2.0-RC1 的兼容性。
- 大并发、性能/连接数量测量、业务会话状态迁移，以及 SSE/STATELESS 的双副本切换。
- 本轮打包客户端 JAR 的独立启动及环境变量优先级冒烟；这部分此前报告有记录，本次没有重新执行。

没有对“每次工具调用都重新选实例”的策略做验收；本轮验证的是当前实现的复用与失效重建行为。清理和证据均仅针对本轮创建的资源，未改动既有服务。
