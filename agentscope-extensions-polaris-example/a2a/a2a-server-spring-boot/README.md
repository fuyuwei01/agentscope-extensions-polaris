# A2A Spring Boot Server

原有 Spring Boot 服务端同时提供 A2A 0.3 的三个协议，使用同一个 Agent、请求处理器和任务存储：

- JSON-RPC 主接口：`http://<INSTANCE_IP>:8888/`。
- HTTP+JSON additional interface：`http://<INSTANCE_IP>:8888/a2a/rest`。
- gRPC additional interface：`http://<INSTANCE_IP>:8889`，使用明文 HTTP/2。

`additionalInterfaces` 仅包含 HTTP+JSON 和 gRPC 两项。JSON-RPC 主接口由 `url` 和
`preferredTransport: JSONRPC` 表示。没有额外的 JSON-RPC 路径别名，也没有独立的多接口 demo 模块。

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

无需配置实例 IP。应用复用 AgentScope 的网络工具，自动获取本机非回环 IPv4 地址，
用于 JSON-RPC、HTTP+JSON、gRPC 的 AgentCard URL 和北极星注册 host。HTTP 和 gRPC
默认监听所有网卡；`server.address` 仅用于限制监听地址，不用于生成上报 IP。

```bash
export A2A_SERVER_PORT='8888'
export A2A_GRPC_PORT='8889'
export A2A_AGENT_NAME='polaris-a2a-example-agent'
export POLARIS_DISCOVERY_ADDRESS='<POLARIS_HOST>:8091'
export POLARIS_NAMESPACE='default'
export POLARIS_TOKEN=''
export POLARIS_REGISTRY_PORT='18080'
export A2A_MODEL_PROVIDER='echo-last-user-input'

java -jar agentscope-extensions-polaris-example-a2a-server-spring-boot-0.1.0-RC1.jar \
  --agentscope.polaris.skill.enabled=false
```

HTTP 监听端口由 `A2A_SERVER_PORT` 设置，默认 `8888`。gRPC 监听端口由 `A2A_GRPC_PORT`
设置，默认 `8889`，也可以使用 `--agentscope.a2a.server.grpc.port=8889`。两个监听端口应不同。
应用先启动 HTTP 和 gRPC，再注册 AgentCard；退出时关闭两个监听服务并注销实例。

北极星注册端口由 `POLARIS_REGISTRY_PORT` 设置，与实际调用端口独立，应用不会为它启动监听。
注册默认值仍是 `0`；当前 Polaris Java SDK `2.2.0.0-RC1` 会拒绝 `0`，测试时必须配置非零值。

原示例默认模型仍是 `openai`，Skill 加载默认开启。上述命令选择离线回显模型并关闭 Skill 加载，
不需要 LLM API Key 或 Skill 服务。使用真实模型时按 `application.yml` 配置模型名和鉴权参数。

## 仅本地验证

不连接北极星时，在 JAR 所在目录执行：

```bash
A2A_MODEL_PROVIDER=echo-last-user-input java -jar \
  agentscope-extensions-polaris-example-a2a-server-spring-boot-0.1.0-RC1.jar \
  --agentscope.polaris.enabled=false --agentscope.polaris.skill.enabled=false

# 在另一终端设置请求地址：
export TEST_SERVER_HOST='127.0.0.1'
export A2A_SERVER_PORT='8888'
export A2A_GRPC_PORT='8889'
```

## 检查 AgentCard

```bash
curl -fsS "http://${TEST_SERVER_HOST}:${A2A_SERVER_PORT}/.well-known/agent-card.json"
```

`TEST_SERVER_HOST` 是请求命令使用的目标地址，仅在客户端终端设置；远程测试时替换为
AgentCard 中自动生成的实例 IP。

预期 `protocolVersion` 为 `0.3.0`，主接口 `url` 以 `/` 结尾，`preferredTransport` 为 `JSONRPC`。
`additionalInterfaces` 为以下两项，端口分别取自 HTTP 和 gRPC 的实际监听设置：

```json
[
  {"transport":"HTTP+JSON","url":"http://<INSTANCE_IP>:8888/a2a/rest"},
  {"transport":"GRPC","url":"http://<INSTANCE_IP>:8889"}
]
```

## JSON-RPC 调用

```bash
curl -fsS "http://${TEST_SERVER_HOST}:${A2A_SERVER_PORT}/" \
  -H 'Content-Type: application/json' \
  --data-binary '{"jsonrpc":"2.0","id":"jsonrpc-test","method":"message/send","params":{"message":{"role":"user","kind":"message","parts":[{"kind":"text","text":"hello-polaris"}],"messageId":"jsonrpc-message"}}}'
```

预期 `result` 包含回显 `hello-polaris`。流式调用把 `method` 改为 `message/stream`，并使用 `curl -N`。

## HTTP+JSON 调用

HTTP+JSON 使用 SDK 的 Protobuf JSON 格式：外层字段是 `request`，用户角色为 `ROLE_USER`，
文本 part 直接使用 `text`。不包含 JSON-RPC 的 `jsonrpc`、`id`、`method` 和 `params`。

```bash
curl -fsS "http://${TEST_SERVER_HOST}:${A2A_SERVER_PORT}/a2a/rest/v1/message:send" \
  -H 'Content-Type: application/json' \
  --data-binary '{"request":{"messageId":"rest-message","role":"ROLE_USER","content":[{"text":"hello-polaris"}]}}'

curl -N --max-time 30 "http://${TEST_SERVER_HOST}:${A2A_SERVER_PORT}/a2a/rest/v1/message:stream" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  --data-binary '{"request":{"messageId":"rest-stream-message","role":"ROLE_USER","content":[{"text":"hello-polaris"}]}}'
```

普通响应和 SSE 事件均应包含 `hello-polaris`。任务查询使用
`GET /a2a/rest/v1/tasks/<TASK_ID>?historyLength=1`，取消使用
`POST /a2a/rest/v1/tasks/<TASK_ID>:cancel`，重新订阅使用
`POST /a2a/rest/v1/tasks/<TASK_ID>:subscribe`。

## gRPC 调用

服务端开启 gRPC reflection，可直接使用已安装的 `grpcurl`，无需手动准备 proto：

```bash
grpcurl -plaintext "${TEST_SERVER_HOST}:${A2A_GRPC_PORT}" list

grpcurl -plaintext -d '{}' "${TEST_SERVER_HOST}:${A2A_GRPC_PORT}" a2a.v1.A2AService/GetAgentCard

grpcurl -plaintext \
  -d '{"request":{"messageId":"grpc-message","role":"ROLE_USER","content":[{"text":"hello-polaris"}]}}' \
  "${TEST_SERVER_HOST}:${A2A_GRPC_PORT}" a2a.v1.A2AService/SendMessage

grpcurl -plaintext \
  -d '{"request":{"messageId":"grpc-stream-message","role":"ROLE_USER","content":[{"text":"hello-polaris"}]}}' \
  "${TEST_SERVER_HOST}:${A2A_GRPC_PORT}" a2a.v1.A2AService/SendStreamingMessage
```

预期普通响应和流式事件均包含 `hello-polaris`。gRPC 任务查询方法是 `a2a.v1.A2AService/GetTask`，
请求示例为 `{"name":"tasks/<TASK_ID>"}`。通过任何协议创建的流式任务可用 HTTP+JSON 或 gRPC 查询。

## 注册发现检查

在 `POLARIS_NAMESPACE` 中查找 `A2A_AGENT_NAME` 对应服务，预期只注册一个实例：

- host 为自动获取的实例 IP（与三个 AgentCard URL 中的 host 相同），port 为 `POLARIS_REGISTRY_PORT`，protocol 为空。
- 元数据只有 `ai-agent-name`、`ai-agent-card`、`ai-a2a-version`；协议版本为 `0.3.0`。
- `ai-agent-card` 保留 JSON-RPC 主 URL 和两个 additional interface 的实际调用地址。

现有 Spring Boot 客户端仍默认使用 JSON-RPC。HTTP+JSON 或 gRPC 客户端需要添加对应 SDK transport
依赖并配置该 transport；仅收到卡片不会使现有客户端自动获得新协议能力。

## 自动验证

```bash
mvn -pl agentscope-extensions-polaris-example/a2a/a2a-server-spring-boot -am test
```

`MultiTransportIntegrationTest` 启动真实 HTTP/gRPC 服务，验证三种协议的普通和流式请求、跨协议任务查询、
客户端通过自动生成的实例地址调用三种协议、从 additionalInterfaces 选择 HTTP+JSON/gRPC、
错误请求和监听端口释放。
`TransportRegistrationTest` 使用模拟 Polaris API 和固定的自动探测 IP，验证无需配置
`server.address`，或者把它设为 `0.0.0.0` / `127.0.0.1` 时，均上报自动探测的实例 IP；
同时验证只有两个 additional interface、只注册一个实例，以及元数据和完整卡片的注册发现。真实北极星后端注册与注销需要在测试环境复测。
