# Harness Agent 集成示例

同一个真实 `HarnessAgent` 通过北极星获得三种能力：

- **MCP**：配置服务名，通过实例元数据找到端点，再向 MCP Server 拉取工具列表并绑定 Toolkit。
- **Skill**：读取北极星中挂载到当前 Agent 服务的 Skill，通过 Harness 的 `skillRepository(...)` 注入。
- **A2A**：官方 Starter 将本 Agent 注册到北极星；`ask_agent` 工具通过北极星发现另一个 Agent，并进行真实 A2A 通信。

默认模型为 `integration-mock`，不用模型 Key。`/mcp`、`/skill`、`/a2a` 都经过 Agent 的实际 tool calling 流程。换成 `openai` 后可以用自然语言调用同一套工具。

## 版本与构建

项目默认 AgentScope 版本是 `1.0.12`；这个示例使用本地 Harness 源码构建的 `agentscope-harness` / `agentscope-core` **`1.1.0-SNAPSHOT`**。仅在本模块覆盖 core，现有扩展和 A2A Starter 保持默认版本，并通过集成测试检查兼容性。示例聚合 POM（`agentscope-extensions-polaris-example/pom.xml`）通过 `harness-example` profile 声明 `harness` 子模块；默认 reactor 不包含该示例，启用 profile 后才加入。

先从相匹配的 AgentScope Java 源码将 snapshot 安装到本地 Maven 仓库：

```bash
cd '<AGENTSCOPE_JAVA_SOURCE_ROOT>'
mvn -pl agentscope-harness -am install -DskipTests
```

源仓库的实际 revision 必须为 `1.1.0-SNAPSHOT`。此示例依赖该版 API，不能直接将 core 换成 `1.0.12`。当前项目内构建命令：

```bash
mvn -Pharness-example -pl agentscope-extensions-polaris-example/harness -am package
```

产物：`target/agentscope-extensions-polaris-example-harness-0.1.0-RC1.jar`。

## 准备北极星、MCP Server 与 Skill

在仓库根目录设置公共环境变量，与其他 demo 使用相同名称：

```bash
export POLARIS_DISCOVERY_ADDRESS='<POLARIS_HOST>:8091'
export POLARIS_SKILL_ADDRESS='<POLARIS_HOST>:8094'
export POLARIS_NAMESPACE='default'
export POLARIS_TOKEN=''
export MCP_SERVICE_NAME='business-mcp-server'
export A2A_AGENT_NAME='polaris-harness-agent'
export A2A_SERVER_PORT='8889'
```

`POLARIS_SKILL_ADDRESS` 可以不设置：默认使用发现地址的主机和 `8094`。发现地址也兼容 `POLARIS_ADDRESS`。

1. 启动现有 [Spring AI Polaris MCP Server demo](../mcp/README.md)。使用相同的 `POLARIS_DISCOVERY_ADDRESS`、namespace 和 `MCP_SERVICE_NAME`，默认在 `48082` 提供 `add`、`echo`、`serverInfo`。
2. 在北极星发布一个 Skill。可以将本目录的 `sample-skill/SKILL.md` 打包为 ZIP 后上传，示例技能名为 `polaris-integration-guide`。
3. 将 Skill 挂载到 `A2A_AGENT_NAME` 对应的北极星服务。服务尚不存在时可先启动本应用，完成挂载后下一次 A2A 请求重新读取目录（受仓库默认缓存刷新周期约束）。也可以设置 `POLARIS_SKILL_MOUNTED=false`，从 namespace 的已发布 Skill 列表加载。

MCP 服务没有健康实例时绑定会失败，应用不会启动。没有挂载 Skill 时仍可执行 MCP 与 A2A 调用，`/skill-list` 显示没有技能。此应用不会发布 Skill 或注册 MCP Server。

## 启动与调用

在仓库根目录执行：

```bash
java -jar agentscope-extensions-polaris-example/harness/target/agentscope-extensions-polaris-example-harness-0.1.0-RC1.jar
```

默认 JSON-RPC 地址是 `http://127.0.0.1:8889/`，AgentCard 地址是 `/.well-known/agent-card.json`。官方 Starter 自动选择注册地址的 IP，跨主机客户端必须能访问 AgentCard 内的地址；可用 `agentscope.a2a.server.card.url` 显式覆盖可访问的外部 URL。

另一个终端使用本目录的 `ask.sh`（需要 Python 3 和 curl）：

```bash
./agentscope-extensions-polaris-example/harness/ask.sh '/mcp add {"a":19,"b":23}'
./agentscope-extensions-polaris-example/harness/ask.sh '/skill-list'
./agentscope-extensions-polaris-example/harness/ask.sh '/skill polaris-integration-guide'
./agentscope-extensions-polaris-example/harness/ask.sh '/a2a polaris-harness-agent /mcp add {"a":20,"b":22}'
```

预期依次得到包含 `42` 的 A2A 响应、Skill 目录、真实 `SKILL.md` 内容，以及通过服务名重新发现本 Agent 后嵌套执行 MCP 的 `42`。可把最后一条的服务名替换为另一个已注册 Agent。`ask.sh` 返回完整 JSON-RPC 响应，服务端日志显示真实工具调用。

`ask.sh` 的入口 HTTP URL 用于访问当前应用；其中 `/a2a` 指令的远端 Agent URL 来自北极星，没有在脚本中配置。脚本默认使用 `A2A_SERVER_PORT`，跨主机访问当前应用时可设置 `A2A_BASE_URL`。

使用真实模型：

```bash
export A2A_MODEL_PROVIDER='openai'
export TOKEN_HUB_API_KEY='<MODEL_API_KEY>'
export TOKEN_HUB_BASE_URL='<OPENAI_COMPATIBLE_BASE_URL>'
export OPENAI_MODEL='<MODEL_NAME>'
java -jar agentscope-extensions-polaris-example/harness/target/agentscope-extensions-polaris-example-harness-0.1.0-RC1.jar
```

## 接线与生命周期

`PolarisHarnessApplication` 只声明 Model、原始 Toolkit 和 AgentRunner；MCP、Skill 仓库、A2A 注册发现使用已有 Polaris Starter。`RemoteAgentTools` 注册 `ask_agent`，按服务名使用 `AgentCardResolver` 创建 `A2aAgent`。

`PolarisHarnessAgentRunner` 每个 A2A 请求创建一个 HarnessAgent，独立 Toolkit、内存和 workspace。原始单例 Toolkit 保留 MCP 连接，每请求 retain delegate 的 Toolkit，结束或取消后只 release 这个副本，应用退出时由 factory 关闭连接。A2A 请求携带的历史消息用于当前推理，这个示例不提供跨请求的服务端持久化会话。

该 snapshot 的动态 Skill Hook 注册目标与 delegate 的 Toolkit 副本不一致，因此示例显式调用 `disableDynamicSkills()`：每请求构建时读取仓库并静态绑定到真正的 delegate Toolkit。刷新粒度为请求，仓库仍使用自己的列表与 ZIP 缓存。每请求 workspace 保留在 `HARNESS_WORKSPACE` 根目录下（默认系统临时目录），方便查看文件，示例不自动清理历史目录。

## 自动验证

```bash
mvn -Pharness-example -pl agentscope-extensions-polaris-example/harness -am verify
```

`HarnessIntegrationTest` 启动真实本机 MCP Server 和 Spring Boot A2A Server，使用真实 HarnessAgent，验证注册卡片回读、MCP 调用、Skill 目录/ZIP 加载、A2A 委派嵌套调用，以及前一次请求释放后的再次调用和关闭注销。同时覆盖挂载 Skill 和 namespace 已发布 Skill 两种来源。仅北极星 ProviderAPI、ConsumerAPI、SkillAPI 使用测试替身，不需要外部北极星或模型 Key。
