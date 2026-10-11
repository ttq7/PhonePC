$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }

# project root: this script lives in <root>\tools
        elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT }
        else { (Get-Location).Path }

# Re-record the promo clips: clean content, one theme, no keyboard, real restarts.
$sdk = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$dev = 'emulator-5554'
$dir = (Join-Path $root '')
if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dir | Out-Null

function A { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { A shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 1100 }
function Key([int]$k) { A shell input keyevent $k | Out-Null; Start-Sleep -Milliseconds 800 }
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
    A pull /sdcard/rec.mp4 $local 2>&1 | Out-Null
    if (Test-Path $local) {
        $d = & ffprobe -v error -show_entries format=duration -of csv=p=0 $local
        "  {0,-10} {1,6:N1} s" -f $name, [double]$d
    } else { "  $name FAILED" }
}

# ---- clean every test artifact, then seed presentable content ---------------
A shell "rm -f /sdcard/Download/typeprobe_* /sdcard/BaiduNetdisk/typeprobe_* /sdcard/BaiduNetdisk/morning_* /sdcard/Download/apk_backup_test.apk /sdcard/Download/archive_test.zip" | Out-Null
A shell "rm -rf /sdcard/QQBrowser /sdcard/tencent/MicroMsg/Download/lastweek_*" | Out-Null
A shell "mkdir -p /sdcard/Download /sdcard/Documents /sdcard/Pictures /sdcard/DCIM/Camera /sdcard/Music /sdcard/Movies /sdcard/BaiduNetdisk /sdcard/tencent/MicroMsg/Download" | Out-Null
A shell "echo x > /sdcard/BaiduNetdisk/新品海报.jpg; echo x > /sdcard/BaiduNetdisk/封面设计.png; echo x > /sdcard/BaiduNetdisk/主视觉-终稿.psd" | Out-Null
A shell "echo x > /sdcard/tencent/MicroMsg/Download/季度报表.xlsx; echo x > /sdcard/tencent/MicroMsg/Download/会议纪要.docx" | Out-Null
A shell "echo x > /sdcard/Download/产品需求文档.pdf; echo x > /sdcard/Download/微信助手.apk; echo x > /sdcard/Download/素材打包.zip" | Out-Null
$today = ((A shell date +%Y%m%d) -join '').Trim()
A shell "touch -t ${today}0940 /sdcard/BaiduNetdisk/新品海报.jpg /sdcard/BaiduNetdisk/封面设计.png" | Out-Null

# one theme for the whole film
A shell cmd uimode night no | Out-Null
Start-Sleep -Seconds 2

# ---- A: the Windows-style shell -------------------------------------------
Record 'a-shell' {
    Fresh
    A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
    Start-Sleep -Seconds 3
    Tap 72 178; Start-Sleep -Milliseconds 600
    Key 4; Start-Sleep -Milliseconds 800
    Tap 320 673
    Start-Sleep -Seconds 3
}

# ---- B: natural-language search -------------------------------------------
Record 'b-search' {
    Fresh
    A shell "am start -n com.phonepc.explorer/.QuickFindActivity --es q `"我上午在百度下载的图片`"" | Out-Null
    Start-Sleep -Seconds 10
    Key 4                       # dismiss the keyboard so the results are visible
    Start-Sleep -Seconds 3
    Swipe 540 1100 540 850
    Swipe 540 850 540 1100
    Start-Sleep -Seconds 2
}

# ---- C: LAN share ----------------------------------------------------------
Record 'c-share' {
    Fresh
    A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
    Start-Sleep -Seconds 3
    Tap 72 178; Start-Sleep -Milliseconds 600
    Tap 200 1217
    Start-Sleep -Seconds 3
    Tap 133 280
    Start-Sleep -Seconds 5
}

# ---- D: recycle bin + undo -------------------------------------------------
Record 'd-undo' {
    Fresh
    A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
    Start-Sleep -Seconds 3
    Tap 72 178; Start-Sleep -Milliseconds 600
    Tap 200 553
    Start-Sleep -Seconds 3
    A shell input swipe 320 1124 320 1124 1200 | Out-Null
    Start-Sleep -Seconds 1
    Tap 800 415
    Start-Sleep -Milliseconds 900
    A shell input tap 870 1362 | Out-Null
    Start-Sleep -Seconds 3
}

"clips:"
Get-ChildItem $dir | Select-Object Name, @{n='MB';e={[math]::Round($_.Length/1MB,2)}}