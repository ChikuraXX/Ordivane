# 在项目根目录点源此脚本；所有设置仅作用于当前 PowerShell 进程。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$jdkDirectory = Get-ChildItem "$PSScriptRoot/.tools/jdk" -Directory | Select-Object -First 1
$nodeDirectory = Get-ChildItem "$PSScriptRoot/.tools/node" -Directory | Select-Object -First 1
if (-not $jdkDirectory -or -not $nodeDirectory) { throw '请先按 docs/toolchain.md 安装项目工具链。' }
$env:JAVA_HOME = $jdkDirectory.FullName
$env:PATH = "$($jdkDirectory.FullName)/bin;$($nodeDirectory.FullName);$env:PATH"
$env:MAVEN_USER_HOME = "$projectRoot/.mvn"
$env:GRAPHIFY_NO_AUTO_REFRESH = '1'
$env:npm_config_cache = "$PSScriptRoot/.tools/npm-cache"
$env:ORDIVANE_PROXY_ENABLED = 'false'
$env:ORDIVANE_PROXY_HOST = 'localhost'
$env:ORDIVANE_PROXY_PORT = '80'
if ($env:HTTPS_PROXY) {
    $proxyUri = [uri]$env:HTTPS_PROXY
    if ($proxyUri.UserInfo) { throw '当前代理含身份信息，请另行配置私有 Maven settings。' }
    $env:ORDIVANE_PROXY_ENABLED = 'true'
    $env:ORDIVANE_PROXY_HOST = $proxyUri.Host
    $env:ORDIVANE_PROXY_PORT = $proxyUri.Port.ToString()
    $env:npm_config_https_proxy = $env:HTTPS_PROXY
}
