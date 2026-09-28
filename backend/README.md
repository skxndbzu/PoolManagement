# PoolGuard Java 后端

## 两种运行模式

- `demo`：H2 内存数据库、内存登录会话、模拟项目适配器。不会访问真实项目；每次重启清空数据。
- `live`（或默认 profile）：PostgreSQL + Redis，两个真实管理 API 适配器。账号同步、调度开关已实现；自定义题目检测仍受目标项目接口限制，会记录 ERROR 而不修改远端调度开关。

Java 服务同时提供页面和 API。构建会把根目录的 `index.html`、`app.js`、`styles.css` 打入 JAR；修改页面后需重新构建。

```powershell
$env:JAVA_HOME = 'D:\ma_nong\java\jdk17'
mvn package
& "$env:JAVA_HOME/bin/java.exe" -jar target/poolguard-control-plane-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo
```

浏览器访问 <http://127.0.0.1:8090>，默认 `admin / pool-admin`。

## 复用 PostgreSQL / Redis

在已有 PostgreSQL 实例上创建独立数据库和账号 `poolguard`，授予该库内建表和迁移权限。Flyway 在该库运行 V1、V2。不要使用任一目标项目的业务数据库。V1 曾包含固定演示账号，V2 将它们标记为不参与监测，并停用旧的含糊评分题目；保留旧检测历史。

Redis 可以复用已有实例，建议指定独立 logical database。PoolGuard 会话 key 使用 `poolguard:session:` 前缀。

```powershell
$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:5432/poolguard'
$env:DATABASE_USERNAME = 'poolguard'
$env:DATABASE_PASSWORD = '<数据库密码>'
$env:REDIS_URL = 'redis://127.0.0.1:6379/3'
$env:POOLGUARD_ADMIN_USERNAME = 'admin'
$env:POOLGUARD_ADMIN_PASSWORD = '<管理员密码>'
$env:SUB2API_BASE_URL = 'http://127.0.0.1:8081'
$env:SUB2API_ADMIN_API_KEY = '<sub2api 管理 API Key>'
$env:SUB2API_TEST_MODEL = 'gpt-5.2'
$env:CODEX_PROXY_BASE_URL = 'http://127.0.0.1:8080'
$env:CODEX_PROXY_ADMIN_API_KEY = '<codex-proxy-rs 管理 API Key>'
$env:CODEX_PROXY_TEST_MODEL = 'gpt-5.2'
..\Start-PoolGuard.ps1 -Mode live -JavaHome 'D:\ma_nong\java\jdk17'
```

两个 `*_INTERNAL_TOKEN` 旧变量仍兼容；新的 `*_ADMIN_API_KEY` 优先。密钥只在服务端配置，不发往浏览器、不写入账号记录。
没有管理密钥的项目不发起远端请求。当前默认仅同步 OpenAI 平台账号，其他 Provider 尚未适配。

如果没有现成中间件，可使用 `docker compose up -d`；Compose 仅供本地开发并绑定环回地址，使用默认开发密码。复用已有服务时无需运行 Compose。

## 检测、隔离与调度

- 默认每 5 分钟检测一次；扫描器每 30 秒读取 `next_run_at`。
- PostgreSQL 会话级 advisory lock 覆盖完整批次，多实例、手工复检、账号操作、策略/设置修改共用该锁。连接断开会释放锁；执行前检查锁连接状态。直接连接 PostgreSQL 或使用 session pooling，不能使用 PgBouncer transaction pooling。
- 同一时刻最多一个批次，账号逐个检测。大号池的执行时间可能超过设定周期，不会叠加重复批次；需要按真实规模进一步增加受控账号并发。
- 成功同步的项目才进入当次检测，完整目录中消失的账号不再监测；同步失败保留旧目录并报告错误。
- 全部启用题目通过：正常账号保留；自动隔离账号累计连续通过次数，达到配置阈值才恢复调度。
- 答错：标记 `DEGRADED`，隔离并继续复检。恢复观察 `RECOVERING` 期间仍然隔离。
- 超时、鉴权失败、接口不支持：记录 `ERROR`，不作为答错、不触发隔离，连续通过计数清零。
- 手工停用：`DISABLED` 且停止监测；只能手工恢复。源项目同步时已停用的账号不取得自动恢复所有权。
- 远端状态回写失败不会伪装为成功；保留失败原因并让批次显示异常。
- 当前跨系统没有原子事务或远端所有权标记。同一账号在源项目被管理员并发改动时无法完整辨认操作归属，正式自动化上线前需扩展幂等/版本条件与隔离所有权协议。

新进程下次拿到任务锁时会将上次遗留的 RUNNING 批次标记中断，下轮重新检测。生产数据库保存结果，异步任务本身不是持久队列。

## 判分规则

标准答案默认精确匹配，先做 Unicode NFKC 归一化、大小写归一化及空白折叠。关键词模式：

```text
keywords:
P95
P99
锁竞争
```

每一行非空关键词都必须出现在回答中。没有正则/脚本执行或隐含 LLM 评分。**只给上游发送问题，不发送标准答案。** 题目和标准答案快照保存在逐题记录中；编辑或删除策略不应改变旧记录。

关键词出现不等于论证正确，题集结果也无法证明上游实际模型身份。建议使用结果明确、可复核的题目，并根据样本误判率调整题集。

## 管控台 API

除了登录、系统模式说明、静态页面和健康检查，其余接口需要 `Authorization: Bearer <token>`。

| 功能 | 接口 |
| --- | --- |
| 登录 / 退出 | `POST /api/auth/login`、`POST /api/auth/logout` |
| 系统模式及项目能力 | `GET /api/system` |
| 概览 | `GET /api/overview` |
| 账号列表 | `GET /api/accounts?project=&search=` |
| 同步目录 | `POST /api/accounts/sync` |
| 账号动作 | `POST /api/accounts/{id}/actions`，`action` 为 `restore` / `retest` / `disable` |
| 全量检测 | `POST /api/detection-runs` 或 `POST /api/accounts/check` |
| 最近 20 个批次 | `GET /api/detection-runs` |
| 最新批次 | `GET /api/detection-runs/latest` |
| 逐题历史 | `GET /api/detection-runs/{id}/results` |
| 策略列表 / 新增 | `GET/POST /api/check-policies` |
| 整批保存策略 | `PUT /api/check-policies`，`[{id?, policy:{title,answer,model,active}}]`，省略旧 ID 表示删除 |
| 单题编辑 / 删除 | `PUT/DELETE /api/check-policies/{id}` |
| 调度设置 | `GET/PATCH /api/settings` |
| 健康检查 | `GET /actuator/health` |

`model` 在策略对象中是沿用旧 Demo 的“能力标签”，不是实际目标模型。实际目标模型通过上面的项目配置指定。`retest` 返回异步批次；其他账号动作返回账号视图。

## 目标项目协议与接入缺口

| 项目 | 查询 / 调度 | 自定义答题 |
| --- | --- | --- |
| sub2api | `GET /api/v1/admin/accounts`；`POST /api/v1/admin/accounts/{id}/schedulable` | 当前部分测试分支忽略 `prompt`，尚未接入 |
| codex-proxy-rs | `GET /api/admin/accounts`；`POST /api/admin/accounts/batch-update`，只提交 `accountIds` 和 `enabled`；详情回读确认 | `connection-test` 固定题目，尚未接入 |

两者使用 `x-api-key`，响应业务码分别为 0 与 200。Java 接口 `ProjectAdapter.ask(externalId, model, question)` 是尚待实现的真实答题入口；必须精确指定账号、允许隔离状态下探测、返回实际完整回答且不触发自动恢复。不能直接复用普通轮询路由或把 SSE 的连接成功作为答题正确。

本轮没有修改两个目标项目。codex-proxy-rs 的 CONTRIBUTING.md 要求 Rust 开发使用 rust-best-practices 技能，但当前本机未找到，因此未开展 Rust 接口扩展。

## 验证

`mvn test` 覆盖判分边界、管理 API 协议、登录/退出/配置/策略接口、模拟隔离恢复流程、异常保护和并发互斥。H2 及 HTTP 模拟服务器测试不替代 PostgreSQL Flyway 迁移、Redis 和两个真实项目的端到端联调；当前环境没有这些服务，后者尚未验证。
