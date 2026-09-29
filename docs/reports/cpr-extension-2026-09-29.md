# CPR 扩展验证记录：2026-09-29

本记录是首次实现的验证快照，不覆盖随后发现的“非空文本但终态为 incomplete”缺陷。原先“完整回答”检查只覆盖超时、空文本和长度上限，不能证明上游正常完成；终态修复及补测见 [结束原因修复记录](cpr-terminal-fix-2026-09-29.md)。

## 验证对象

- CPR：`f4356d867044d1246c2c162f440a9df8bed06e67` 加本次本地扩展
- PoolGuard：`17064500156c53c1b2db79ba5244f9b2bf1b8727` 加本次回执校验及文档修改
- CPR 扩展补丁：[poolguard.patch](../../integrations/cpr/poolguard.patch)
- 接口合同：[项目管控协议 v1](../project-control-protocol.md)

CPR 新增两个管理路由，分别承担指定账号答题及带归属回执的隔离／恢复／查询。扩展共涉及 22 个文件，其中 8 个原有生产文件只增加模块声明、路由或端口接线，主要逻辑位于独立 `poolguard.rs`。没有改动 Core、Provider、前端、Cargo 依赖及数据库迁移；原有 `modules/plugins`、`modules/ui` 工作区差异未纳入补丁

PoolGuard 另增加回执完整性检查：缺少启停状态、控制版本，或 `isolated/restored/none` 与 `enabled` 矛盾时拒绝确认成功

## 实际结果

Rust 在本机 WSL Ubuntu 24.04、Rust 1.97.0 下执行。Windows MSVC 首次尝试因缺少 `link.exe` 无法编译，未将该次尝试算作通过。数据库测试使用 WSL 内独立 PostgreSQL 测试数据库和 Redis 测试实例，未连接远程业务库；结束后已移除本次测试库及角色并关闭专用 Redis 实例

| 检查 | 结果 |
| --- | --- |
| Rustfmt 全工作区检查 | 通过 |
| 严格 Clippy，all-targets、all-features、locked、warnings as errors | 通过 |
| gateway-admin `main` 测试 | 252 通过 |
| gateway-api `main` 测试 | 459 通过 |
| gateway-store `main` 测试，配置独立 PG/Redis | 321 通过 |
| gateway-core `main` 测试 | 446 通过 |
| provider-openai `main` 测试 | 836 通过 |
| PoolGuard Maven 测试，JDK 21 | 8 通过 |
| 补丁在原始 HEAD 临时索引中应用／反向检查 | 通过 |
| 安装脚本对当前 CPR 的重复检查 | 正确识别扩展已存在 |

Rust 合计 2,314 项；未把子进程重复执行的测试额外计数。未执行其余 Rust 包的完整运行测试，Clippy 覆盖工作区编译检查

主要命令（Rust 在 CPR 根目录，Java 在 PoolGuard 根目录）：

```bash
cargo +1.97.0 fmt --all --manifest-path backend/Cargo.toml -- --check
RUST_MIN_STACK=16777216 cargo +1.97.0 clippy --manifest-path backend/Cargo.toml --all-targets --all-features --locked -- -D warnings
# CPR_TEST_DATABASE_URL、CPR_TEST_REDIS_URL 指向独立测试实例
RUST_MIN_STACK=16777216 cargo +1.97.0 test --manifest-path backend/Cargo.toml -p gateway-admin -p gateway-api -p gateway-store --test main --locked
RUST_MIN_STACK=16777216 cargo +1.97.0 test --manifest-path backend/Cargo.toml -p gateway-core -p provider-openai --test main --locked
mvn -f backend/pom.xml test
```

## 关键行为覆盖

- Admin 测试使用模拟 AccountProbe，确认自定义题目及指定账号传入、不携带标准答案、停用账号可调用、完整回答被返回
- 空答案、上游失败、超长答案、非法题目及 45 秒超时返回异常，不当作正常答错
- API 测试验证管理认证、请求字段及拒绝额外标准答案字段
- 真实 PostgreSQL 事务测试验证重复隔离／恢复幂等、重新创建 Store 后回执可读、同值人工停用阻止恢复、旧版本拒绝及回执过期拒绝
- 两个并发隔离操作只能有一个拥有归属；其他操作不能恢复，跨账号复用操作 ID 被拒绝
- 隔离及恢复后比对账号完整数据库行，确认除 `enabled`、`updated_at` 外其他字段不变
- Java 模拟 HTTP／生命周期测试覆盖回执校验、答错隔离、答对恢复、响应丢失对账和人工覆盖

## 验证边界

未部署到 `64.32.22.221:8080`，未重启现有 CPR 或 PoolGuard，未调用真实 GPT 账号消耗额度。这些测试证明代码合同和本地事务行为，不能代替部署后的真实账号答题验收，也不能保证任何题库能够准确判定所谓“降智”

上线需重新构建部署 CPR 和 PoolGuard，配置 CPR 管理员 Key 与可用目标模型，再按协议中的验收清单跑真实账号。回执沿用 CPR 审计保留期；审计过期或无法精确归属的批量人工启停会保守地暂停自动管控，需要人工确认
