# MCP 示例

服务端须已由 Spring AI Polaris 或其他兼容注册组件注册到北极星。实例发布 `host`、`port`、`protocol=mcp-sse|mcp-streamable-http` 和 `metadata[mcp-endpoint-path]`。本项目不提供服务端注册。

`mcp-client` 展示普通 Java 发现、Toolkit 绑定和资源关闭。设置 `POLARIS_DISCOVERY_ADDRESS`、`POLARIS_NAMESPACE`、`MCP_SERVICE_NAME` 后运行 `PolarisMcpClientExample`，可选传入无参数工具名执行调用。

`mcp-client-spring-boot` 展示默认 Agent 自动绑定。通过与 A2A demo 相同的模型环境变量启动 `PolarisMcpApplication`；示例仅打印已发现工具，不发起模型请求。可设置 `MCP_MODEL_PROVIDER=echo-last-user-input` 使用已有共享离线模型，无需 API Key。

公共环境变量与 A2A Spring Boot demo 对齐：

- `POLARIS_DISCOVERY_ADDRESS`：北极星发现／注册地址，默认 `127.0.0.1:8091`。兼容原 `POLARIS_ADDRESS`；同时设置时新名称优先。
- `POLARIS_ENABLED`：是否启用北极星，默认 `true`。关闭后普通 Java 客户端直接退出，Spring Boot 客户端打印空工具集，Server 仍提供 MCP 但不注册。
- `POLARIS_NAMESPACE`：命名空间，默认 `default`。
- `POLARIS_TOKEN`：北极星 SDK 鉴权 Token，默认空；三个 demo 都会传入 SDK，Server 使用上游 `PolarisConfigModifier` 扩展点。

MCP 场景参数继续使用 `MCP_` 前缀：三个 demo 使用相同的 `MCP_SERVICE_NAME`（默认 `business-mcp-server`）；服务端使用 `MCP_SERVER_PORT`、`MCP_PROTOCOL`、`MCP_INSTANCE_ID` 和可选 `MCP_ADVERTISE_HOST`；Spring Boot 客户端使用 `MCP_AGENT_NAME`、`MCP_MODEL_PROVIDER`。选择 `MCP_MODEL_PROVIDER=openai` 时，配置与 A2A 相同的 `TOKEN_HUB_API_KEY`、`TOKEN_HUB_BASE_URL`、`OPENAI_MODEL`。

每个用户聊天会话获取一次 Agent 并复用。结束时调用 `PolarisMcpClientFactory.release(agent.getToolkit())`，释放该 Agent 的连接。自定义 Toolkit Bean 可配置 `agentscope.polaris.mcp.discovery.toolkit-bean-name`；普通代码创建的 Toolkit 调用 `factory.bind(toolkit)`，构建 Agent 后调用 `factory.retain(agent.getToolkit())` 记录 AgentScope 创建的副本。

默认 HTTP 无需地址配置。HTTPS 可设置客户端 `default-scheme: https`，鉴权可设置 `headers` 或在 Java Builder 使用 `headerProvider`。同一 Wrapper 复用已选实例，失效时重新初始化兼容副本；已发送的工具调用不自动重放。

构建示例：

```sh
mvn -pl agentscope-extensions-polaris-example/mcp/mcp-client,agentscope-extensions-polaris-example/mcp/mcp-client-spring-boot -am package
```

普通 Java 示例：

```sh
export POLARIS_DISCOVERY_ADDRESS='<POLARIS_HOST>:8091'
export POLARIS_NAMESPACE='default'
export MCP_SERVICE_NAME='business-mcp-server'
java -jar agentscope-extensions-polaris-example/mcp/mcp-client/target/*-jar-with-dependencies.jar
```

Spring Boot 示例使用相同的北极星环境变量，模型配置与 A2A demo 对齐。离线演示：

```sh
export MCP_MODEL_PROVIDER='echo-last-user-input'
java -jar agentscope-extensions-polaris-example/mcp/mcp-client-spring-boot/target/agentscope-extensions-polaris-example-mcp-client-spring-boot-0.1.0-RC1.jar
```

Spring AI 的 SSE 服务使用 context-path 时，应配置 `spring.ai.mcp.server.base-url`，使 SSE 公告的消息地址包含此前缀。北极星 `mcp-endpoint-path` 也需要包含 context-path。客户端直接使用服务端公告的消息地址。

## Spring AI Polaris MCP Server

`mcp-server-spring-ai-polaris` 使用已发布的 Spring AI Polaris Server Starter 0.1.0、Spring AI 1.1.4 和 Spring Boot 3.5.13。服务端通过 `@McpTool` 暴露 `serverInfo`、`echo`、`add` 三个确定性工具；注册、心跳、端点元数据和退出注销均由外部 Starter 完成。本项目的客户端依赖仅用于测试。

```sh
mvn -pl agentscope-extensions-polaris-example/mcp/mcp-server-spring-ai-polaris -am package
export POLARIS_DISCOVERY_ADDRESS='<POLARIS_HOST>:8091'
export POLARIS_NAMESPACE='default'
export MCP_SERVICE_NAME='business-mcp-server'
java -jar agentscope-extensions-polaris-example/mcp/mcp-server-spring-ai-polaris/target/agentscope-extensions-polaris-example-mcp-server-spring-ai-polaris-0.1.0-RC1.jar
```

默认端口 `48082`、传输 `STREAMABLE`、端点 `/mcp`。可设置 `MCP_SERVER_PORT`、`MCP_PROTOCOL=SSE|STREAMABLE|STATELESS`、`MCP_INSTANCE_ID`。默认无需设置 `MCP_ADVERTISE_HOST`：Spring AI Polaris 自动检测本机非回环地址，默认优先 IPv4；未指定注册端口时使用实际 Web Server 端口。

多网卡、VPN、容器或 NAT 场景下，自动检测到的本机地址可能不符合客户端的访问路径，可通过 `MCP_ADVERTISE_HOST` 或 `spring.ai.polaris.mcp.server.host` 显式覆盖。它是 MCP Server 的发布地址，与北极星地址分别配置。同机演示可指定 `127.0.0.1`。示例关闭服务契约上报，实例注册和发现仍然启用。

先启动此 Server，再启动上述客户端，即可发现相同服务名的工具。`serverInfo` 为无参数工具，可以直接传给普通 Java 示例执行。

## 真实北极星 E2E

普通测试使用受控 ConsumerAPI；真实 E2E 通过显式 `mcp-e2e` profile 启用，默认构建不访问远程北极星。需要 JDK 17+、Maven，以及可读写的 `default` 命名空间（或通过环境变量指定其他已有命名空间）。

```sh
export POLARIS_DISCOVERY_ADDRESS='<POLARIS_HOST>:8091'
export POLARIS_HTTP_ADDRESS='<POLARIS_HOST>:8090'
export POLARIS_NAMESPACE='default'
mvn -Pmcp-e2e -pl agentscope-extensions-polaris-example/mcp/mcp-server-spring-ai-polaris -am verify
```

测试自动构建并启动独立 JVM Server，使用随机服务名和本机随机端口注册到真实北极星。SSE 场景不提供发布地址，验证自动检测并通过该地址调用；HTTP 场景显式指定回环地址，保留覆盖配置的验证。覆盖 SSE、STREAMABLE、STATELESS、自定义 context-path/端点元数据、原生 Toolkit 调用、Spring Boot YAML 自动绑定 Agent、Agent 触发实际远程工具调用和会话释放。STREAMABLE 场景启动两个副本，验证重复调用固定副本，选中副本退出后重新发现与调用，以及新 Agent 会话连接剩余副本。测试模型只决定调用哪个工具，注册、发现、HTTP 通信和工具执行均使用真实实现，无需模型 API Key。

测试在 `finally` 中停止自己启动的进程，验证实例注销后删除本次创建的空服务。详细服务端日志、验收摘要、Failsafe 结果位于该模块 `target/e2e/` 和 `target/failsafe-reports/`。本次执行结果见仓库 `reports/` 中的 E2E 自测报告。
