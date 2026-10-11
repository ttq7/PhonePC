# Verify the breadcrumb root keeps the phone icon after navigating.
$sdk = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$dev = 'emulator-5554'
$dir = 'E:\此电脑\preview\icon'
if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dir | Out-Null

function A { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { A shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 1200 }
function Shot([string]$n) {
    $r = "/sdcard/_i_$n.png"
    A shell screencap -p $r | Out-Null
    $l = Join-Path $dir "$n.png"
    if (Test-Path $l) { Remove-Item $l -Force }
    A pull $r $l 2>&1 | Out-Null
    A shell rm $r | Out-Null
    if (Test-Path $l) { "  $n ($((Get-Item $l).Length) bytes)" }
}

A shell am force-stop com.phonepc.explorer | Out-Null
Start-Sleep -Milliseconds 600
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 4

# This PC root
Shot 'i1-root'
# navigate into a real folder: 此手机 page -> 下载
Tap 72 178
Tap 200 553
Start-Sleep -Seconds 2
Shot 'i2-in-folder'
# one level deeper
Tap 320 673
Start-Sleep -Seconds 2
Shot 'i3-deeper'
# and via the address bar to a deep path
A shell input tap 470 318 | Out-Null
Start-Sleep -Milliseconds 1200
for ($i = 0; $i -lt 60; $i++) { A shell input keyevent 67 | Out-Null }
A shell input text '/sdcard/Documents' | Out-Null
Start-Sleep -Milliseconds 600
A shell input keyevent 66 | Out-Null
Start-Sleep -Seconds 2
Shot 'i4-typed-path'
