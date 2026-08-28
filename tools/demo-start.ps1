# HIP 演示环境一键启动（面向客户/投资方现场演示，非开发用）
#
# 与 dev-start.ps1 的区别：本脚本用**已构建产物**启动（jar + vite），不跑 mvn 编译，
# 起得快、不依赖完整开发工具链；并自动灌演示数据，保证界面有真实业务流水可看。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\demo-start.ps1           # 启动（保留既有数据）
#   powershell -ExecutionPolicy Bypass -File tools\demo-start.ps1 -Fresh    # 重建演示库，干净开讲
#   powershell -ExecutionPolicy Bypass -File tools\demo-start.ps1 -Stop     # 停止
#
# 演示地址 http://localhost:5173 ，账号见启动结束后的提示。

param(
    [switch]$Fresh,
    [switch]$Stop
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$demoDb = 'hip_demo'

function Stop-Port($port) {
    try {
        Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop |
            ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
    } catch { }
}

if ($Stop) {
    Write-Host "停止演示环境..." -ForegroundColor Cyan
    Stop-Port 8080
    Stop-Port 5173
    Stop-Port 8100
    wsl -d Ubuntu -- pkill -f 'sleep infinity' 2>$null      # 收掉保活，让 WSL 可以空闲关机
    Write-Host "已停止（数据库与数据保留，下次启动即恢复现场）" -ForegroundColor Green
    exit 0
}

# ── 1. 数据库 ────────────────────────────────────────────────
Write-Host "[1/5] 数据库..." -ForegroundColor Cyan
# 保活进程防 WSL 空闲关机——演示中途数据库消失是最难堪的故障。
# 先查后起：每次启动无条件再起一个会累积泄漏，且 WSL 从此永不空闲关机（第六轮审阅 P3）
$alive = wsl -d Ubuntu -- pgrep -f 'sleep infinity' 2>$null
if (-not $alive) {
    Start-Process wsl -ArgumentList '-d', 'Ubuntu', '--', 'sleep', 'infinity' -WindowStyle Hidden
}
wsl -d Ubuntu -u root -- service postgresql start | Out-Null
$ready = $false
foreach ($i in 1..20) {
    wsl -d Ubuntu -- pg_isready -h 127.0.0.1 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw "PostgreSQL 未就绪，请检查 WSL Ubuntu" }

if ($Fresh) {
    Write-Host "      重建演示库 $demoDb ..." -ForegroundColor Yellow
    # 必须先停后端并踢掉残留会话：连接还在时 drop 会被 PostgreSQL 拒绝，
    # 而后续 create 又因"已存在"失败——两条都只是 stderr，脚本会照常往下跑，
    # 于是"重来一次"静默变成"接着用旧库"。最需要它的时候失效，正是要防的形态
    Stop-Port 8080
    wsl -d Ubuntu -u postgres -- psql -c `
        "select pg_terminate_backend(pid) from pg_stat_activity where datname='$demoDb';" | Out-Null
    wsl -d Ubuntu -u postgres -- psql -c "drop database if exists $demoDb;" | Out-Null
    wsl -d Ubuntu -u postgres -- psql -c "create database $demoDb owner hip;" | Out-Null
    $left = wsl -d Ubuntu -u postgres -- psql -d $demoDb -tAc `
        "select count(*) from information_schema.tables where table_schema='public'"
    # 校验器自身失败必须现形：[int]$null 与 [int]'' 都是 0，psql 报错时
    # "空库确认"恰在最需要它的时候失明（第六轮审阅 P3）——按退出码 + 严格字符串判定
    if ($LASTEXITCODE -ne 0) { throw "演示库校验查询失败（psql 退出码 $LASTEXITCODE），无法确认重建" }
    if ("$left".Trim() -ne '0') { throw "演示库未能重建（残留 $("$left".Trim()) 张表），请关闭占用连接后重试" }
    Write-Host "      已重建（空库确认）" -ForegroundColor Green
}
else {
    $exists = wsl -d Ubuntu -u postgres -- psql -tAc "select 1 from pg_database where datname='$demoDb'"
    if (-not $exists) {
        wsl -d Ubuntu -u postgres -- psql -c "create database $demoDb owner hip;" | Out-Null
    }
}

# ── 2. 后端（含 AI 辅助侧车）────────────────────────────────
Write-Host "[2/5] 后端（端口 8080）..." -ForegroundColor Cyan
# AI 辅助是独立服务：起得来就能演示 AI 建议，起不来则平台自动走人工兜底——
# 兜底本身就是要讲的产品特性，故此处失败不阻断演示
Stop-Port 8100
Start-Process powershell -ArgumentList '-NoProfile', '-WindowStyle', 'Hidden', '-Command', `
    "cd '$root\ai-service'; python -m uvicorn main:app --port 8100" -WindowStyle Hidden
Stop-Port 8080
$jar = Get-ChildItem "$root\server\target\hip-server-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*sources*' } | Select-Object -First 1
if (-not $jar) {
    throw "未找到后端产物，请先构建：mvn -pl server -am package -DskipTests"
}
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:5432/$demoDb"
Start-Process java -ArgumentList '-jar', $jar.FullName, '--spring.profiles.active=dev' `
    -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput "$root\demo-server.log" `
    -RedirectStandardError "$root\demo-server.err.log"

$up = $false
foreach ($i in 1..90) {
    try {
        $r = Invoke-RestMethod 'http://localhost:8080/actuator/health' -TimeoutSec 2
        if ($r.status -eq 'UP') { $up = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $up) { throw "后端未就绪，见 demo-server.log" }
Write-Host "      就绪（数据库迁移已自动完成）" -ForegroundColor Green
$aiUp = $false
try { Invoke-WebRequest 'http://localhost:8100/docs' -TimeoutSec 3 -UseBasicParsing | Out-Null; $aiUp = $true } catch { }
if ($aiUp) { Write-Host "      AI 辅助服务就绪（端口 8100）" -ForegroundColor Green }
else { Write-Host "      AI 辅助未起，演示时将展示人工兜底路径" -ForegroundColor DarkGray }

# ── 3. 演示数据 ──────────────────────────────────────────────
Write-Host "[3/5] 演示数据..." -ForegroundColor Cyan
Push-Location $root
try {
    # 与下方 flows 循环同样的防护：python 写 stderr 即 NativeCommandError，
    # 在 ErrorActionPreference=Stop 下会掐断整个脚本（第六轮审阅 P2-C，此前只防了 E2E 一侧）
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    python tools\bootstrap-demo.py 2>&1 | Select-Object -Last 2 | ForEach-Object { Write-Host "      $_" }
    if ($LASTEXITCODE -ne 0) { Write-Host "      !! 演示数据引导未完成（见上），继续启动但部分页面可能无数据" -ForegroundColor Yellow }
    $ErrorActionPreference = $prevEap
    # 跑几条真实业务链路，让驾驶舱与各工作台有流水可看——空界面是演示第一杀手。
    # 走 E2E 而不是直接灌库：数据经过真实状态机，各表状态自洽，点开任何详情都对得上
    # 按 CI 的完整顺序跑全部业务链路：既让每个模块的演示页都有数据，
    # 也避开隐式顺序依赖——patient 360 要求同一患者兼有门诊/检验/住院文档，
    # 检验那部分由 integration 的 HL7 ORU 产生，少跑一套它就断言失败
    # （docs/测试方法论.md 第 ⑦ 条）。哈希表无序，故用有序数组
    Write-Host "      按 CI 顺序跑 20 条业务链路（约 1-2 分钟）..." -ForegroundColor DarkGray
    $flows = @(
        @{ script = 'e2e-outpatient.py'; label = '门诊闭环（挂号→收费→发药→退费）' }
        @{ script = 'e2e-inpatient.py';  label = '住院与进销存' }
        @{ script = 'e2e-integration.py'; label = '集成引擎（医保留痕 + HL7 检验回传）' }
        @{ script = 'e2e-mllp.py';       label = 'MLLP/TCP 接口' }
        @{ script = 'e2e-phase3.py';     label = 'CDR 归集与患者 360' }
        @{ script = 'e2e-phase48.py';    label = '叫号 / 转科 / 病历签名 / 合理用药' }
        @{ script = 'e2e-phase912.py';   label = '护理白板 / 时限质控 / 不良事件' }
        @{ script = 'e2e-phase1316.py';  label = '打印数据 / 日结 CSV / 审计' }
        @{ script = 'e2e-phase1821.py';  label = '机构参数化 / 模块下沉 / AI 辅助' }
        @{ script = 'e2e-phase2226.py';  label = '运维保障 / 临床闭环' }
        @{ script = 'e2e-insurance.py';  label = '医保目录对照与费用分割' }
        @{ script = 'e2e-phase2931.py';  label = '扫码支付 / 患者端报告' }
        @{ script = 'e2e-phase3234.py';  label = '设备全生命周期 / 采购' }
        @{ script = 'e2e-phase3537.py';  label = 'HR 人事 / 资产处置 / 价格' }
        @{ script = 'e2e-phase38.py';    label = '多角色菜单矩阵与演示账号' }
        @{ script = 'e2e-phase3940.py';  label = '护理风险评估（Braden / Morse）' }
        @{ script = 'e2e-product1.py';   label = '模块开关 / 字典 CSV' }
        @{ script = 'e2e-101.py';        label = '每日清单 / 审方' }
        @{ script = 'e2e-drg-cdss.py';   label = 'DRG 入组与 CDSS' }
        @{ script = 'e2e-emr-stats.py';  label = '住院病历 / 体征 / 统计驾驶舱' }
    )
    $failed = @()
    foreach ($f in $flows) {
        # python 往 stderr 写字即被 PowerShell 判为 NativeCommandError，
        # 在 ErrorActionPreference=Stop 下会掐断整个脚本——此处按退出码判定
        $prev = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & python "tools\$($f.script)" 2>&1 | Out-Null
        $ok = ($LASTEXITCODE -eq 0)
        $ErrorActionPreference = $prev
        if (-not $ok) {
            $failed += $f.label
            Write-Host "      !!  $($f.label)" -ForegroundColor Yellow
        }
    }
    if ($failed.Count -eq 0) {
        Write-Host "      20 条链路全部通过 —— 各模块演示页均有真实流水" -ForegroundColor Green
    }
    else {
        Write-Host "      $($failed.Count) 条未通过（上列），对应模块的演示页可能无数据" -ForegroundColor Yellow
    }
}
finally { Pop-Location }

# ── 4. 前端 ──────────────────────────────────────────────────
Write-Host "[4/5] 前端（端口 5173）..." -ForegroundColor Cyan
Stop-Port 5173
Start-Process powershell -ArgumentList '-NoProfile', '-WindowStyle', 'Hidden', '-Command', `
    "cd '$root\frontend\shell'; npm run dev" -WindowStyle Hidden
foreach ($i in 1..40) {
    try { Invoke-WebRequest 'http://localhost:5173' -TimeoutSec 2 -UseBasicParsing | Out-Null; break } catch { }
    Start-Sleep -Seconds 1
}

# ── 5. 打开浏览器 ────────────────────────────────────────────
Write-Host "[5/5] 打开演示页面..." -ForegroundColor Cyan
Start-Process 'http://localhost:5173'

Write-Host ""
Write-Host "  演示环境就绪   http://localhost:5173" -ForegroundColor Green
Write-Host "  管理员      admin / admin123（可见全部菜单）"
Write-Host "  角色账号    doctor01 / nurse01 / cashier01 / pharm01 / tech01 / quality01 / ops01"
Write-Host "              统一密码 Demo1234 —— 按角色登录可展示「每个岗位看到的不是同一套系统」"
Write-Host "  演示库 $demoDb —— 与开发库 hip 隔离，讲砸了用 -Fresh 重来"
Write-Host "  结束后：tools\demo-start.ps1 -Stop"
Write-Host ""
