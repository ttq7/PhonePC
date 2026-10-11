# Drive the running emulator through the UI and capture screenshots.
param([int]$Port = 5554)

$ErrorActionPreference = 'Continue'
$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }
$sdk  = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb  = Join-Path $sdk 'platform-tools\adb.exe'
$dev  = "emulator-$Port"
$pkg  = 'com.phonepc.explorer'
$shotDir = Join-Path $root 'preview\shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null

function Adb { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { Adb shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 900 }
function LongPress([int]$x, [int]$y) { Adb shell input swipe $x $y $x $y 900 | Out-Null; Start-Sleep -Milliseconds 900 }
function Swipe([int]$x1, [int]$y1, [int]$x2, [int]$y2, [int]$ms = 300) {
    Adb shell input swipe $x1 $y1 $x2 $y2 $ms | Out-Null; Start-Sleep -Milliseconds 900
}
function Shot([string]$name) {
    $remote = "/sdcard/_s_$name.png"
    Adb shell screencap -p $remote | Out-Null
    $local = Join-Path $shotDir "$name.png"
    if (Test-Path $local) { Remove-Item $local -Force }
    Adb pull $remote $local 2>&1 | Out-Null
    Adb shell rm $remote | Out-Null
    if (Test-Path $local) { "  shot $name  ($((Get-Item $local).Length) bytes)" }
}

# screen is 1080x2400, density 420
$W = 1080; $H = 2400
$MENU      = @(72, 178)
$CRUMB_TOP = @(300, 318)      # breadcrumb "此手机" when inside a folder
$GRID_BTN  = @(1010, 2334)    # bottom-right view toggle
$DETAIL_BTN= @(948, 2334)
$ROW1      = @(320, 522)
$ROW2      = @(320, 660)
$ROW3      = @(320, 800)

"== 1. enter 内部存储 =="
Tap $ROW1[0] $ROW1[1]
Start-Sleep -Seconds 2
Shot '02-storage-details'

"== 2. large icons =="
Tap $GRID_BTN[0] $GRID_BTN[1]
Start-Sleep -Seconds 1
Shot '03-storage-icons'

"== 3. back to details =="
Tap $DETAIL_BTN[0] $DETAIL_BTN[1]
Start-Sleep -Seconds 1
Shot '04-storage-details-again'

"== 4. navigation pane =="
Tap $MENU[0] $MENU[1]
Start-Sleep -Seconds 1
Shot '05-nav-pane'

"== 5. close pane, enter Download =="
Adb shell input keyevent 4 | Out-Null
Start-Sleep -Milliseconds 800
Tap $ROW1[0] $ROW1[1]
Start-Sleep -Seconds 2
Shot '06-download'

"== 6. selection mode (long press first row) =="
LongPress $ROW2[0] $ROW2[1]
Start-Sleep -Milliseconds 800
Shot '07-selection'

"== 7. sort menu (cancel selection first) =="
Adb shell input keyevent 4 | Out-Null
Start-Sleep -Milliseconds 800
# 排序 is the 7th command; scroll the command bar right first
Swipe 900 415 300 415 400
Tap 700 415
Start-Sleep -Milliseconds 800
Shot '08-sort-menu'

"== done =="
Get-ChildItem $shotDir -Filter '*.png' | Select-Object Name, @{n='KB';e={[math]::Round($_.Length/1KB)}}
