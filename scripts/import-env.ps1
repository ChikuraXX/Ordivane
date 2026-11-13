param([string]$Path = "$PSScriptRoot/../.env")
# 仅支持 KEY=value；不执行文件中的表达式或脚本。
$ErrorActionPreference = 'Stop'
foreach ($line in Get-Content -LiteralPath $Path) {
    if ($line -match '^\s*(#|$)') { continue }
    if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw '环境文件含不支持的行，要求 KEY=value。' }
    [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
}
