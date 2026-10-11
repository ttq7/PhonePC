# Install PhonePC on the running emulator, grant all-files access, seed some
# sample content, launch it, and capture screenshots.

param(
    [int]$Port = 5554,
    [string]$Action = 'deploy'
)

$ErrorActionPreference = 'Continue'

$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }
$sdk  = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb  = Join-Path $sdk 'platform-tools\adb.exe'
$dev  = "emulator-$Port"
$pkg  = 'com.phonepc.explorer'
$apk  = Join-Path $root 'PhonePC\app\build\outputs\apk\debug\app-debug.apk'
$shotDir = Join-Path $root 'preview\shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null

function Adb { & $adb -s $dev @args }

function Shot([string]$name) {
    $remote = "/sdcard/_shot_$name.png"
    Adb shell screencap -p $remote | Out-Null
    $local = Join-Path $shotDir "$name.png"
    if (Test-Path $local) { Remove-Item $local -Force }
    Adb pull $remote $local | Out-Null
    Adb shell rm $remote | Out-Null
    if (Test-Path $local) { "shot: $local  ($((Get-Item $local).Length) bytes)" }
    else { "shot FAILED: $name" }
}

function Seed {
    # Run an on-device script so non-ASCII filenames never pass through a
    # Windows command line (PowerShell 5.1 mangles them to ANSI).
    $script = Join-Path $root 'tools\seed.sh'
    Adb push $script /data/local/tmp/seed.sh 2>&1 | Out-Null
    Adb shell sh /data/local/tmp/seed.sh 2>&1 | Select-Object -Last 40
}

switch ($Action) {

    'deploy' {
        "=== install ==="
        Adb install -r $apk 2>&1 | Select-Object -Last 3

        "=== reset state (start from the device root) ==="
        Adb shell pm clear $pkg 2>&1 | Out-Null

        "=== grant all-files access ==="
        Adb shell appops set $pkg MANAGE_EXTERNAL_STORAGE allow 2>&1 | Out-Null
        Adb shell pm grant $pkg android.permission.READ_MEDIA_IMAGES 2>&1 | Out-Null
        Adb shell pm grant $pkg android.permission.READ_MEDIA_VIDEO 2>&1 | Out-Null
        Adb shell pm grant $pkg android.permission.READ_MEDIA_AUDIO 2>&1 | Out-Null
        Adb shell appops get $pkg MANAGE_EXTERNAL_STORAGE

        "=== seed content ==="
        Seed

        "=== launch ==="
        Adb shell am force-stop $pkg | Out-Null
        Adb shell am start -n "$pkg/.MainActivity" 2>&1 | Select-Object -Last 2
        Start-Sleep -Seconds 6
        Adb shell "dumpsys window | grep -i mCurrentFocus"
        Shot '01-initial'
    }

    'shot' {
        Shot "shot-$([DateTime]::Now.ToString('HHmmss'))"
    }

    'log' {
        Adb logcat -d -t 300 2>&1 | Select-String -Pattern 'phonepc|AndroidRuntime|FATAL' |
            Select-Object -Last 60 | ForEach-Object { $_.Line }
    }
}