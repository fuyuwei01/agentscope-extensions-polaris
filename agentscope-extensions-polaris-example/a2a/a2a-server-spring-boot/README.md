# A2A Spring Boot Server

基础示例直接使用官方 `agentscope-a2a-spring-boot-starter` 创建和管理 A2A server、
JSON-RPC 控制器和注册监听器。Polaris Starter 提供注册发现；示例仅添加天气、计算和时间工具。

默认在 HTTP 端口 `8888` 的 `/` 提供 A2A 0.3 JSON-RPC，AgentCard 地址为
`/.well-known/agent-card.json`。无需手工创建 `AgentScopeA2aServer` 或配置实例 IP，
官方 Starter 自动生成卡片 URL。默认不设置 `server.address`，HTTP 监听所有网卡；
若显式设置该属性，沿用官方 Starter 的监听和部署地址语义。

需要 HTTP+JSON 和 gRPC additional interfaces 时，参照独立进阶示例
[`a2a-server-additional-interfaces-spring-boot`](../a2a-server-additional-interfaces-spring-boot)。

## 构建

在仓库根目录执行：

```bash
mvn -pl agentscope-extensions-polaris-example/a2a/a2a-server-spring-boot -am package
```

产物：

```text
agentscope-extensions-polaris-example/a2a/a2a-server-spring-boot/target/agentscope-extensions-polaris-example-a2a-server-spring-boot-0.1.0-RC1.jar
```

## 启动并注册到北极星

在 JAR 所在目录执行：

```bash
export POLARIS_DISCOVERY_ADDRESS='<POLARIS_HOST>:8091'
export POLARIS_NAMESPACE='default'
export POLARIS_TOKEN=''
export A2A_SERVER_PORT='8888'
export A2A_AGENT_NAME='polaris-a2a-example-agent'
export A2A_MODEL_PROVIDER='echo-last-user-input'

java -jar agentscope-extensions-polaris-example-a2a-server-spring-boot-0.1.0-RC1.jar \
  --agentscope.polaris.skill.enabled=false
```

该命令使用离线回显模型并关闭 Skill 加载。默认模型仍是 `openai`，Skill 加载默认开启；
使用真实模型时按照 `application.yml` 配置模型名和鉴权参数。

北极星注册端口默认 `1`，可通过 `POLARIS_REGISTRY_PORT` 覆盖，例如设为 `18080`。
这是注册实例的端口，实际 JSON-RPC 调用地址以 AgentCard 为准；HTTP 监听端口通过
`A2A_SERVER_PORT` 设置。

## 本地验证

无需北极星或 LLM 服务时，在 JAR 所在目录执行：

```bash
A2A_MODEL_PROVIDER=echo-last-user-input java -jar \
  agentscope-extensions-polaris-example-a2a-server-spring-boot-0.1.0-RC1.jar \
  --agentscope.polaris.enabled=false --agentscope.polaris.skill.enabled=false
```

在另一终端检查卡片并发送消息：

```bash
curl -fsS http://127.0.0.1:8888/.well-known/agent-card.json

curl -fsS http://127.0.0.1:8888/ \
  -H 'Content-Type: application/json' \
  --data-binary '{"jsonrpc":"2.0","id":"test","method":"message/send","params":{"message":{"role":"user","kind":"message","parts":[{"kind":"text","text":"hello-polaris"}],"messageId":"test-message"}}}'
```

预期 `protocolVersion` 为 `0.3.0`、`preferredTransport` 为 `JSONRPC`，消息响应包含
`hello-polaris`。流式调用将 `method` 改为 `message/stream`，并使用 `curl -N`。
官方 Starter 也会将默认 JSON-RPC 接口列入 `additionalInterfaces`；该示例没有额外的
HTTP+JSON 路由或 gRPC 监听。

## 注册发现检查

在 `POLARIS_NAMESPACE` 中查找 `A2A_AGENT_NAME` 对应服务，预期只注册一个实例：

- host 为官方 Starter 自动获取的实例 IP，port 默认 `1`，protocol 为空。
- 元数据为 `ai-agent-name`、`ai-agent-card`、`ai-a2a-version`；协议版本为 `0.3.0`。
- `ai-agent-card` 保存完整卡片，客户端使用其中的 JSON-RPC URL 调用。

## 自动验证

```bash
mvn -pl agentscope-extensions-polaris-example/a2a/a2a-server-spring-boot -am test
```

`StarterRegistrationTest` 验证官方 Starter 创建唯一 server、自动生成 JSON-RPC URL，
以及北极星注册默认端口、端口覆盖、卡片发现和关闭 A2A server 的行为。
