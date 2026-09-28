param(
    [ValidateSet('demo','live')][string]$Mode = 'demo',
    [int]$Port = 8090,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$backendDir = Join-Path $projectRoot 'backend'
$jar = Join-Path $backendDir 'target/poolguard-control-plane-0.1.0-SNAPSHOT.jar'
if (-not $JavaHome -and (Test-Path -LiteralPath 'D:\ma_nong\java\jdk17\bin\java.exe')) {
    $JavaHome = 'D:\ma_nong\java\jdk17'
}
$java = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $java)) { throw 'Java 不存在，请通过 -JavaHome 指定 JDK 17 或更高版本。' }
$versionText = (& $java -version 2>&1 | Out-String)
if ($versionText -notmatch 'version "(\d+)') { throw '无法识别 Java 版本。' }
if ([int]$Matches[1] -lt 17) { throw '需要 Java 17 或更高版本，请使用 -JavaHome 指定。' }
if ($Build -or -not (Test-Path -LiteralPath $jar)) {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    & mvn -q -f (Join-Path $backendDir 'pom.xml') package
    if ($LASTEXITCODE -ne 0) { throw '构建或测试失败。' }
}
Write-Host "PoolGuard: http://127.0.0.1:$Port  mode=$Mode"
if ($Mode -eq 'demo') { Write-Host '演示模式不会访问真实项目；数据在服务重启后重置。' }
Push-Location $backendDir
try {
    $arguments = @('-jar', $jar, "--server.port=$Port", "--spring.profiles.active=$Mode")
    & $java @arguments
} finally { Pop-Location }
