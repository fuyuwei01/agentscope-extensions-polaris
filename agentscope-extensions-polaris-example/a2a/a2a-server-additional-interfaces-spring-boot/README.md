# A2A Spring Boot Server with Additional Interfaces

这个示例基于 `a2a-server-spring-boot`，默认使用离线回显模型。一个 Agent 暴露四个 JSON-RPC URL：

- `/`：主接口。
- `/a2a/jsonrpc`：附加接口。
- `/a2a/internal`：附加接口。
- `/a2a/external`：附加接口。

四个接口使用同一 IP 和 HTTP 端口，都能处理 A2A 0.3 的普通 JSON-RPC 和 SSE 请求。
`internal`、`external` 是路径别名，不带访问控制差异。

## 构建

在项目根目录执行：

```bash
mvn -pl agentscope-extensions-polaris-example/a2a/a2a-server-additional-interfaces-spring-boot -am package
```

产物：

```text
agentscope-extensions-polaris-example/a2a/a2a-server-additional-interfaces-spring-boot/target/agentscope-extensions-polaris-example-a2a-server-additional-interfaces-spring-boot-0.1.0-RC1.jar
```

## 注册到北极星

在上传目录运行，修改发现服务地址、命名空间和服务端本机 IP。`A2A_SERVER_HOST` 同时用于监听地址和卡片 URL，必须是本机可绑定、客户端可访问的 IP。

```bash
export POLARIS_DISCOVERY_ADDRESS='10.0.0.10:8091'
export POLARIS_NAMESPACE='default'
export POLARIS_TOKEN=''
export POLARIS_REGISTRY_PORT='18080'
export A2A_SERVER_HOST='10.0.0.20'
export A2A_SERVER_PORT='8890'
export A2A_AGENT_NAME='polaris-a2a-additional-interfaces-agent'

java -jar agentscope-extensions-polaris-example-a2a-server-additional-interfaces-spring-boot-0.1.0-RC1.jar
```

默认模型是 `echo-last-user-input`，Skill 加载关闭。可用 `A2A_MODEL_PROVIDER` 切换模型；选择 `openai` 时配置对应 API Key、Base URL 和模型名。

HTTP 端口默认 `8890`。注册端口默认 `18080`，用于北极星实例身份和注销，应用不会额外监听它。
当前 Polaris Java SDK 拒绝注册端口 `0`，因此此示例默认采用非零值。

## 检查 AgentCard 和请求

以下命令在服务端机器执行：

```bash
curl -fsS "http://${A2A_SERVER_HOST}:${A2A_SERVER_PORT}/.well-known/agent-card.json"
```

预期 `protocolVersion` 为 `0.3.0`，`preferredTransport` 为 `JSONRPC`，主 `url` 以 `/` 结尾。
`additionalInterfaces` 包含主接口和三个别名，共四项。列表通过配置项
`agentscope.a2a.server.card.additional-interfaces` 声明，每项使用 A2A 0.3 的 `transport`、`url` 字段。
新增声明时需要同时实现对应路由；卡片声明本身不会创建监听服务或 HTTP 路由。

```bash
for path in / /a2a/jsonrpc /a2a/internal /a2a/external; do
  curl -N --max-time 30 "http://${A2A_SERVER_HOST}:${A2A_SERVER_PORT}${path}" \
    -H 'Content-Type: application/json' \
    -H 'Accept: text/event-stream' \
    --data-binary "{\"jsonrpc\":\"2.0\",\"id\":\"test-${path}\",\"method\":\"message/stream\",\"params\":{\"message\":{\"role\":\"user\",\"kind\":\"message\",\"parts\":[{\"kind\":\"text\",\"text\":\"hello-polaris\"}],\"messageId\":\"message-${path}\"}}}"
done
```

每个接口的 SSE 响应都应包含远端回显 `hello-polaris`。

## 北极星检查点

找到 `polaris-a2a-additional-interfaces-agent` 服务，预期本次启动只注册一个实例：

- host 为 `A2A_SERVER_HOST`，注册 port 为 `POLARIS_REGISTRY_PORT`，protocol 为空。
- `ai-agent-name` 为 Agent 名字，`ai-a2a-version` 为 `0.3.0`。
- `ai-agent-card` 保存完整卡片，四个 `additionalInterfaces` URL 均保留实际 HTTP 端口。

现有 Spring Boot 客户端可通过 `A2A_AGENT_NAME=polaris-a2a-additional-interfaces-agent` 发现并调用主接口。
按 `Ctrl+C` 正常退出服务端后，检查本次实例是否已注销。

## 仅验证本地 HTTP

不连接北极星时，可以使用默认的 `127.0.0.1:8890`：

```bash
java -jar agentscope-extensions-polaris-example-a2a-server-additional-interfaces-spring-boot-0.1.0-RC1.jar \
  --agentscope.polaris.enabled=false
```

注册与发现的自动化测试使用模拟 Polaris API，验证配置绑定、完整卡片的注册与发现、单实例注册和协议版本元数据。真实北极星注册及注销需在测试环境验证。
