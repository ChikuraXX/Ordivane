param([Parameter(Mandatory)][ValidateSet('platform', 'demo')][string]$Application)
# 前台启动便于明确管理进程；使用 Ctrl+C 停止。
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
. "$PSScriptRoot/import-env.ps1"
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot
& "$env:JAVA_HOME/bin/java.exe" -jar "ordivane-$Application/target/ordivane-$Application-0.1.0-SNAPSHOT.jar"
if ($LASTEXITCODE -ne 0) { throw "$Application 启动失败，退出码 $LASTEXITCODE。" }
