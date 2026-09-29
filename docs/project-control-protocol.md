# PoolGuard 项目管控协议 v1

本协议供受管项目及其 PoolGuard 适配器实现者使用。目标是指定账号答题、仅隔离答题未达标的账号、在隔离期间复检，并安全恢复同一次自动隔离

协议定义业务语义与 JSON 字段。项目可沿用原生目录和人工启停接口，通过 `ProjectAdapter` 映射；不要求复制 CPR 的 URL、数据库或内部实现。新项目仍需注册适配器，当前配置不会自动发现任意项目

## 接口清单

| 必需能力 | CPR 路由 | PoolGuard 调用点 |
| --- | --- | --- |
| 完整分页账号目录 | `GET /api/admin/accounts?page=1&pageSize=200&provider=openai` | `listAccounts()` |
| 指定账号自定义答题 | `POST /api/admin/accounts/poolguard/probe` | `ask()` |
| 隔离、恢复及回执查询 | `POST /api/admin/accounts/poolguard/control` | `isolate()`、`restoreIsolation()`、`isolationState()` |
| 人工启停并确认实际状态 | `POST /api/admin/accounts/batch-update`，随后 `GET /api/admin/accounts/detail?accountId=...` | `setSchedulable()` |

自动管控必须同时支持答题和隔离回执。只提供普通启停接口的项目不能宣称支持安全的自动隔离恢复

## 通用约定

- 所有接口只对管理员或专用服务身份开放；CPR 使用现有 `x-api-key` 管理员鉴权，也接受管理员会话
- 同服务器优先使用环回或容器私网地址，跨服务器使用 TLS；普通模型调用 Key 不得获得管理权限
- JSON 使用 UTF-8。CPR 成功响应为 HTTP 200、`{"code":200,"data":{...}}`，其他项目由适配器归一化
- `accountId` 是目标项目稳定、唯一的账号 ID，不是邮箱、模型名或可轮换的 Client Key
- 不把上游账号 Token、Cookie、代理密码返回 PoolGuard；模型请求继续由目标项目负责授权、刷新、代理和传输
- 非成功响应必须可区分输入错误、鉴权、控制冲突和上游异常；响应及日志不得回显凭据或任意上游原始正文

## 1. 账号目录

目录至少包含账号 ID、脱敏展示名、项目及当前 `enabled` 状态；须提供明确的分页结束依据

CPR 适配器读取 `data.items[].id/email/enabled` 和 `data.page.totalPages`，目标模型来自 PoolGuard 项目配置。目录返回所有受管账号，包括停用账号；不能只返回可调度账号

适配器必须完整读完目录后才能把缺失账号标记为已移除。分页失败、重复 ID、响应格式错误都应放弃本轮目录更新，不得把部分目录当作完整结果

## 2. 指定账号答题

```http
POST /api/admin/accounts/poolguard/probe
Content-Type: application/json
x-api-key: <管理员Key>
```

```json
{"accountId":"acct_example","model":"目标上游模型ID","prompt":"计算17乘24，只输出整数"}
```

成功返回：

```json
{"code":200,"data":{"accountId":"acct_example","model":"目标上游模型ID","text":"408","completed":true,"controlVersion":"12"}}
```

- 必须使用指定账号，不得轮换或回退到其他账号
- `prompt` 必须真实传给上游，不得替换为固定连通题；请求不包含标准答案
- 停用账号仍可探测，探测不得将其启用或清除隔离归属，不得修改其分组、权重或授权归属；允许沿用目标项目正常的 Token 刷新机制
- 使用目标项目现有的账号授权、出站代理和上游协议实现；停用状态不等于 Token 被撤销，授权失效仍应返回错误
- `text` 为完整、按顺序拼接的最终文本回答；连接成功、响应头成功、半截流和推理过程不能替代最终答案
- `completed=true` 只在上游请求正常完成时返回。超时、上游错误、空回答、截断或超限均为异常，不是答错
- CPR 必须保留并检查 Provider 规范化结束原因：只有 `Stop` 接受为正常完成；长度截断、内容过滤、工具调用、其他或缺失结束原因返回 HTTP 502，不得仅凭获得非空文本设置 `completed=true`
- `model` 表示请求目标，不证明上游实际运行的模型身份
- `controlVersion` 为答题开始前读取的账号启停版本，按十进制字符串传递；人工启停必须使它失效
- CPR 限制：题目最多 16 KiB，回答最多 64 KiB，探测执行超时 180 秒。PoolGuard HTTP 读取超时为 200 秒，为目标项目返回结果留出余量；目标项目不得无限执行

PoolGuard 校验返回账号、模型、完整结束和控制版本，再由本地 `AnswerEvaluator` 判分。每道题通过 `matchMode` 选择 `FUZZY`（默认，包含标准答案）或 `EXACT`（整段一致），均先归一化全半角、大小写和空白。模糊匹配允许解释，纯数字答案要求匹配完整数值文本（`21` 不匹配 `121`、`21.5` 或 `-21`），不判断推理正确性或否定语义；兼容旧的 `keywords:` 多关键词规则。已有题目迁移为模糊匹配，改变匹配方式会重新累计连续检测次数；历史结果不重新判分。目标项目不参与判分

## 3. 自动隔离、恢复和回执

禁用请求：

```json
{"accountId":"acct_example","operationId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","action":"disable","expectedControlVersion":"12"}
```

回执查询／恢复请求：

```json
{"accountId":"acct_example","operationId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","action":"inspect"}
```

```json
{"accountId":"acct_example","operationId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","action":"restore"}
```

成功响应：

```json
{"code":200,"data":{"accountId":"acct_example","operationId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","enabled":false,"controlVersion":"13","state":"isolated"}}
```

| state | 含义 | PoolGuard 行为 |
| --- | --- | --- |
| `none` | 没有该操作回执，账号当前启用 | 不认定隔离成功；重新检测后才可发起隔离 |
| `isolated` | 当前停用仍归属于此 operationId | 保持复检，达标后可恢复 |
| `restored` | 此次隔离已经恢复，当前账号启用 | 确认完成，清除本地隔离标记 |
| `conflict` | 状态被其他操作覆盖、回执失效或归属不可确认 | 暂停自动管控，等待人工确认 |

必须满足以下语义：

1. PoolGuard 在发出禁用前持久化非零 UUID `operationId`；同一次隔离的重试、查询、恢复使用相同 ID，新一轮隔离使用新 ID
2. 禁用只在账号启用且 `expectedControlVersion` 仍有效时提交；版本检查和状态修改必须在同一事务或等价原子操作中执行
3. 禁用状态与归属回执必须原子保存；不能先禁用，再在另一个独立事务记录归属
4. 重复相同禁用或恢复不得重复产生副作用；同一个 ID 不能绑定另一个账号
5. 恢复只能解除该 ID 当前拥有的隔离。人工启停、其他控制器覆盖或回执不可确认时，不得强制启用
6. 提交后应同步目标项目运行时调度视图。超时或连接中断后允许用 `inspect` 对账，调用方不得把 HTTP 超时直接视为未执行
7. `inspect` 不改变启停状态；`restore` 与 `inspect` 不携带 `expectedControlVersion`

CPR 使用现有审计表保存回执，不新增 CPR 表。回执受审计保留期影响；记录过期时，停用账号保守地返回冲突。同 Provider 的原生批量启停审计粒度较粗，可能使其他账号的隔离回执失效。长期隔离应确保审计保留期覆盖复检周期，或接受人工确认后恢复

## 4. 人工启停

CPR 请求：

```json
{"accountIds":["acct_example"],"enabled":false}
```

启用将 `enabled` 改为 `true`。只提交需要修改的字段，不覆盖并发、分组、权重等其他配置；查询详情确认实际状态后才更新 PoolGuard 页面

人工操作具有优先权，必须使旧自动隔离回执失效。人工恢复后 PoolGuard 清除旧操作 ID 和连续计数；人工停用后不继续自动恢复

## 错误与判定

| 状况 | 推荐 HTTP | 自动启停行为 |
| --- | --- | --- |
| 字段不合法 | 400 | 不执行 |
| 管理凭据无效／权限不足 | 401 / 403 | 不执行，报配置异常 |
| 账号不存在／未部署接口 | 404 | 不执行，标记接口或目录异常 |
| 版本或归属冲突 | 409，或 inspect 返回 conflict | 暂停该账号自动管控 |
| 上游限流、超时、鉴权、空回答、截断 | 429 / 502 / 503 / 504 | 记录 ERROR，不按答错禁用，不按答对恢复 |
| 完整回答与标准答案不符 | probe 本身成功，PoolGuard 记录 FAIL | 达到失败阈值后隔离 |

PoolGuard 默认一轮答错即隔离，一轮全部题目通过即恢复；恢复次数可在系统设置调整。一轮包含接口异常时不执行启停，即使其他题答错也不自动隔离；人工停用账号不会自动恢复。题目、答案或目标模型改变后重新累计；接口异常清零连续计数。支持协议不等于保证题库能够判断真实模型或所谓“智商”

## 接入验收

- 指定两个不同账号分别答题，确认没有发生跨账号回退
- 自定义算术题与标准答案获得 PASS，故意设置不匹配答案获得 FAIL
- 禁用后继续探测且保持停用；满足恢复次数后自动启用
- 人工停用与并发人工启停不能被自动恢复覆盖
- 模拟上游异常、流截断、禁用／恢复响应丢失、进程重启，确认不误判且能够对账
- 重复 operationId、跨账号复用 ID、过期 controlVersion 和回执过期均按合同处理
- 文档注明实际测试版本和缺口，模拟测试不能替代目标服务真实答题验收

账号池的“复检”通过 `POST /api/accounts/{id}/actions`、`action=retest` 执行：本轮有答错且无异常时立即自动禁用，不受批量检测的连续失败阈值限制；已自动禁用的账号保持隔离并继续监测。接口异常不执行启停，通过后按系统设置的恢复次数恢复。

同一账号可配置多个检测模型。PoolGuard 对每个模型单独调用 probe，以 `accountId + model` 定向检测，并保存实际模型到逐题记录。账号控制接口仍作用于整个账号：只有全部配置模型完成全部启用题目且通过，才可累计恢复轮次；手动指定某一个模型通过不能提前恢复多模型账号。任一模型答错且本轮无接口异常时，按现有规则隔离整个账号。无需目标项目新增模型级启停接口。

## 可选：账号模型目录

适配器可实现 `ProjectAdapter.refreshModels(externalId)`，返回该账号上游公布的模型 ID 清单。没有此能力的项目应明确报错，不把默认模型伪装成已同步目录。CPR 已有 `POST /api/admin/accounts/models/refresh`，管理员认证，请求 `{"accountId":"acct_xxx"}`，成功响应为 `{"code":200,"data":{"models":[{"id":"模型ID","label":"显示名"}]}}`。

PoolGuard 通过 `POST /api/accounts/{id}/models/sync` 保存候选清单及拉取时间，模型按 ID 去重，失败保留上次目录；成功的空清单清除候选。候选模型可直接用于单模型复检，选中并保存到检测配置后才参与定时检测。目录只表明上游公布该模型，不构成成功答题或可自动恢复的证据。
