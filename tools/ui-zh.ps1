$sdk = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$dev = 'emulator-5554'
function A { & $adb -s $dev @args }
function Dump {
    A shell "uiautomator dump /sdcard/ui.xml" 2>&1 | Out-Null
    A pull /sdcard/ui.xml (Join-Path $env:TEMP 'ui.xml') 2>&1 | Out-Null
    (Get-Content (Join-Path $env:TEMP 'ui.xml') -Raw -Encoding UTF8)
}
function Ask([string]$q) {
    A shell am force-stop com.phonepc.explorer | Out-Null
    Start-Sleep -Milliseconds 500
    A shell "am start -n com.phonepc.explorer/.QuickFindActivity --es q `"$q`"" | Out-Null
    Start-Sleep -Seconds 9
    $x = Dump
    $n = -1
    if ($x -match '找到 (\d+) 个结果') { $n = [int]$Matches[1] }
    $src = ''
    if ($x -match '来源「([^」]*)」') { $src = $Matches[1] }
    $files = [regex]::Matches($x, 'text="([^"]*\.[A-Za-z0-9]{1,5})"') |
             ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
    "  [$q]"
    "      results=$n  source='$src'"
    "      hits: " + (($files | Select-Object -First 8) -join ', ')
    ""
}

A shell "mkdir -p /sdcard/QQBrowser/download /sdcard/tencent/MicroMsg/Download" | Out-Null
A shell "echo x > /sdcard/QQBrowser/download/report-2026.pdf" | Out-Null
A shell "echo x > /sdcard/QQBrowser/download/holiday-photo.jpg" | Out-Null
A shell "echo x > /sdcard/tencent/MicroMsg/Download/budget.xlsx" | Out-Null
A shell "echo x > /sdcard/Download/apk_backup_test.apk" | Out-Null
A shell "echo x > /sdcard/Download/archive_test.zip" | Out-Null

Ask '帮我找一下手机中的apk文件'
Ask '找一下压缩包'
Ask '我上午在百度下载的图片'
Ask '上周微信发我的表格'
Ask '刚过中午那会儿存的照片'
Ask 'report'