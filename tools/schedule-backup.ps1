# 三十七期：注册每日自动备份任务（Windows 任务计划程序）
# 用法（管理员 PowerShell 手工执行）：powershell -ExecutionPolicy Bypass -File tools\schedule-backup.ps1
# 注销任务：Unregister-ScheduledTask -TaskName 'HIP-DailyBackup' -Confirm:$false

$root = Split-Path $PSScriptRoot -Parent
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-ExecutionPolicy Bypass -File `"$root\tools\db-backup.ps1`""
$trigger = New-ScheduledTaskTrigger -Daily -At '02:30'
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -WakeToRun

Register-ScheduledTask -TaskName 'HIP-DailyBackup' -Action $action -Trigger $trigger `
    -Settings $settings -Description 'HIP 平台数据库每日自动备份（tools/db-backup.ps1）' -Force

Write-Host '已注册任务计划 HIP-DailyBackup（每日 02:30），恢复演练请定期执行 tools\db-restore.ps1'
