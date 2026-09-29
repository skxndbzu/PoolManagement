# 本机 IDEA 开发与中间件启动

适用于当前 Windows 电脑。两个服务以后台进程运行，关闭终端不会停止服务；没有配置 Windows 开机自启，重启电脑后重新执行启动命令

## 启动 PostgreSQL 和 Redis

在 PowerShell 或 IDEA 的 PowerShell 终端执行，可从任意目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File D:\app\pgslq\start-local.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File D:\app\redis\start-local.ps1
```

已经启动时会提示服务或端口已存在，不需要再启动第二份实例

| 服务 | 本机连接 | PoolGuard 使用方式 |
| --- | --- | --- |
| PostgreSQL | `127.0.0.1:5432` | 独立数据库 `poolguard`，本地测试用户／密码均为 `poolguard` |
| Redis | `127.0.0.1:6379` | DB `2`，使用原 Redis 密码；已写入下述本地配置 |

PostgreSQL 安装目录是 `D:\app\pgslq`，注意目录拼写。数据和日志分别在该目录的 `data`、`logs` 下；Redis 对应目录为 `D:\app\redis\data`、`D:\app\redis\logs`

## 在 IDEA 启动 PoolGuard

1. 使用 JDK 17 或更高版本；本机可选 `D:\app\scoop\apps\temurin21-jdk\current`
2. 打开 `Run → Edit Configurations`，选择 `com.poolguard.PoolGuardApplication` 的运行配置
3. 将工作目录设为 `D:\project\PoolManagement\backend`
4. 在 `Program arguments` 填入下面一整行；如果该输入框隐藏，通过 `Modify options` 显示

```text
--spring.profiles.active=live --spring.config.additional-location=file:D:/project/PoolManagement/backend/.runtime/application-local.yml
```

5. 移除运行配置中旧的 `demo` profile，以及直接覆盖 Spring 数据源／Redis 地址的远程连接参数，再运行主类
6. 启动成功后打开 <http://127.0.0.1:8090/>。首次启动由 Flyway 自动创建 PoolGuard 的表，无需手动导入表结构

本地连接文件位于 `backend/.runtime/application-local.yml`，已被 Git 忽略，其中含本地 Redis 密码，不要提交或公开。该文件只配置 PostgreSQL 和 Redis；启动 IDEA 时必须携带上述参数才会加载

CPR 的管理地址、管理员 Key 和目标模型仍按 [后端说明](../backend/README.md) 配置。若 CPR 在服务器上，地址使用服务器可访问地址；若在本机运行则使用实际本机端口。中间件连接成功不等于 CPR 检测接口已经部署

## 确认状态

```powershell
& D:\app\pgslq\bin\pg_ctl.exe status -D D:\app\pgslq\data
Get-NetTCPConnection -State Listen -LocalPort 5432,6379 | Select-Object LocalAddress,LocalPort,OwningProcess
```

端口监听仅证明有程序占用端口。如果 PostgreSQL 脚本提示未运行但 `5432` 已监听，应先检查占用进程，避免连接到另一份数据库实例。WSL 中此前测试用的默认 PostgreSQL／Redis 已停用自动启动；CPR 的另一个 `55432` PostgreSQL 实例不受此配置影响

## 停止服务

先停止使用这些服务的 IDEA 项目及其他本地应用，再执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File D:\app\redis\stop-local.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File D:\app\pgslq\stop-local.ps1
```

停止命令不会删除数据库，Redis 停止脚本会保存数据。不要通过删除 `data` 目录停止或重置服务
