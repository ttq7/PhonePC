# Final capture run for v1.3 – uses D-pad for menu picks (deterministic).
param([int]$Port = 5554)

$ErrorActionPreference = 'Continue'
$root = 'E:\此电脑'
$sdk  = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb  = Join-Path $sdk 'platform-tools\adb.exe'
$dev  = "emulator-$Port"
$shotDir = Join-Path $root 'preview\catch'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null

function A { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { A shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 800 }
function Long([int]$x, [int]$y) { A shell input swipe $x $y $x $y 1100 | Out-Null; Start-Sleep -Milliseconds 900 }
function Key([int]$k) { A shell input keyevent $k | Out-Null; Start-Sleep -Milliseconds 700 }
function Down([int]$n) { for ($i = 0; $i -lt $n; $i++) { A shell input keyevent 20 | Out-Null; Start-Sleep -Milliseconds 120 } }
function Shot([string]$n) {
    $r = "/sdcard/_c_$n.png"
    A shell screencap -p $r | Out-Null
    $l = Join-Path $shotDir "$n.png"
    if (Test-Path $l) { Remove-Item $l -Force }
    A pull $r $l 2>&1 | Out-Null
    A shell rm $r | Out-Null
    if (Test-Path $l) { "  $n  ($((Get-Item $l).Length) bytes)" }
}

# ---- 1. device root --------------------------------------------------------
A shell am force-stop com.phonepc.explorer | Out-Null; Start-Sleep -Seconds 1
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null; Start-Sleep -Seconds 5
Shot '01-this-phone'

# ---- 2. Pictures, details --------------------------------------------------
Tap 72 178
Tap 200 742
Start-Sleep -Seconds 2
Shot '02-details'

# ---- 3. large icons -> real thumbnails -------------------------------------
Tap 1025 2333
Start-Sleep -Seconds 3
Shot '03-thumbnails'

# ---- 4. second tab ---------------------------------------------------------
Tap 1029 182
Start-Sleep -Seconds 2
Shot '04-tabs'

# ---- 5. batch rename (2 selected) ------------------------------------------
Long 110 593
Tap 644 580
Tap 1006 415
Down 6
A shell input keyevent 66 | Out-Null; Start-Sleep -Seconds 2
Shot '05-batch-rename'
Key 4
Key 4

# ---- 6. file properties with hashes ----------------------------------------
Long 861 1224
Tap 1006 415
Down 6
A shell input keyevent 66 | Out-Null; Start-Sleep -Seconds 4
Shot '06-properties-hash'
Key 4
Key 4

# ---- 7. navigation pane ----------------------------------------------------
Tap 72 178
Shot '07-nav-pane'
Key 4

# ---- 8. command bar overflow ----------------------------------------------
Tap 1006 415
Shot '08-overflow'
Key 4

"done"
