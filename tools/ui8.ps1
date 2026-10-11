# Final v1.4 capture, with the view mode pinned to details where needed.
param([int]$Port = 5554)

$ErrorActionPreference = 'Continue'
$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }
$sdk  = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb  = Join-Path $sdk 'platform-tools\adb.exe'
$dev  = "emulator-$Port"
$dir  = Join-Path $root 'preview\catch'
if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dir | Out-Null

function A { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { A shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 1000 }
function Long([int]$x, [int]$y) { A shell input swipe $x $y $x $y 1100 | Out-Null; Start-Sleep -Milliseconds 1100 }
function Key([int]$k) { A shell input keyevent $k | Out-Null; Start-Sleep -Milliseconds 1000 }
function Down([int]$n) { for ($i = 0; $i -lt $n; $i++) { A shell input keyevent 20 | Out-Null; Start-Sleep -Milliseconds 140 } }
function Shot([string]$n) {
    $r = "/sdcard/_f_$n.png"
    A shell screencap -p $r | Out-Null
    $l = Join-Path $dir "$n.png"
    A pull $r $l 2>&1 | Out-Null
    A shell rm $r | Out-Null
    if (Test-Path $l) { "  $n  ($((Get-Item $l).Length) bytes)" }
}
$ROW = { param($i) 522 + ($i * 133) }
$DETAILS_BTN = @(954, 2333)
$ICON_BTN    = @(1025, 2333)

A shell am force-stop com.phonepc.explorer | Out-Null
Start-Sleep -Milliseconds 500
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 4

"== 1 device root =="
Tap $DETAILS_BTN[0] $DETAILS_BTN[1]
Shot '01-this-phone'

"== 2 thumbnails =="
Tap 72 178; Tap 200 742; Start-Sleep -Seconds 2      # 图片
Tap $ICON_BTN[0] $ICON_BTN[1]; Start-Sleep -Seconds 3
Shot '02-thumbnails'

"== 3 tabs =="
Tap 1029 182; Start-Sleep -Seconds 2
Shot '03-tabs'

"== back to details, then into Download =="
Tap $DETAILS_BTN[0] $DETAILS_BTN[1]
Start-Sleep -Seconds 1
Tap 72 178; Tap 200 553; Start-Sleep -Seconds 1      # 下载
Shot '03b-download-check'

"== 4 archive browser =="
Tap 320 (& $ROW 5)                                    # 测试压缩包.zip
Start-Sleep -Seconds 3
Shot '04-archive'

"== 5 nested + selection =="
Tap 320 (& $ROW 2)                                    # 文档
Start-Sleep -Seconds 2
Tap 320 (& $ROW 0)                                    # 附件
Start-Sleep -Seconds 2
Long 320 (& $ROW 0)
Tap 320 (& $ROW 1)
Shot '05-archive-selection'

"== 6 APK info =="
Key 4; Key 4; Key 4
Start-Sleep -Seconds 1
Tap 320 (& $ROW 4)                                    # 此手机-1.4.apk
Start-Sleep -Seconds 6
Shot '06-apk-info'
Key 4
Start-Sleep -Milliseconds 900

"== 7 properties with hash =="
Long 320 (& $ROW 4)
Tap 1006 415
Down 6
A shell input keyevent 66 | Out-Null
Start-Sleep -Seconds 5
Shot '07-properties-hash'
Key 4; Key 4
Start-Sleep -Milliseconds 900

"== 8 overflow =="
Tap 1006 415
Shot '08-overflow'
Key 4
"done"
