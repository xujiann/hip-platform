# 启动 Docker Desktop（绕过本机 Unix socket 无法删除的 bug）
# 背景：本机删除已存在的 unix socket 文件会报 "The file cannot be accessed by the system"，
# 导致 Docker Desktop 启动时清理旧 socket 失败而崩溃。
# 方案：启动前把含残留 socket 的目录整体改名移开（目录改名不受该 bug 影响）。

$ErrorActionPreference = 'SilentlyContinue'
Stop-Process -Name 'com.docker.backend', 'Docker Desktop', 'docker-ai', 'docker-mcp' -Force
Start-Sleep 2

$stamp = Get-Date -Format 'MMddHHmmss'
foreach ($dir in @("$env:LOCALAPPDATA\Docker\run", "$env:LOCALAPPDATA\docker-secrets-engine")) {
    if (Test-Path $dir) {
        cmd /c "move `"$dir`" `"${dir}_broken_$stamp`"" | Out-Null
    }
}
# 清理能删掉的历史残留（socket 所在残壳删不掉就留着，不影响）
Get-ChildItem "$env:LOCALAPPDATA\Docker" -Filter 'run_broken*' -Directory |
    ForEach-Object { cmd /c "rd /s /q `"$($_.FullName)`"" | Out-Null }
Get-ChildItem "$env:LOCALAPPDATA" -Filter 'docker-secrets-engine_b*' -Directory |
    ForEach-Object { cmd /c "rd /s /q `"$($_.FullName)`"" | Out-Null }

Start-Process 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
Write-Host '等待 Docker 引擎就绪...' -ForegroundColor Cyan
$env:DOCKER_HOST = $null
for ($i = 0; $i -lt 100; $i++) {
    Start-Sleep 3
    docker --context desktop-linux info --format ok 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { Write-Host '引擎已就绪' -ForegroundColor Green; exit 0 }
}
Write-Host '等待超时，请检查 Docker Desktop 界面' -ForegroundColor Red
exit 1
