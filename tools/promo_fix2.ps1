$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }

# project root: this script lives in <root>\tools
        elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT }
        else { (Get-Location).Path }

$sdk = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$dev = 'emulator-5554'
$dir = (Join-Path $root '')
function A { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { A shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 1100 }
function Fresh { A shell am force-stop com.phonepc.explorer | Out-Null; Start-Sleep -Milliseconds 700 }
function Record([string]$name, [scriptblock]$body) {
    A shell "rm -f /sdcard/rec.mp4" | Out-Null
    Start-Process -FilePath $adb -ArgumentList @('-s', $dev, 'shell', 'screenrecord',
        '--bit-rate', '12000000', '--size', '1080x2400', '/sdcard/rec.mp4') -NoNewWindow
    Start-Sleep -Seconds 1
    & $body
    A shell "pkill -INT screenrecord" | Out-Null
    Start-Sleep -Seconds 2
    $local = Join-Path $dir "$name.mp4"
    if (Test-Path $local) { Remove-Item $local -Force }
    A pull /sdcard/rec.mp4 $local 2>&1 | Out-Null
    if (Test-Path $local) { "  {0,-10} {1,6:N1} s" -f $name, [double](& ffprobe -v error -show_entries format=duration -of csv=p=0 $local) }
    else { "  $name FAILED" }
}

# C: hold on the "sharing is on" screen long enough to actually read
Record 'c-share' {
    Fresh
    A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
    Start-Sleep -Seconds 3
    Tap 72 178; Start-Sleep -Milliseconds 700
    Tap 200 748                 # 文件共享
    Start-Sleep -Seconds 3
    Tap 133 280                 # 启动共享
    Start-Sleep -Seconds 12     # long hold
}

# D: hold on the long-press context menu
Record 'd-undo' {
    Fresh
    A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
    Start-Sleep -Seconds 3
    Tap 72 178; Start-Sleep -Milliseconds 700
    Tap 200 560                 # 内部存储
    Start-Sleep -Seconds 3
    A shell input swipe 320 1124 320 1124 1200 | Out-Null
    Start-Sleep -Seconds 9      # long hold on the menu
}