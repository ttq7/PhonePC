# Build the PhonePC debug APK from the confined sandbox.
# Keeps GRADLE_USER_HOME / ANDROID_USER_HOME inside the workspace so no
# elevated file access is ever needed.

param(
    [string]$Task = 'assembleDebug',
    [switch]$Clean
)

$ErrorActionPreference = 'Continue'
$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }
$proj = Join-Path $root 'PhonePC'
$log  = Join-Path $root 'tools\build.log'

$env:GRADLE_USER_HOME  = Join-Path $root '.gradle-home'
$env:ANDROID_USER_HOME = Join-Path $root '.android-home'
$env:ANDROID_HOME      = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$env:ANDROID_SDK_ROOT  = $env:ANDROID_HOME

$gradle = 'C:\Users\asd32\AppData\Local\TyranoToolKit\gradle\gradle-8.9\bin\gradle.bat'

$args = @('-p', $proj, '--console=plain', '--no-daemon')
if ($Clean) { $args += 'clean' }
$args += $Task

& $gradle @args *>&1 | Tee-Object -FilePath $log | Out-Null
$code = $LASTEXITCODE
"exit code: $code"
"log: $log"

if ($code -ne 0) {
    "==================== errors ===================="
    Get-Content $log |
        Select-String -Pattern '^e: |error:|FAILURE|What went wrong' |
        ForEach-Object { $_.Line } |
        Select-Object -Unique |
        Select-Object -First 90
}