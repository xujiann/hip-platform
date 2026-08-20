# 二十二期：数据库自动备份（WSL postgres → deploy/backups/，并调用平台接口留痕）
# 用法：powershell -ExecutionPolicy Bypass -File tools\db-backup.ps1 [-Token <jwt>] [-Db hip]
# -Db：库名（1.2.6 v24-B 参数化——多院部署每院库名不同，原先硬编码 hip 备的永远是另一个库）
# 定时：可用 Windows 任务计划程序每日调用；服务器容器部署时改用 crontab + pg_dump 同逻辑
param([string]$Token = '', [string]$Db = 'hip')

$root = Split-Path $PSScriptRoot -Parent
$backupDir = Join-Path $root 'deploy\backups'
if (-not (Test-Path $backupDir)) { New-Item -ItemType Directory -Force $backupDir | Out-Null }

$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$file = "${Db}_$stamp.dump"
$wslTmp = "/tmp/$file"

Write-Host "[1/3] pg_dump 到 WSL $wslTmp ..."
wsl -d Ubuntu -- sh -c "PGPASSWORD=hip123456 pg_dump -h 127.0.0.1 -U hip -d $Db -F c -f $wslTmp"
if ($LASTEXITCODE -ne 0) { Write-Error 'pg_dump 失败'; exit 1 }

Write-Host "[2/3] 拷贝到 $backupDir\$file ..."
# 路径换算在 WSL 侧一次完成（1.2.6 v24-B）：原先把 Windows 路径当参数传给 wsl，
# 反斜杠在传参过程中被吞成 "C:Usersdrxuj..."，wslpath 必失败 → 备份永远走不到拷贝步。
# 拷贝失败必须保留源 dump 并中止——否则会出现
# 「备份文件不存在、台账却记 SUCCESS」，灾难恢复时才发现。
$drive = $backupDir.Substring(0,1).ToLower()
$wslDest = "/mnt/$drive" + $backupDir.Substring(2).Replace([char]92, [char]47)
wsl -d Ubuntu -- cp $wslTmp "$wslDest/$file"
if ($LASTEXITCODE -ne 0) { Write-Error "拷贝失败（dump 保留在 $wslTmp，请手工取回）"; exit 1 }
$dest = Join-Path $backupDir $file
if (-not (Test-Path $dest)) { Write-Error "目标文件不存在：$dest（dump 保留在 $wslTmp）"; exit 1 }
$size = (Get-Item $dest).Length
if ($size -le 0) { Write-Error "备份文件为空：$dest（dump 保留在 $wslTmp）"; exit 1 }
wsl -d Ubuntu -- rm -f $wslTmp          # 确认落盘后才删源
Write-Host "备份完成：$file（$([math]::Round($size/1KB)) KB）"

# 保留策略：默认 30 天，避免无限累积占满磁盘
Get-ChildItem $backupDir -Filter 'hip_*.dump' |
    Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-30) } |
    ForEach-Object { Write-Host "清理过期备份：$($_.Name)"; Remove-Item $_.FullName -Force }

if ($Token) {
    Write-Host "[3/3] 平台留痕 ..."
    $body = @{ fileName = $file; sizeBytes = $size; status = 'SUCCESS'; note = 'db-backup.ps1 自动备份' } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/ops/backups' `
        -Headers @{ Authorization = "Bearer $Token" } -ContentType 'application/json' -Body $body | Out-Null
    Write-Host '已写入备份台账'
}
