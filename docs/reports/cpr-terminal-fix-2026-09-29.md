# CPR 答题终态修复记录：2026-09-29

## 问题核实

这是新增 PoolGuard 接口的实现缺陷。OpenAI Provider 已将 `response.incomplete` 映射为规范化 `Completed` 事件，并通过 `FinishReason::Length`、`ContentFilter` 或 `Other` 保留不完整原因。`Completed` 表示事件流到达终态，并不保证回答正常完成

原有 Core `AccountProbeResult` 只提取文本，未将结束原因返回；PoolGuard 用例仅检查文本非空及长度限制，API 随后固定输出 `completed: true`。因此非空的截断答案会进入判分，既可能误判为答错导致隔离，也可能恰好匹配标准答案导致错误恢复。此前测试没有覆盖这一组合，不能用首次测试通过的数量证明此处正确

## 修复

- `AccountProbeResult` 增加可选 `finish_reason`；Core 从规范化终态事件保留该字段，不更改 Provider 对事件的解释
- PoolGuard 用例仅接受明确的 `FinishReason::Stop`，并继续检查非空文本与长度限制
- 长度截断、内容过滤、工具调用、其他或缺失结束原因一律返回 HTTP 502，不进入答案匹配
- HTTP 成功分支中的 `completed: true` 由上述检查保证，不再仅凭收到文本推断；原有连通性探测调用方不被强制改为答题判分语义
- 更新可重放补丁、接口说明和官方更新合并说明；Core 的两个生产文件现在属于扩展需维护的范围

## 验证

WSL Ubuntu 24.04、Rust 1.97.0；Java 使用本机 JDK 21

| 范围 | 结果 |
| --- | --- |
| gateway-core 完整 main 测试 | 447 通过 |
| gateway-admin 完整 main 测试 | 253 通过 |
| gateway-api 完整 main 测试 | 459 通过 |
| provider-openai 中 incomplete 相关测试 | 7 通过，含 BPS 保留 incomplete、规范化原因及 WebSocket 转发 |
| PoolGuard AdapterContractTest、CprDetectionLifecycleTest | 4 通过 |
| 全工作区 Rustfmt 及严格 Clippy（all-targets、all-features、locked） | 通过 |
| 更新补丁在干净基线临时索引中应用及反向检查 | 通过 |

新增 Core 回归测试经过真实 `DefaultExecutionService` 探测链，使用提供规范化事件的测试 Provider，验证文本相同时各结束原因仍被保留。新增 Admin 回归测试确认即使文本恰好为 `408`，非正常终态也返回异常。Java HTTP 模拟测试验证非空文本加 `completed:false` 及 HTTP 502 都被拒绝

本轮没有运行真实 GPT 账号，也没有部署或重启服务器。测试不证明上游账号质量；修复后的 CPR 需重新构建部署才会影响运行中的检测。之前可能受错误判分影响的账号应重新检测并人工核对，不根据这些测试自动批量修改账号状态
