# PoolGuard Java 后端

## 两种运行模式

- `demo`：H2 内存数据库、内存登录会话、模拟项目适配器。不会访问真实项目；每次重启清空数据。
- `live`（或默认 profile）：PostgreSQL + Redis，两个真实管理 API 适配器。CPR 部署 [PoolGuard 扩展](../integrations/cpr/README.md)后支持自定义题目与自动隔离恢复；sub2api 仍记录能力检测不支持。

Java 服务同时提供页面和 API。构建会把根目录的 `index.html`、`app.js`、`styles.css` 打入 JAR；修改页面后需重新构建。

```powershell
$env:JAVA_HOME = 'D:\ma_nong\java\jdk17'
mvn package
& "$env:JAVA_HOME/bin/java.exe" -jar target/poolguard-control-plane-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo
```

浏览器访问 <http://127.0.0.1:8090>，默认 `admin / pool-admin`。

## 复用 PostgreSQL / Redis

在已有 PostgreSQL 实例上创建独立数据库和账号 `poolguard`，授予该库内建表和迁移权限。Flyway 在该库运行版本迁移（当前 V1–V9）。不要使用任一目标项目的业务数据库。迁移保留旧检测历史，并保存自动隔离回执、失败计数、题库版本指纹、账号检测模型配置、上游模型目录及答题覆盖进度。

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
- 答错：连续失败达到 `POOLGUARD_DISABLE_FAILURES`（默认 1）后自动隔离并标记 `DEGRADED`。手动复检答错一轮即隔离。阈值前只记录疑似降智；恢复观察 `RECOVERING` 期间仍然隔离。题目、答案或模型改变会重置连续次数。
- 超时、鉴权失败、接口不支持：记录 `ERROR`，不作为答错、不触发隔离，连续通过计数清零。
- 手工停用：`DISABLED` 且停止监测；只能手工恢复。源项目同步时已停用的账号不取得自动恢复所有权。
- 远端状态回写失败不会伪装为成功；保留失败原因并让批次显示异常。
- CPR 自动隔离在发送前持久化操作 UUID，远端启停与审计回执在同一事务提交。重启、请求超时后查询回执；人工启停导致归属冲突时暂停自动管控。回执遵循 CPR 审计保留期；粗粒度批量启停可能保守地使其他账号的回执失效。sub2api 的普通启停没有此协议，自动答题仍未开放。

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
| 筛选与分页 | `GET /api/detection-runs/search` |
| 最新批次 | `GET /api/detection-runs/latest` |
| 逐题历史 | `GET /api/detection-runs/{id}/results` |
| 策略列表 / 新增 | `GET/POST /api/check-policies` |
| 整批保存策略 | `PUT /api/check-policies`，`[{id?, policy:{title,answer,model,active}}]`，省略旧 ID 表示删除 |
| 单题编辑 / 删除 | `PUT/DELETE /api/check-policies/{id}` |
| 调度设置 | `GET/PATCH /api/settings` |
| 健康检查 | `GET /actuator/health` |

`model` 在策略对象中是沿用旧 Demo 的“能力标签”，不是实际目标模型。实际检测模型在账号池的“配置检测模型”中逐账号设置，未配置时使用项目默认模型。`retest` 返回异步批次；其他账号动作返回账号视图。

## 目标项目协议与接入缺口

通用接入合同见 [项目管控协议 v1](../docs/project-control-protocol.md)，CPR 的安装和跟随官方更新步骤见 [扩展说明](../integrations/cpr/README.md)。新增项目应实现该合同并注册 `ProjectAdapter`，不能只凭普通模型 API Key 开启账号管控。

| 项目 | 查询 / 调度 | 自定义答题 |
| --- | --- | --- |
| sub2api | `GET /api/v1/admin/accounts`；`POST /api/v1/admin/accounts/{id}/schedulable` | 当前部分测试分支忽略 `prompt`，尚未接入 |
| codex-proxy-rs | 查询与人工启停沿用原接口；自动启停使用 `POST /api/admin/accounts/poolguard/control` | `POST /api/admin/accounts/poolguard/probe`，固定账号、完整回答、停用可复检 |

两者使用 `x-api-key`，响应业务码分别为 0 与 200。CPR 的 `ProjectAdapter.ask(externalId, model, question)` 已接入扩展；回答缺失完成标记、账号／模型不匹配、缺失控制版本、空回答或 HTTP 错误均记录 ERROR，不触发隔离。标准答案仅用于本地判分。

默认日志为 `logs/poolguard.log`（从启动脚本运行时位于 `backend/logs/`），可通过 `POOLGUARD_LOG_FILE` 调整。使用北京时间，记录逐题失败、自动禁用、恢复观察、重新启用及异常对账；每文件 10 MB、保留 7 天、总量 100 MB。日志不包含密钥或完整上游响应。

## 验证

`mvn test` 覆盖判分边界、管理 API 协议、登录/退出/配置/策略接口、模拟隔离恢复流程、异常保护和并发互斥。H2 及 HTTP 模拟服务器测试不替代 PostgreSQL Flyway 迁移、Redis 和真实模型账号的端到端联调。CPR 的隔离事务测试需配置独立测试数据库 `CPR_TEST_DATABASE_URL`；不能使用生产账号库。

检测历史查询参数：`page`（从 1 开始）、`pageSize`（默认 20，最多 100）、`from` / `to`（带时区的 ISO 时间，按批次开始时间闭区间查询）、`triggerType`（MANUAL / SCHEDULED / RETEST）、`status`（RUNNING / SUCCEEDED / FAILED）、`project`、`account`（账号 ID、内部 UUID 或脱敏邮箱）、`outcome`（PASS / FAIL / ERROR）。返回 `items`、`total`、`page`、`pageSize`、`totalPages`；账号与答题结果组合条件必须命中同一条答题记录。批次 `durationMs` 为运行总耗时，运行中的批次为查询时已用时间；逐题 `latencyMs` 包含上游等待，旧异常记录没有耗时则返回空值。

默认一轮答错且无接口异常时自动禁用；系统隔离的账号继续参与定时检测，一轮全部通过即恢复。异常保持启停状态，人工停用账号不自动恢复。升级时恢复观察次数设为 1，后续可在系统设置调整。

账号池的“复检”通过 `POST /api/accounts/{id}/actions`、`action=retest` 执行：本轮有答错且无异常时立即自动禁用，不受批量检测的连续失败阈值限制；已自动禁用的账号保持隔离并继续监测。接口异常不执行启停，通过后按系统设置的恢复次数恢复。

## 单账号多模型检测

账号池的“配置检测模型”每行填写一个目标项目支持的模型 ID（最多 20 个）。模型配置保存在 PoolGuard，后续同步不会覆盖；保存配置不发送模型请求，不保证远端支持该模型，不支持时检测记录为 ERROR。

可先点击账号行的“拉取上游模型”。PoolGuard 调用 CPR 已有的 `POST /api/admin/accounts/models/refresh`，请求 `{"accountId":"远端账号ID"}`，使用服务端管理员密钥，读取 `data.models[].id`。无需修改 CPR。模型目录由上游返回，目录中存在不代表该账号已经答题验证通过。

- `POST /api/accounts/{id}/models/sync`：拉取并保存该账号的上游候选模型，返回账号视图中的 `upstreamModels` 和 `modelsSyncedAt`。支持至多 500 个候选模型；失败或格式错误保留上次目录，成功的空目录清空候选。拉取不改变启停状态、恢复计数或已保存的定时检测模型。
- 复检范围可直接选择已保存定时模型或上游候选模型；已配置项与仅供本次复检的上游项分组显示。在“配置检测模型”中勾选上游模型并保存后，才加入定时检测。可用“加入全部上游模型”一次加入，定时模型仍限制最多 20 个。

- `PUT /api/accounts/{id}/models`，请求 `{"models":["model-a","model-b"]}`，保存该账号的模型列表，去除首尾空白及重复项。检测期间修改返回 409，防止一轮内配置变化。
- `POST /api/accounts/{id}/actions`，请求 `{"action":"retest","model":"model-a"}`，检测该账号已配置或从上游同步的指定模型；省略 `model` 则检测全部定时配置模型。
- 定时及全量检测逐账号、逐模型执行全部启用题目。每条逐题记录的 `model` 是实际发送的模型快照；旧记录为 null，不推测历史模型。
- 同一轮出现任何接口异常，保持账号启停状态；无异常且任一答案错误，按失败规则禁用整个账号。只有全部配置模型、全部题目通过的完整轮次可累计自动恢复次数。多模型账号的单模型 PASS 不会自动恢复、不将账号标记为健康，也不覆盖最近全模型评分。
- 账号响应 `enabled` 是最近一次目录同步或成功控制回执确认的远端启停状态；`status` 仍表示健康/管控状态，`monitoring` 表示是否继续检测。自动禁用账号显示“已禁用（自动）”，可继续复检或手工恢复。概览禁用数量包含自动禁用账号。

CPR 现有控制接口的粒度为整个账号，不支持仅禁止该账号的某个模型。多模型检测沿用已有 probe 接口的 `model` 参数，无需增加 CPR 接口。

定时检测的推荐配置、逐项执行规则与覆盖验收见 [账号模型检测方案](../docs/account-model-detection.md)。普通接口错误不会中断后续题目和模型；批次新增 `plannedChecks` / `completedChecks`，表示计划调用数与已记录调用数（包含异常）。旧批次返回 null。控制冲突和任务锁丢失仍会停止相关检测。
