# v1.7 UX verification: dark mode, undo strip, progress, restricted folder.
param([int]$Port = 5554)

$ErrorActionPreference = 'Continue'
$root = 'E:\此电脑'
$sdk  = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb  = Join-Path $sdk 'platform-tools\adb.exe'
$dev  = "emulator-$Port"
$dir  = Join-Path $root 'preview\ux'
if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dir | Out-Null

function A { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { A shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 1100 }
function Long([int]$x, [int]$y) { A shell input swipe $x $y $x $y 1100 | Out-Null; Start-Sleep -Milliseconds 1200 }
function Key([int]$k) { A shell input keyevent $k | Out-Null; Start-Sleep -Milliseconds 900 }
function Shot([string]$n) {
    $r = "/sdcard/_u_$n.png"
    A shell screencap -p $r | Out-Null
    $l = Join-Path $dir "$n.png"
    if (Test-Path $l) { Remove-Item $l -Force }
    A pull $r $l 2>&1 | Out-Null
    A shell rm $r | Out-Null
    if (Test-Path $l) { "  $n  ($((Get-Item $l).Length) bytes)" }
}

A shell am force-stop com.phonepc.explorer | Out-Null
Start-Sleep -Milliseconds 600
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 4

"== light mode: restricted folder (Android/data) =="
Tap 954 2333
Tap 72 178; Tap 200 1121          # 内部存储
Start-Sleep -Seconds 2
# scroll down to Android/ and open it, then data/
Tap 320 921                        # Android folder (row 3 of the sorted list)
Start-Sleep -Seconds 2
Shot 'u1-android-dir'
Tap 320 522
Start-Sleep -Seconds 2
Shot 'u2-android-data'
A shell "dumpsys window | grep -i mCurrentFocus"

"== dark mode =="
Key 4; Key 4; Key 4
A shell cmd uimode night yes | Out-Null
Start-Sleep -Seconds 3
A shell am force-stop com.phonepc.explorer | Out-Null
Start-Sleep -Milliseconds 800
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 4
Shot 'u3-dark-thispc'
Tap 72 178; Tap 200 1121
Start-Sleep -Seconds 2
Shot 'u4-dark-folder'
