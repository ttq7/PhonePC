# Headless Android emulator control for real-device verification of PhonePC


# Actions: create | accel | run | wait | stop | status
# Everything (AVD home, logs) stays inside the workspace.

param(
    [ValidateSet('create', 'accel', 'run', 'wait', 'stop', 'status')]
    [string]$Action = 'status',
    [string]$AvdName = 'phonepc',
    [string]$Api = 'android-35',
    [string]$Abi = 'x86_64',
    [string]$Device = 'pixel_6',
    [int]$Port = 5554,
    [int]$BootTimeoutMin = 15
)

$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }

$ErrorActionPreference = 'Continue'

$sdk     = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$avdHome = Join-Path $root '.android-home\avd'

$env:JAVA_HOME        = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot'
$env:ANDROID_HOME     = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_AVD_HOME = $avdHome
New-Item -ItemType Directory -Force -Path $avdHome | Out-Null

$avdManager = Join-Path $sdk 'cmdline-tools\latest\bin\avdmanager.bat'
$emulator   = Join-Path $sdk 'emulator\emulator.exe'
$adb        = Join-Path $sdk 'platform-tools\adb.exe'
$dev        = "emulator-$Port"

switch ($Action) {

    'accel' {
        & $emulator -accel-check 2>&1 | ForEach-Object { $_ }
    }

    'create' {
        $ini = Join-Path $avdHome "$AvdName.ini"
        if (Test-Path $ini) { "AVD $AvdName already exists"; break }
        'no' | & $avdManager create avd -n $AvdName `
            -k "system-images;$Api;google_apis;$Abi" -d $Device --force 2>&1 |
            ForEach-Object { $_ }
        "created AVD $AvdName at $avdHome"
        # keep it lean
        $cfg = Join-Path $avdHome "$AvdName.avd\config.ini"
        if (Test-Path $cfg) {
            Add-Content $cfg "`nhw.gpu.mode=swiftshader_indirect"
            Add-Content $cfg "hw.keyboard=yes"
            Add-Content $cfg "disk.dataPartition.size=4096M"
        }
    }

    'run' {
        $emuLog = Join-Path $root 'tools\emulator.log'
        if (Test-Path $emuLog) { Remove-Item $emuLog -Force }
        "starting emulator $AvdName (headless, port $Port)"
        # foreground: this process must stay alive for the emulator to survive
        & $emulator -avd $AvdName -port $Port `
            -no-window -no-audio -no-boot-anim -no-snapshot `
            -gpu swiftshader_indirect -memory 2048 -cores 4 `
            -netdelay none -netspeed full *>&1 |
            Tee-Object -FilePath $emuLog
        "emulator exited with $LASTEXITCODE"
    }

    'wait' {
        "waiting for $dev"
        & $adb -s $dev wait-for-device 2>&1 | ForEach-Object { $_ }
        $deadline = (Get-Date).AddMinutes($BootTimeoutMin)
        $booted = $false
        while ((Get-Date) -lt $deadline) {
            $v = (& $adb -s $dev shell getprop sys.boot_completed 2>$null) -join ''
            if ($v.Trim() -eq '1') { $booted = $true; break }
            Start-Sleep -Seconds 5
        }
        if ($booted) {
            "BOOT COMPLETED"
            & $adb -s $dev shell wm size
            & $adb -s $dev shell wm density
            "release: " + ((& $adb -s $dev shell getprop ro.build.version.release 2>$null) -join '')
        } else {
            "BOOT TIMED OUT"
            $emuLog = Join-Path $root 'tools\emulator.log'
            if (Test-Path $emuLog) { Get-Content $emuLog -Tail 40 }
        }
    }

    'stop' {
        & $adb -s $dev emu kill 2>&1 | ForEach-Object { $_ }
        Start-Sleep -Seconds 3
        Get-Process -Name 'qemu-system-x86_64*', 'emulator' -ErrorAction SilentlyContinue |
            ForEach-Object { "still running: $($_.Name) pid=$($_.Id)" }
    }

    'status' {
        & $adb devices -l 2>&1 | ForEach-Object { $_ }
        "--- avds ---"
        Get-ChildItem $avdHome -Filter '*.ini' -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty BaseName
        "--- emulator processes ---"
        Get-Process -Name 'qemu-system-x86_64*', 'emulator' -ErrorAction SilentlyContinue |
            Select-Object Id, Name, @{n='MB';e={[math]::Round($_.WorkingSet64/1MB)}}
    }
}
