# Final verification tour: icon views + properties + breadcrumb.
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

# view menu item centres (from the captured popup)
$VIEW_X = 703
$VIEW_Y = @{ xl = 264; lg = 390; md = 516; sm = 642; tiles = 768; list = 894; details = 1020 }

Adb shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 3

"== into 内部存储 =="
Tap 320 (& $ROW 0)

"== 大图标 =="
Tap 967 178; Tap $VIEW_X $VIEW_Y.lg
Shot '31-icons-large'

"== 小图标 =="
Tap 967 178; Tap $VIEW_X $VIEW_Y.sm
Shot '32-icons-small'

"== 平铺 =="
Tap 967 178; Tap $VIEW_X $VIEW_Y.tiles
Shot '33-tiles'

"== 列表 =="
Tap 967 178; Tap $VIEW_X $VIEW_Y.list
Shot '34-list'

"== back to 详细信息 =="
Tap 967 178; Tap $VIEW_X $VIEW_Y.details

"== into Download, check breadcrumb =="
Tap 320 (& $ROW 5)
Shot '35-download-breadcrumb'

"== properties =="
LongPress 320 (& $ROW 1)
Tap 1006 415
Start-Sleep -Milliseconds 700
Shot '36-selection-more'
# 属性 is the last entry of the overflow menu
Tap 700 1560
Start-Sleep -Seconds 2
Shot '37-properties'
Key 4
Key 4

"== new folder dialog =="
Tap 130 415
Start-Sleep -Seconds 1
Shot '38-new-menu'
Tap 400 640
Start-Sleep -Seconds 1
Shot '39-new-folder-dialog'
Key 4

"== done =="