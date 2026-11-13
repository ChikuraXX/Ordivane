# 官方 Wrapper 的旧版 Windows PowerShell 下载受本机代理影响时，预热同一校验过的分发缓存。
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/env.ps1"
$properties = Get-Content "$PSScriptRoot/../.mvn/wrapper/maven-wrapper.properties" -Raw | ConvertFrom-StringData
$url = $properties.distributionUrl
$download = "$PSScriptRoot/.tools/maven-wrapper.zip"
Invoke-WebRequest $url -OutFile $download -TimeoutSec 180
if ((Get-FileHash $download).Hash.ToLowerInvariant() -ne $properties.distributionSha256Sum) {
    throw 'Maven 分发包 SHA-256 不匹配。'
}
$hash = ([System.Security.Cryptography.SHA256]::Create().ComputeHash([byte[]][char[]]$url) | ForEach-Object { $_.ToString('x2') }) -join ''
$destination = "$env:MAVEN_USER_HOME/wrapper/dists/apache-maven-3.10.0/$hash"
if (-not (Test-Path $destination)) {
    $expanded = "$PSScriptRoot/.tools/wrapper-extract"
    Expand-Archive $download $expanded -Force
    New-Item -ItemType Directory $destination -Force | Out-Null
    Copy-Item "$expanded/apache-maven-3.10.0/*" $destination -Recurse
}
