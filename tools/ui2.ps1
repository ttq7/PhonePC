# Second UI tour with corrected coordinates.
param([int]$Port = 5554)

$ErrorActionPreference = 'Continue'
$root = 'E:\此电脑'
$sdk  = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb  = Join-Path $sdk 'platform-tools\adb.exe'
$dev  = "emulator-$Port"
$shotDir = Join-Path $root 'preview\shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null

function Adb { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { Adb shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 900 }
function LongPress([int]$x, [int]$y) { Adb shell input swipe $x $y $x $y 1100 | Out-Null; Start-Sleep -Milliseconds 900 }
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

# row centres inside 内部存储 (row height 136px, first row ~y=522)
$ROW = { param($i) 522 + ($i * 133) }

"== back to storage =="
Key 4
Shot '10-storage-details'

"== view menu via tab spinner =="
Tap 967 178
Shot '11-view-menu'
Key 4

"== enter Download (6th row) =="
Tap 320 (& $ROW 5)
Shot '12-download'

"== long press 2nd row -> selection =="
LongPress 320 (& $ROW 1)
Shot '13-selection'

"== select all via overflow =="
Key 4
Tap 1006 415
Shot '14-overflow-menu'
Key 4

"== navigation pane =="
Tap 72 178
Shot '15-nav-pane'
Key 4

"== search =="
Tap 900 318
Adb shell input text "IMG" | Out-Null
Start-Sleep -Seconds 2
Shot '16-search'
Key 4

"== done =="
Get-ChildItem $shotDir -Filter '*.png' | Sort-Object Name |
    Select-Object Name, @{n='KB';e={[math]::Round($_.Length/1KB)}}
