# Archive browser + APK inspector verification (fixed coordinates).
param([int]$Port = 5554)

$ErrorActionPreference = 'Continue'
$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }
$sdk  = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb  = Join-Path $sdk 'platform-tools\adb.exe'
$dev  = "emulator-$Port"
$dir  = Join-Path $root 'preview\arch'
New-Item -ItemType Directory -Force -Path $dir | Out-Null

function A { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { A shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 900 }
function Long([int]$x, [int]$y) { A shell input swipe $x $y $x $y 1100 | Out-Null; Start-Sleep -Milliseconds 1000 }
function Key([int]$k) { A shell input keyevent $k | Out-Null; Start-Sleep -Milliseconds 900 }
function Shot([string]$n) {
    $r = "/sdcard/_a_$n.png"
    A shell screencap -p $r | Out-Null
    $l = Join-Path $dir "$n.png"
    if (Test-Path $l) { Remove-Item $l -Force }
    A pull $r $l 2>&1 | Out-Null
    A shell rm $r | Out-Null
    if (Test-Path $l) { "  $n  ($((Get-Item $l).Length) bytes)" }
}
$ROW = { param($i) 522 + ($i * 133) }

A shell am force-stop com.phonepc.explorer | Out-Null
Start-Sleep -Milliseconds 500
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 4

"== Download =="
Tap 72 178
Tap 200 553
Start-Sleep -Seconds 1

"== open 测试压缩包.zip (row 4) =="
Tap 320 (& $ROW 4)
Start-Sleep -Seconds 3
Shot '10-archive-root'

"== into 文档 (row 2 of folders-first listing) =="
Tap 320 (& $ROW 2)
Start-Sleep -Seconds 2
Shot '11-archive-doc'

"== into 附件 (row 0) =="
Tap 320 (& $ROW 0)
Start-Sleep -Seconds 2
Shot '12-archive-attach'

"== select 历史 + 备注.md =="
Long 320 (& $ROW 0)
Tap 320 (& $ROW 1)
Shot '13-archive-selection'

"== 解压选中项 =="
Tap 110 415
Start-Sleep -Seconds 3
Shot '14-extracted'