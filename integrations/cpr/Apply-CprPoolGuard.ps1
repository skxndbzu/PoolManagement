param(
    [string]$CprRoot = 'D:\Project\codex-proxy-rs',
    [switch]$Check
)
$ErrorActionPreference = 'Stop'
$patchFile = Join-Path $PSScriptRoot 'poolguard.patch'
if (-not (Test-Path -LiteralPath $patchFile)) { throw '缺少 poolguard.patch。' }
& git -C $CprRoot rev-parse --show-toplevel | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'CPR 路径不是 Git 工作区。' }
$conflicts = & git -C $CprRoot diff --name-only --diff-filter=U
if ($LASTEXITCODE -ne 0 -or $conflicts) { throw 'CPR 存在未解决的合并冲突，请先完成当前合并。' }
$previousErrorActionPreference = $ErrorActionPreference
try {
    # 未安装时反向检查失败是正常情况，兼容 Windows PowerShell 的原生命令错误流。
    $ErrorActionPreference = 'Continue'
    & git -C $CprRoot apply --reverse --check $patchFile *> $null
    $alreadyApplied = $LASTEXITCODE -eq 0
} finally { $ErrorActionPreference = $previousErrorActionPreference }
if ($alreadyApplied) { Write-Host 'PoolGuard 扩展已经存在。'; exit 0 }
& git -C $CprRoot apply --check $patchFile
if ($LASTEXITCODE -ne 0) { throw '补丁与当前 CPR 不匹配，请审查接口变化后合并；未修改代码。' }
if ($Check) { Write-Host '检查通过，可以应用 PoolGuard 扩展。'; exit 0 }
& git -C $CprRoot apply $patchFile
if ($LASTEXITCODE -ne 0) { throw '补丁应用失败，请检查 Git 差异。' }
Write-Host '已应用 PoolGuard 扩展。请按 CPR 文档构建并重启服务。'
