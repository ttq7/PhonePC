# Verify the archive browser and APK inspector on the emulator.
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

"== into 下载 =="
Tap 72 178          # drawer
Tap 200 553         # 下载
Start-Sleep -Seconds 1
Shot '01-download'

"== open the test archive (search to be deterministic) =="
Tap 900 318
A shell input text "测试" | Out-Null
Start-Sleep -Seconds 2
Shot '02-filtered'
Tap 320 (& $ROW 0)
Start-Sleep -Seconds 3
Shot '03-archive-root'

"== into 文档 =="
Tap 320 (& $ROW 2)
Start-Sleep -Seconds 2
Shot '04-archive-doc'

"== into 附件 =="
Tap 320 (& $ROW 3)
Start-Sleep -Seconds 2
Shot '05-archive-attach'