# CPR 自动检测扩展

CPR 扩展提供两个管理员接口：`/api/admin/accounts/poolguard/probe` 和 `/api/admin/accounts/poolguard/control`。不需要插件、不新增 CPR 数据库迁移；题库与调度保存在 PoolGuard。

首次安装或在一份官方代码上重新安装扩展：

```powershell
.\integrations\cpr\Apply-CprPoolGuard.ps1 -CprRoot D:\Project\codex-proxy-rs -Check
.\integrations\cpr\Apply-CprPoolGuard.ps1 -CprRoot D:\Project\codex-proxy-rs
```

之后按 CPR 的构建与部署方式重新构建并启动服务。脚本只检查／应用本地补丁，不拉取代码、不重置工作区、不提交、不重启服务。扩展已经存在时重复执行不重复修改；发生合并冲突时停止。正常保留本地扩展并合并上游，不必每次重放补丁。

补丁基于官方提交 `d83d3eb80396b454c8120612a9ffed03d57af72c`；后续官方改变接口或审计结构时必须重新验证。CPR 修改说明和接口合同随补丁写入 `docs/poolguard.md`。

PoolGuard 配置：`CODEX_PROXY_BASE_URL`、`CODEX_PROXY_ADMIN_API_KEY`、`CODEX_PROXY_TEST_MODEL`。默认连续 2 轮不达标自动禁用，通过 `POOLGUARD_DISABLE_FAILURES` 调整；恢复次数沿用页面设置。检测题目、答案或目标模型变化会重新累计连续次数。演示模式默认 1 轮失败隔离。

启动脚本会将工作目录设为 `backend`，默认日志文件是 `backend/logs/poolguard.log`；通过 `POOLGUARD_LOG_FILE` 可设置绝对路径。日志使用北京时间，单文件 10 MB，保留 7 天、总量上限 100 MB。日志包含账号 ID、脱敏邮箱、题目 ID／摘要及操作结果，不输出账号密钥或上游原始响应。

```powershell
Get-Content .\backend\logs\poolguard.log -Encoding UTF8 -Wait
```

重点关注 `[题目未通过/疑似降智]`、`[自动禁用成功]`、`[恢复观察]`、`[能力恢复/自动启用成功]`、`[隔离对账确认]`、`[恢复对账确认]` 和 `[自动管控暂停]`。超时与上游异常单独记录，不算答错；启停超时后查回执，确认之前不打印成功。

恢复只解除 PoolGuard 自己的隔离。CPR 人工启停会使旧回执失效；原有批量启停审计无法精确定位每个账号，因此批量操作可能保守地暂停其他受管账号。回执随 CPR 审计保留期过期，过期且仍停用时需要人工确认。暂停后，在 PoolGuard 使用手工恢复重新开始监测。
