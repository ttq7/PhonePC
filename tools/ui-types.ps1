$sdk = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$dev = 'emulator-5554'
function A { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { A shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 1200 }
function Dump {
    A shell "uiautomator dump /sdcard/ui.xml" 2>&1 | Out-Null
    A pull /sdcard/ui.xml (Join-Path $env:TEMP 'ui.xml') 2>&1 | Out-Null
    (Get-Content (Join-Path $env:TEMP 'ui.xml') -Raw -Encoding UTF8)
}
function OpenFinder {
    A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null; Start-Sleep -Seconds 3
    Tap 72 178            # drawer
    Start-Sleep -Milliseconds 800
    Tap 399 633           # 快捷查找
    Start-Sleep -Seconds 8
}
function Ask([string]$q) {
    for ($i = 0; $i -lt 90; $i++) { A shell input keyevent 67 | Out-Null }
    A shell input text $q | Out-Null
    Start-Sleep -Milliseconds 1800
    $x = Dump
    $n = -1; if ($x -match '找到 (\d+) 个结果') { $n = [int]$Matches[1] }
    $f = [regex]::Matches($x, 'text="([^"]*\.[A-Za-z0-9]{1,5})"') |
         ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
    $exts = $f | ForEach-Object { ($_ -split '\.')[-1].ToLower() } | Sort-Object -Unique
    "  {0,-8} results={1,-4} types=[{2}]  e.g. {3}" -f $q, $n, ($exts -join ','), (($f | Select-Object -First 3) -join ', ')
}

A shell "mkdir -p /sdcard/BaiduNetdisk /sdcard/QQBrowser/download /sdcard/tencent/MicroMsg/Download" | Out-Null
A shell "echo x > /sdcard/BaiduNetdisk/typeprobe_img.jpg; echo x > /sdcard/BaiduNetdisk/typeprobe_pic.png" | Out-Null
A shell "echo x > /sdcard/Download/typeprobe_doc.pdf; echo x > /sdcard/Download/typeprobe_sheet.xlsx" | Out-Null
A shell "echo x > /sdcard/Download/typeprobe_note.txt; echo x > /sdcard/Download/typeprobe_data.json" | Out-Null
A shell "echo x > /sdcard/Download/typeprobe_app.apk; echo x > /sdcard/Download/typeprobe_pack.zip" | Out-Null
A shell "echo x > /sdcard/Download/typeprobe_movie.mp4; echo x > /sdcard/Download/typeprobe_word.docx" | Out-Null
A shell "echo x > /sdcard/Download/typeprobe_disc.iso" | Out-Null

OpenFinder
"=== no kind word at all: does it return every type? ==="
Ask 'typeprobe'
""
"=== extension literals ==="
foreach ($e in 'apk','zip','jpg','png','txt','json','pdf','docx','xlsx','mp4','iso') { Ask $e }
