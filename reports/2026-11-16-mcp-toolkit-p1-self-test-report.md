# MCP Toolkit 共享连接 P1 修复自测报告

## 验证结论

符合本次 P1 修复的预期。释放一个 Toolkit 副本只清理该副本，其他使用者仍可调用 MCP 工具；最后一个使用者释放后关闭连接。原始 Toolkit 也是使用者，单例 Toolkit 保持可用并能继续创建 Agent，不再使用时需显式 release。

实际验证时间：2026-11-16 01:30:32–01:33:59（Asia/Shanghai）。分支：`codex/mcp-polaris-discovery`；HEAD：`81eebfecdd189759cd074cb5c3e188ce4a26cc5e`，包含未提交改动，以 [源码 SHA-256 快照](2026-11-16-mcp-toolkit-p1-source-snapshot.json) 定位本次实现。

环境：OpenJDK 25.0.2、Maven 3.9.9、AgentScope 1.0.12。最终执行相关 reactor 的 184 项测试，0 失败、0 错误、0 跳过。其中 MCP 自动装配 15 项、真实 HTTP 通信 6 项。没有生成本次覆盖率数据。

## 需求与复测场景

- **R1 / T1：副本释放隔离，通过。** `releasingOneCopyPreservesOtherOwnersAndClosesAfterTheLastRelease`：绑定原始 Toolkit，保留两个副本，释放第一个。该副本不再有 lookup，原始和第二个副本实际调用 lookup 都返回 `ok`，底层客户端关闭次数为 0。重复 retain/release 不增加使用权或重复关闭。
- **R2 / T2：最后一个使用者释放，通过。** T1 继续释放原始 Toolkit，第二个副本仍返回 `ok`；释放第二个后关闭次数为 1，工厂 shutdown 不重复关闭底层客户端。
- **R3 / T3：Spring 单例 Toolkit，通过。** `singletonToolkitCopiesRemainUsableWhenAnotherAgentIsReleased`：两个实际 ReActAgent 共享同一个原始 Toolkit，释放第一个后第二个调用仍返回 `ok`，新建第三个 Agent 也返回 `ok`；始终只创建 1 个客户端。释放全部 Agent 后原始 Toolkit 仍持有连接，显式释放原始 Toolkit 后关闭 1 次。
- **R4 / T4：原始 Toolkit 重新绑定，通过。** `releaseAllowsTheOriginalToolkitToBeBoundAgain` 和 `rebindingReleasedOriginalDoesNotLetOldCopiesCloseItsNewClient`：原始 Toolkit 显式释放后可重新绑定；新连接和旧副本均可调用，释放旧副本只关闭旧连接，新连接仍返回 `ok`。
- **R5 / T5：并发释放，通过。** `concurrentReleasesPreserveTheRemainingOwner`：同时释放原始和两个副本，保留的第三个副本仍返回 `ok`，连接未关闭；释放第三个后关闭 1 次。
- **R6 / T6：相关功能回归，通过。** core、MCP、A2A、skill、Spring Boot starter 的测试均通过。MCP HTTP 测试覆盖 SSE / STREAMABLE / STATELESS 的同步与异步客户端，共 6 项。

## 公共准备与执行

在 `$REPO_ROOT` 执行，需要项目依赖、Java 和 Maven，HTTP 集成测试需允许绑定本机随机端口。为适配当前 JDK 环境，本次使用 Mockito Java agent，覆盖了 JaCoCo 的 argLine。

```sh
cd "$REPO_ROOT"
export MOCKITO_AGENT='<absolute-path-to-mockito-core-5.23.0.jar>'
mvn -pl agentscope-extensions-polaris-spring-boot-starter -am test \
  "-DargLine=-javaagent:$MOCKITO_AGENT"
```

单独复测 T1–T5，在上面命令追加 `-Dtest=PolarisMcpAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`。本次依赖已缓存，实际使用 `-o` 和临时 Maven settings，未修改用户 Maven 设置。测试通过 try-with-resources / Spring context 关闭工厂和本机服务，无需手动注册或清理北极星测试服务。

## 证据与验证边界

修复前先执行新增回归：14 项测试中 4 项失败，实际出现 `Error: Tool not found: lookup`；修复后全部通过。第一次扩大回归受到沙箱 socket 权限限制，6 项 HTTP 测试报 `Operation not permitted`，经执行权限批准后重跑成功。[执行摘要](2026-11-16-mcp-toolkit-p1-verification.txt) 保留修复前后测试结果和真实时间。

本次 HTTP 测试使用真实本机 MCP Server，服务发现使用测试替身；没有重跑远程北极星端到端测试。原始 Toolkit 被垃圾回收后的定时清理与并发 retain/release 的全部交错未单独验证。其他 P2 评审项不在本次修改范围。没有提交或推送代码。
