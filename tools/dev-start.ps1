# 一键启动本地开发环境（PowerShell）
# 用法：powershell -ExecutionPolicy Bypass -File tools\dev-start.ps1

$root = Split-Path $PSScriptRoot -Parent

Write-Host "[1/3] 启动数据库 (WSL Ubuntu PostgreSQL)..." -ForegroundColor Cyan
# 保活进程防止 WSL 空闲关机（本机 Docker Desktop 不稳定，暂用 WSL 原生 postgres）
Start-Process wsl -ArgumentList '-d', 'Ubuntu', '--', 'sleep', 'infinity' -WindowStyle Hidden
wsl -d Ubuntu -u root -- service postgresql start

Write-Host "[2/3] 启动后端 (端口 8080)..." -ForegroundColor Cyan
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:USERPROFILE\tools\apache-maven-3.9.16\bin;$env:Path"
Start-Process powershell -ArgumentList '-NoExit', '-Command', "cd '$root'; mvn -pl server -am spring-boot:run"

Write-Host "[3/3] 启动前端 (端口 5173)..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList '-NoExit', '-Command', "cd '$root\frontend\shell'; npm run dev"

Write-Host "完成。访问 http://localhost:5173 ，默认账号 admin/admin123" -ForegroundColor Green
