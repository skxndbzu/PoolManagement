# CPR 自动检测扩展

CPR 扩展提供两个管理员接口：`/api/admin/accounts/poolguard/probe` 和 `/api/admin/accounts/poolguard/control`。不需要插件、不新增 CPR 数据库迁移；题库与调度保存在 PoolGuard。

首次安装或在一份官方代码上重新安装扩展：

```powershell
.\integrations\cpr\Apply-CprPoolGuard.ps1 -CprRoot D:\Project\codex-proxy-rs -Check
.\integrations\cpr\Apply-CprPoolGuard.ps1 -CprRoot D:\Project\codex-proxy-rs
```

之后按 CPR 的构建与部署方式重新构建并启动服务。脚本只检查／应用本地补丁，不拉取代码、不重置工作区、不提交、不重启服务。扩展已经存在时重复执行不重复修改；发生合并冲突时停止。正常保留本地扩展并合并上游，不必每次重放补丁。

补丁基于官方提交 `f4356d867044d1246c2c162f440a9df8bed06e67`；后续官方改变接口或审计结构时必须重新验证。CPR 接口合同随补丁写入 `docs/poolguard.md`，其他项目接入遵循 [项目管控协议 v1](../../docs/project-control-protocol.md)。

## 保留扩展并合并官方更新

建议将扩展单独提交到本地分支，例如 `poolguard-extension`。首次整理时从当前 CPR 工作区创建分支，检查差异，只提交扩展文件；已有的子模块改动和其他个人改动不要混入。保留该提交，后续从此分支合并官方版本，不要用重置或覆盖目录的方式更新。

```powershell
# 在 CPR 仓库内执行；首次创建，已有此分支时使用 git switch poolguard-extension
git switch -c poolguard-extension
# 审查并提交扩展后，后续更新时执行：
git fetch origin
git merge origin/main
```

这里假定 `origin/main` 是官方代码来源；如果 `origin` 是自己的 fork，应使用实际的官方远端分支。合并前保持工作区干净；合并有冲突时先解决并验证，不能强行覆盖。扩展不是插件，业务实现集中在新增的 `poolguard.rs`，现有控制面文件主要增加模块、路由和服务端口接线。Core 探测结果增加结束原因并从事件中保留它，使 PoolGuard 可以拒绝不完整答案；不修改 Provider、前端、依赖或数据库迁移，仍不能保证未来零冲突。

官方更新后重点检查 `AccountsService`、`AccountStore`、`AccountProbe`、管理认证和账号启停审计合同。按 CPR 的 `docs/architecture.md` 运行 Rustfmt、Clippy 和后端测试，数据库事务测试使用独立测试库；最后部署到测试实例验收指定账号答题、停用复检、隔离恢复和人工覆盖。补丁脚本适用于未安装扩展的官方工作区；已合并扩展的分支无需每次重新应用。

## PoolGuard 配置与运行

PoolGuard 配置：`CODEX_PROXY_BASE_URL`、`CODEX_PROXY_ADMIN_API_KEY`、`CODEX_PROXY_TEST_MODEL`。默认 1 轮不达标自动禁用，通过 `POOLGUARD_DISABLE_FAILURES` 调整；默认 1 轮全部通过自动恢复，恢复次数可在页面设置。检测题目、答案或目标模型变化会重新累计连续次数。演示模式默认 1 轮失败隔离。

启动脚本会将工作目录设为 `backend`，默认日志文件是 `backend/logs/poolguard.log`；通过 `POOLGUARD_LOG_FILE` 可设置绝对路径。日志使用北京时间，单文件 10 MB，保留 7 天、总量上限 100 MB。日志包含账号 ID、脱敏邮箱、题目 ID／摘要及操作结果，不输出账号密钥或上游原始响应。

```powershell
Get-Content .\backend\logs\poolguard.log -Encoding UTF8 -Wait
```

重点关注 `[题目未通过/疑似降智]`、`[自动禁用成功]`、`[恢复观察]`、`[能力恢复/自动启用成功]`、`[隔离对账确认]`、`[恢复对账确认]` 和 `[自动管控暂停]`。超时与上游异常单独记录，不算答错；启停超时后查回执，确认之前不打印成功。

恢复只解除 PoolGuard 自己的隔离。CPR 人工启停会使旧回执失效；原有批量启停审计无法精确定位每个账号，因此批量操作可能保守地暂停其他受管账号。回执随 CPR 审计保留期过期，过期且仍停用时需要人工确认。暂停后，在 PoolGuard 使用手工恢复重新开始监测。
