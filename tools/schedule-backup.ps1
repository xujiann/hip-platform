# 三十七期：注册每日自动备份任务（Windows 任务计划程序）
# 用法（管理员 PowerShell 手工执行）：powershell -ExecutionPolicy Bypass -File tools\schedule-backup.ps1
# 注销任务：Unregister-ScheduledTask -TaskName 'HIP-DailyBackup' -Confirm:$false

$root = Split-Path $PSScriptRoot -Parent
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-ExecutionPolicy Bypass -File `"$root\tools\db-backup.ps1`""
$trigger = New-ScheduledTaskTrigger -Daily -At '02:30'
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -WakeToRun

# -LogonType S4U：不要求用户登录即可运行。默认的交互令牌在服务器无人登录的凌晨 2:30 不会触发，
# -StartWhenAvailable 只补「错过的触发」，救不了登录态要求。
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType S4U -RunLevel Highest

Register-ScheduledTask -TaskName 'HIP-DailyBackup' -Action $action -Trigger $trigger `
    -Settings $settings -Principal $principal `
    -Description 'HIP 平台数据库每日自动备份（tools/db-backup.ps1）' -Force

Write-Host '已注册任务计划 HIP-DailyBackup（每日 02:30，S4U 无需登录），恢复演练请定期执行 tools\db-restore.ps1'
Write-Host '提示：备份台账留痕需 db-backup.ps1 带 -Token 参数；如需台账请在任务参数中补 -Token <jwt>'
