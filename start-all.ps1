<#
.SYNOPSIS
    个人 AI 健身系统 —— 一键启动 / 停止。

.DESCRIPTION
    按依赖顺序把整套环境拉起来：
      1) 检查 MySQL（Windows 服务 MySQL84，默认自动启动）
      2) 启动 Docker Desktop 并启动 Redis 容器 fitness-redis（等待引擎就绪 + PING 验证）
      3) 启动后端 Spring Boot（Java 21 + Maven Wrapper）
      4) 启动前端 Vite
    每一步都会先探测是否已经在运行，可以重复执行（幂等）。

    后端与前端会在「独立的新窗口」中运行，便于直接在窗口里查看日志。

    编码说明：本文件以「UTF-8 带 BOM」保存。Windows PowerShell 5.1 读取无 BOM 的 UTF-8 文件时
    会按本地代码页（中文系统为 GBK）解析，导致中文乱码与语法错误；用编辑器改动本文件后请保持该编码。

.PARAMETER Stop
    停止后端与前端（按端口 8080 / 5173 定位进程）。Redis 容器默认保留。

.PARAMETER StopRedis
    配合 -Stop 使用：同时停止 Redis 容器。

.PARAMETER SkipRedis
    Redis 已经在跑（或使用其他 Redis 实例）时跳过 Docker 步骤。

.PARAMETER NoWait
    只负责拉起进程，不等待就绪探测。

.PARAMETER InfraOnly
    只启动基础设施（MySQL + Redis），不启动后端与前端。
    适合「在 IntelliJ IDEA 里运行 Spring Boot」的场景：IDEA 要用 8080 / 5173，
    脚本就不该先占住它们。

.EXAMPLE
    .\start-all.ps1
    启动全部服务，并在就绪后打印访问地址。

.EXAMPLE
    .\start-all.ps1 -Stop
    停止后端与前端。

.NOTES
    推荐入口是同级目录的 start-all.cmd（它用 -ExecutionPolicy Bypass 绕过 Windows 默认的
    「禁止运行脚本」策略限制，且不需要修改系统设置）。

    LLM_API_KEY：优先读取环境变量；没有则交互式询问（不会写入任何文件）。
    如需持久化，可在普通 PowerShell 里执行一次：
        setx LLM_API_KEY "sk-xxxxxxxxxxxxxxxx"
    之后新开的终端会自动携带该变量。
#>
[CmdletBinding()]
param(
    [switch]$Stop,
    [switch]$StopRedis,
    [switch]$SkipRedis,
    [switch]$InfraOnly,
    [switch]$NoWait
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# ------------------------------------------------------------------
# 可调参数：按实际环境修改以下路径
# ------------------------------------------------------------------
$Root         = 'D:\jsgl'
$BackendDir   = Join-Path $Root 'backend'
$FrontendDir  = Join-Path $Root 'frontend'
# 注意：用户级 JAVA_HOME 可能是 D:\jdk11（Java 11 无法编译本项目），这里强制覆盖为 Java 21
$JavaHome     = 'D:\jdk'
$NodeDir      = 'D:\NodeJS'          # npm / node（vite 的启动脚本需要 node 在 PATH 中）
$DockerExe    = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'
$DockerApp    = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\Docker Desktop.exe'
$RedisName    = 'fitness-redis'
$BackendPort  = 8080
$FrontendPort = 5173
$AppUrl       = "http://localhost:$FrontendPort"   # 必须用 localhost：Vite 只绑 IPv6 [::1]

function Write-Step([string]$Text) { Write-Host "`n=== $Text ===" -ForegroundColor Cyan }
function Write-Ok([string]$Text)   { Write-Host "  [OK] $Text" -ForegroundColor Green }
function Write-Warn2([string]$Text){ Write-Host "  [!]  $Text" -ForegroundColor Yellow }
function Write-Err2([string]$Text) { Write-Host "  [X]  $Text" -ForegroundColor Red }

function Test-TcpPort([int]$Port) {
    return [bool](Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue)
}

# 前端专用探测：Vite 只监听 IPv6 [::1]，用 127.0.0.1 做 TCP 探测会永远失败，
# 因此这里以「能拿到 HTTP 200」为准（localhost 会解析到 ::1）。
# 若不做这个区分，重复执行脚本会误判前端未启动，从而再开一个 5174 端口的 Vite。
function Test-FrontendUp([int]$Port) {
    if (Test-TcpPort $Port) { return $true }
    try {
        return (Invoke-WebRequest -Uri "http://localhost:$Port" -UseBasicParsing -TimeoutSec 4).StatusCode -eq 200
    } catch {
        return $false
    }
}

function Stop-PortOwner([int]$Port) {
    $stopped = 0
    $lines = netstat -ano | Select-String ":$Port\s" | Select-String 'LISTENING'
    foreach ($line in $lines) {
        $procId = ($line.Line -split '\s+')[-1]
        if ($procId -match '^\d+$' -and $procId -ne '0') {
            Stop-Process -Id ([int]$procId) -Force -ErrorAction SilentlyContinue
            $stopped++
        }
    }
    return $stopped
}

function Start-InNewWindow([string]$Title, [string]$WorkDir, [string]$Command) {
    $inner = "`$host.UI.RawUI.WindowTitle = '$Title'; Set-Location '$WorkDir'; $Command"
    $shell = 'powershell'
    if (Get-Command pwsh -ErrorAction SilentlyContinue) { $shell = 'pwsh' }
    Start-Process -FilePath $shell -ArgumentList @('-NoExit', '-NoProfile', '-Command', $inner) -WorkingDirectory $WorkDir | Out-Null
}

# ------------------------------------------------------------------
# 停止模式
# ------------------------------------------------------------------
if ($Stop) {
    Write-Step "停止后端与前端"
    foreach ($port in @($BackendPort, $FrontendPort)) {
        $n = Stop-PortOwner $port
        if ($n -gt 0) { Write-Ok "已停止占用 $port 端口的进程（$n 个）" } else { Write-Warn2 "$port 端口本来就没有进程在监听" }
    }
    if ($StopRedis) {
        Write-Step "停止 Redis 容器"
        if (Test-Path $DockerExe) {
            & $DockerExe stop $RedisName 2>&1 | Out-Null
            Write-Ok "已停止容器 $RedisName（Docker Desktop 本身不会退出）"
        } else { Write-Warn2 "找不到 docker.exe，跳过" }
    } else {
        Write-Host "  （Redis 容器保留；要一起停请加 -StopRedis）" -ForegroundColor DarkGray
    }
    Write-Host "`n已停止。" -ForegroundColor Green
    return
}

Write-Host "个人 AI 健身系统 —— 启动中" -ForegroundColor Magenta
Write-Host "项目根目录：$Root" -ForegroundColor DarkGray

# ------------------------------------------------------------------
# 0) 前置检查
# ------------------------------------------------------------------
Write-Step "0/4 前置检查"
if (-not (Test-Path (Join-Path $BackendDir 'mvnw.cmd'))) { Write-Err2 "找不到 $BackendDir\mvnw.cmd，请确认项目路径"; return }
if (-not (Test-Path $JavaHome)) { Write-Err2 "找不到 Java 21 目录：$JavaHome（请修改脚本顶部的 `$JavaHome）"; return }
if (-not (Test-Path $NodeDir))  { Write-Err2 "找不到 Node 目录：$NodeDir"; return }
if (-not (Test-Path (Join-Path $FrontendDir 'node_modules'))) { Write-Warn2 "前端 node_modules 不存在，第一次需要先执行：cd $FrontendDir; npm install" }
Write-Ok "Java: $JavaHome   Node: $NodeDir"

if (-not $env:LLM_API_KEY) {
    Write-Warn2 "未检测到环境变量 LLM_API_KEY（AI 对话 / ChatMemory 需要它）"
    $typed = Read-Host "  请输入 DeepSeek API Key（直接回车则跳过，AI 接口将不可用）"
    if ($typed) { $env:LLM_API_KEY = $typed.Trim(); Write-Ok "已临时注入本次启动的 LLM_API_KEY" }
    else { Write-Warn2 "已跳过：AI 相关接口会返回鉴权错误，其余功能正常" }
} else {
    Write-Ok "LLM_API_KEY 已就绪（来自环境变量，长度 $($env:LLM_API_KEY.Length)）"
}
# 后端必须用 Java 21：覆盖可能指向 jdk11 的用户级 JAVA_HOME
$env:JAVA_HOME = $JavaHome
# vite 的启动脚本需要 node 在 PATH 中
$env:PATH = "$NodeDir;$env:PATH"

# ------------------------------------------------------------------
# 1) MySQL
# ------------------------------------------------------------------
Write-Step "1/4 MySQL（3306）"
if (Test-TcpPort 3306) {
    Write-Ok "MySQL 已在运行"
} else {
    Write-Warn2 "3306 未监听，尝试启动 Windows 服务 MySQL84…"
    try { Start-Service -Name 'MySQL84' -ErrorAction Stop; Start-Sleep -Seconds 3 } catch { Write-Err2 "启动 MySQL84 失败：$($_.Exception.Message)" }
    if (Test-TcpPort 3306) { Write-Ok "MySQL 已启动" } else { Write-Err2 "MySQL 仍未就绪，请手动检查服务 MySQL84" }
}

# ------------------------------------------------------------------
# 2) Redis（Docker）
# ------------------------------------------------------------------
Write-Step "2/4 Redis（6379，Docker 容器 $RedisName）"
$engineReady = $false
if ($SkipRedis) {
    Write-Warn2 "已按 -SkipRedis 跳过"
} elseif (Test-TcpPort 6379) {
    Write-Ok "Redis 已在运行"
} elseif (-not (Test-Path $DockerExe)) {
    Write-Err2 "找不到 docker.exe（预期位置：$DockerExe）"
    Write-Host "  请手动启动 Docker Desktop，或修改脚本顶部的 `$DockerExe" -ForegroundColor DarkGray
} else {
    & $DockerExe info --format '{{.ServerVersion}}' 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Ok "Docker 引擎已在运行"
        $engineReady = $true
    } else {
        if (Test-Path $DockerApp) {
            Write-Host "  正在启动 Docker Desktop（首次启动约需 15~60 秒）…" -ForegroundColor DarkGray
            Start-Process -FilePath $DockerApp | Out-Null
        } else { Write-Warn2 "找不到 Docker Desktop.exe，请手动打开它" }
        for ($i = 0; $i -lt 24; $i++) {
            Start-Sleep -Seconds 5
            & $DockerExe info --format '{{.ServerVersion}}' 2>&1 | Out-Null
            if ($LASTEXITCODE -eq 0) { $engineReady = $true; Write-Ok "Docker 引擎就绪（约 $(($i+1)*5) 秒）"; break }
        }
        if (-not $engineReady) { Write-Err2 "Docker 引擎 120 秒内未就绪，请手动打开 Docker Desktop 后重跑本脚本" }
    }

    if ($engineReady) {
        $exists = (& $DockerExe ps -a --filter "name=^$RedisName$" --format '{{.Names}}' 2>&1 | Out-String).Trim()
        if ($exists -eq $RedisName) {
            & $DockerExe start $RedisName 2>&1 | Out-Null
            Write-Ok "已启动已有容器 $RedisName"
        } else {
            Write-Host "  容器不存在，创建 redis:7-alpine …" -ForegroundColor DarkGray
            & $DockerExe run -d --name $RedisName -p 6379:6379 redis:7-alpine 2>&1 | Out-Null
            Write-Ok "已创建并启动容器 $RedisName"
        }
        Start-Sleep -Seconds 3
        $pong = (& $DockerExe exec $RedisName redis-cli ping 2>&1 | Out-String).Trim()
        if ($pong -eq 'PONG') { Write-Ok "Redis 连通性验证：PONG" } else { Write-Err2 "Redis 未响应 PING（返回：$pong）" }
    }
}

# ------------------------------------------------------------------
# 3) 后端
# ------------------------------------------------------------------
if ($InfraOnly) {
    Write-Host ("`n" + ('-' * 56)) -ForegroundColor DarkGray
    Write-Host "  已按 -InfraOnly 只启动基础设施：MySQL（3306）+ Redis（6379）" -ForegroundColor Green
    Write-Host "  8080 / 5173 保持空闲，可以直接在 IntelliJ IDEA 里运行后端与前端。" -ForegroundColor White
    Write-Host "  IDEA：打开 D:\jsgl → 提示 Load Maven Project 时点 Load → 运行 FitnessAiApplication" -ForegroundColor White
    Write-Host ('-' * 56) -ForegroundColor DarkGray
    return
}

Write-Step "3/4 后端 Spring Boot（$BackendPort）"
if (Test-TcpPort $BackendPort) {
    Write-Ok "后端已在运行"
} else {
    Start-InNewWindow 'Fitness Backend 8080' $BackendDir '.\mvnw.cmd -B -ntp spring-boot:run'
    Write-Ok "已在新窗口启动后端（首次编译约 30~60 秒）"
}

# ------------------------------------------------------------------
# 4) 前端
# ------------------------------------------------------------------
Write-Step "4/4 前端 Vite（$FrontendPort）"
if (Test-FrontendUp $FrontendPort) {
    Write-Ok "前端已在运行"
} else {
    Start-InNewWindow 'Fitness Frontend 5173' $FrontendDir 'npm run dev'
    Write-Ok "已在新窗口启动前端"
}

# ------------------------------------------------------------------
# 等待就绪
# ------------------------------------------------------------------
if (-not $NoWait) {
    Write-Step "等待就绪"
    $backendOk = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 2
        if (Test-TcpPort $BackendPort) {
            try {
                $r = Invoke-WebRequest -Uri "http://localhost:$BackendPort/api/health" -UseBasicParsing -TimeoutSec 4
                if ($r.StatusCode -eq 200) { $backendOk = $true; Write-Ok "后端就绪（约 $(($i+1)*2) 秒）"; break }
            } catch { }
        }
    }
    if (-not $backendOk) { Write-Warn2 "后端在 120 秒内未通过健康检查，请查看标题为 Fitness Backend 8080 的窗口日志" }

    $frontendOk = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        if (Test-FrontendUp $FrontendPort) { $frontendOk = $true; Write-Ok "前端就绪（约 $($i+1) 秒）"; break }
    }
    if (-not $frontendOk) { Write-Warn2 "前端未就绪，请查看标题为 Fitness Frontend 5173 的窗口日志" }
}

Write-Host ("`n" + ('-' * 56)) -ForegroundColor DarkGray
if (Test-TcpPort $BackendPort)     { Write-Host "  后端  http://localhost:$BackendPort/api/health" -ForegroundColor White }
if (Test-FrontendUp $FrontendPort) { Write-Host "  前端  $AppUrl   <-- 用浏览器打开这个" -ForegroundColor Green }
Write-Host "  Redis 容器：$RedisName（6379）  MySQL：MySQL84（3306）" -ForegroundColor White
Write-Host '  ------------------------' -ForegroundColor DarkGray
Write-Host "  停止：start-all.cmd -Stop      （要连 Redis 一起停：-Stop -StopRedis）" -ForegroundColor White
Write-Host "  提示：必须用 localhost 访问，不要用 127.0.0.1（Vite 只绑 IPv6 [::1]）" -ForegroundColor Yellow
Write-Host ('-' * 56) -ForegroundColor DarkGray