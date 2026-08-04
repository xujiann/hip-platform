# 二十二期：数据库恢复演练（恢复到独立校验库，不动生产库）
# 用法：powershell -ExecutionPolicy Bypass -File tools\db-restore.ps1 -File deploy\backups\hip_xxx.dump [-TargetDb hip_restore_check]
# 恢复到生产库请先停应用，并把 -TargetDb 指向 hip（需人工二次确认，本脚本默认拒绝）
param(
    [Parameter(Mandatory = $true)][string]$File,
    [string]$TargetDb = 'hip_restore_check'
)

if ($TargetDb -eq 'hip') { Write-Error '拒绝直接覆盖生产库 hip；请手工执行并确认'; exit 1 }
if (-not (Test-Path $File)) { Write-Error "备份文件不存在：$File"; exit 1 }

$full = (Resolve-Path $File).Path
$drive = $full.Substring(0, 1).ToLower()
$wslPath = "/mnt/$drive" + ($full.Substring(2) -replace '\\', '/')

Write-Host "[1/3] 重建校验库 $TargetDb ..."
wsl -d Ubuntu -u root -- su postgres -c "dropdb --if-exists $TargetDb"
wsl -d Ubuntu -u root -- su postgres -c "createdb -O hip $TargetDb"
if ($LASTEXITCODE -ne 0) { Write-Error 'createdb 失败'; exit 1 }

Write-Host "[2/3] pg_restore ..."
wsl -d Ubuntu -- sh -c "PGPASSWORD=hip123456 pg_restore -h 127.0.0.1 -U hip -d $TargetDb --no-owner '$wslPath'"

Write-Host "[3/3] 校验关键表 ..."
$users = wsl -d Ubuntu -- sh -c "PGPASSWORD=hip123456 psql -h 127.0.0.1 -U hip -d $TargetDb -tAc 'select count(*) from sys_user'"
Write-Host "恢复校验：sys_user 共 $users 行（>0 即演练通过）"
if ([int]$users -lt 1) { exit 1 }
