# 二十二期：数据库自动备份（WSL postgres → deploy/backups/，并调用平台接口留痕）
# 用法：powershell -ExecutionPolicy Bypass -File tools\db-backup.ps1 [-Token <jwt>]
# 定时：可用 Windows 任务计划程序每日调用；服务器容器部署时改用 crontab + pg_dump 同逻辑
param([string]$Token = '')

$root = Split-Path $PSScriptRoot -Parent
$backupDir = Join-Path $root 'deploy\backups'
if (-not (Test-Path $backupDir)) { New-Item -ItemType Directory -Force $backupDir | Out-Null }

$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$file = "hip_$stamp.dump"
$wslTmp = "/tmp/$file"

Write-Host "[1/3] pg_dump 到 WSL $wslTmp ..."
wsl -d Ubuntu -- sh -c "PGPASSWORD=hip123456 pg_dump -h 127.0.0.1 -U hip -d hip -F c -f $wslTmp"
if ($LASTEXITCODE -ne 0) { Write-Error 'pg_dump 失败'; exit 1 }

Write-Host "[2/3] 拷贝到 $backupDir\$file ..."
$drive = $backupDir.Substring(0, 1).ToLower()
$winDest = "/mnt/$drive" + ($backupDir.Substring(2) -replace '\\', '/')
wsl -d Ubuntu -- cp $wslTmp "$winDest/$file"
wsl -d Ubuntu -- rm -f $wslTmp
$size = (Get-Item (Join-Path $backupDir $file)).Length
Write-Host "备份完成：$file（$([math]::Round($size/1KB)) KB）"

if ($Token) {
    Write-Host "[3/3] 平台留痕 ..."
    $body = @{ fileName = $file; sizeBytes = $size; status = 'SUCCESS'; note = 'db-backup.ps1 自动备份' } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/ops/backups' `
        -Headers @{ Authorization = "Bearer $Token" } -ContentType 'application/json' -Body $body | Out-Null
    Write-Host '已写入备份台账'
}
