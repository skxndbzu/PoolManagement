# PoolGuard 号池管控台

Java 17 + Spring Boot 3 后端，原生 JavaScript 前端，页面由同一个 Java 服务提供。
正式模式使用 PostgreSQL 保存配置与检测记录，Redis 保存登录会话。支持复用已有中间件实例，但 PostgreSQL 必须创建独立的 `poolguard` 数据库，不能直接指向 sub2api / codex-proxy-rs 的业务库。

## 当前可用范围

- 真实登录、退出、会话鉴权；前端通过 API 读写数据，不再用 localStorage 模拟业务操作。
- 自定义检测周期（1–999 分钟/小时/天），默认 5 分钟。
- 自定义题目与答案，新增、修改、删除、启停，整批事务保存。
- 单账号复检、全量检测、答题结果与接口异常分开记录。
- 连续答题未通过自动隔离，隔离账号继续复检；默认失败 2 轮隔离、连续通过 2 轮恢复。
- 手工停用与自动隔离分开管理；手工停用的账号不自动恢复。
- 检测历史、逐题问题/标准答案/实际回答摘要/失败原因。
- 分页读取两个项目的 OpenAI 账号，管理 API 调整调度开关。目标模型通过项目环境变量指定，不用套餐或平台名称冒充模型名。

**CPR 需部署本地扩展后启用真实答题。** [接入与升级说明](integrations/cpr/README.md)包含 CPR 补丁、部署步骤和日志查看方式。扩展支持指定账号自定义答题、停用期间复检及带回执的自动启停；旧版 CPR 缺少接口时只记录异常。sub2api 的部分测试分支仍忽略自定义题目，能力检测保持未接入。

## 本机演示

```powershell
.\Start-PoolGuard.ps1 -Mode demo -JavaHome 'D:\ma_nong\java\jdk17'
```

打开 <http://127.0.0.1:8090>。默认登录 `admin / pool-admin`。
需要重新构建时添加 `-Build`；占用端口时添加 `-Port 8091`。

演示使用 H2 内存数据库、内存会话和模拟项目，不需要安装 PostgreSQL/Redis，也不会访问真实上游。服务重启会清空演示数据。自动调度默认关闭，可通过启动参数 `--poolguard.scheduler.enabled=true` 测试定时运行。

1. 登录后点击“同步账号”，可见两个项目共 8 个模拟账号。
2. 点击“立即检测”：`demo-good` 通过，`demo-bad` 和 `demo-recover` 答错后隔离，`demo-manual` 保持手工停用。
3. 再运行两轮：`demo-recover` 依次进入恢复观察、正常状态；`demo-bad` 继续隔离。
4. “检测记录”展示批次和逐题耗时，支持时间、触发方式、批次状态、项目、账号及答题结果组合筛选与分页。“系统设置”可修改周期；刷新页面后仍保留。

模拟答案只预设了 `17 × 24 → 408`，用于演示控制流程，不会自动根据用户填写的标准答案生成正确答案。自定义题目在此环境中可能答错，这是模拟器的明确限制。

## 正式接入与验证

在当前 Windows 电脑使用 IDEA 开发，见 [本地 PostgreSQL／Redis 启停与 IDEA 配置](docs/local-development.md)。

定时遍历每个账号的配置模型，见 [账号 × 模型检测方案](docs/account-model-detection.md)，包含前端配置、异常后继续检测、整轮启停判定和覆盖进度说明。

接入其他受管项目时，实现 [项目管控协议 v1](docs/project-control-protocol.md) 中的账号目录、指定账号答题、隔离回执及人工启停能力，并注册对应 `ProjectAdapter`。文档列明请求／响应字段、错误处理、人工操作优先级和验收场景。

详见 [后端说明](backend/README.md)。真实账号自动隔离／恢复仍需在部署扩展后验收，模拟测试不能代替上游模型实测。sub2api 仍需自定义答题接口及真实联调。答案匹配只能反映题集表现，不能证明实际使用了哪一种模型。

```powershell
$env:JAVA_HOME = 'D:\ma_nong\java\jdk17'
mvn -f backend/pom.xml test
node --check app.js
```
