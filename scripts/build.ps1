param([switch]$Offline, [switch]$AllowDependencyDownloads)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = Join-Path $projectRoot '.tools\java'
$env:ANDROID_HOME = Join-Path $projectRoot '.tools\sdk'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools\android-user'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools\gradle-cache'
$taskTemp = Join-Path $projectRoot '.tools\tmp'
New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null
$env:TEMP = $taskTemp
$env:TMP = $taskTemp
# Keep Java's local socket files away from long or virtualized AppData paths.
$env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS + ' -Djdk.net.unixdomain.tmpdir=' + $taskTemp).Trim())
$gradle = Join-Path $projectRoot '.tools\gradle\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) { throw 'Run scripts/setup_tools.py first. See README.md.' }
Push-Location $projectRoot
try {
    $tasks = @('--console=plain', 'assembleDebug', 'lintDebug')
    if ($Offline -and $AllowDependencyDownloads) { throw 'Choose Offline or AllowDependencyDownloads, not both.' }
    if (-not $AllowDependencyDownloads) { $tasks += '--offline' }
    & $gradle @tasks
    if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE" }
    New-Item -ItemType Directory -Force -Path 'dist' | Out-Null
    Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination 'dist\x-feed-guard-0.7.0-debug.apk' -Force
    Get-FileHash -LiteralPath 'dist\x-feed-guard-0.7.0-debug.apk' -Algorithm SHA256
} finally { Pop-Location }
