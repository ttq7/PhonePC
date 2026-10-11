# Deterministic UI tour starting from the device root.
param([int]$Port = 5554)

$ErrorActionPreference = 'Continue'
$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }
$sdk  = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb  = Join-Path $sdk 'platform-tools\adb.exe'
$dev  = "emulator-$Port"
$shotDir = Join-Path $root 'preview\shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null

function Adb { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { Adb shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 900 }
function LongPress([int]$x, [int]$y) { Adb shell input swipe $x $y $x $y 1100 | Out-Null; Start-Sleep -Milliseconds 1000 }
function Key([int]$k) { Adb shell input keyevent $k | Out-Null; Start-Sleep -Milliseconds 900 }
function Shot([string]$name) {
    $remote = "/sdcard/_s_$name.png"
    Adb shell screencap -p $remote | Out-Null
    $local = Join-Path $shotDir "$name.png"
    if (Test-Path $local) { Remove-Item $local -Force }
    Adb pull $remote $local 2>&1 | Out-Null
    Adb shell rm $remote | Out-Null
    if (Test-Path $local) { "  shot $name  ($((Get-Item $local).Length) bytes)" }
}
$ROW = { param($i) 522 + ($i * 133) }

"== make sure the app is foreground =="
Adb shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 3
Shot '20-thispc'

"== enter 内部存储 =="
Tap 320 (& $ROW 0)
Shot '21-storage'

"== view menu =="
Tap 967 178
Shot '22-view-menu'
Key 4

"== enter Download (row 6) =="
Tap 320 (& $ROW 5)
Shot '23-download'

"== overflow menu (normal mode) =="
Tap 1006 415
Shot '24-overflow'
Key 4

"== selection mode =="
LongPress 320 (& $ROW 1)
Shot '25-selection'

"== overflow in selection mode =="
Tap 1006 415
Shot '26-overflow-sel'
Key 4

"== clear selection, nav pane =="
Key 4
Tap 72 178
Shot '27-nav-pane'
Key 4

"== properties dialog =="
LongPress 320 (& $ROW 1)
Tap 1006 415
Start-Sleep -Milliseconds 600
Shot '28-more-menu'
Key 4
Key 4

"== search =="
Tap 900 318
Adb shell input text "jpg" | Out-Null
Start-Sleep -Seconds 2
Shot '29-search'
Adb shell input keyevent 67 | Out-Null
Adb shell input keyevent 67 | Out-Null
Adb shell input keyevent 67 | Out-Null
Start-Sleep -Milliseconds 800
Shot '30-download-clean'

"== done =="
