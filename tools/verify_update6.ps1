$sdk = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$dev = 'emulator-5554'
function A { & $adb -s $dev @args }
function Dump {
    A shell "uiautomator dump /sdcard/u.xml" 2>&1 | Out-Null
    A pull /sdcard/u.xml (Join-Path $env:TEMP 'u.xml') 2>&1 | Out-Null
    (Get-Content (Join-Path $env:TEMP 'u.xml') -Raw -Encoding UTF8)
}
function FindId([string]$id) {
    $m = [regex]::Match((Dump), 'resource-id="com.phonepc.explorer:id/' + [regex]::Escape($id) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Success) { return @([int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2), [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)) }
    return $null
}
function FindText([string]$t) {
    $m = [regex]::Match((Dump), 'text="' + [regex]::Escape($t) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Success) { return @([int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2), [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)) }
    return $null
}
function Texts { [regex]::Matches((Dump), 'text="([^"]{2,110})"') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique }

# ---- rebuild the dialog state -------------------------------------------------
A shell am force-stop com.phonepc.explorer | Out-Null
Start-Sleep -Milliseconds 700
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 4
$p = FindId 'btnMenu'; A shell input tap $p[0] $p[1] | Out-Null; Start-Sleep -Seconds 2
$s = FindText '设置';  A shell input tap $s[0] $s[1] | Out-Null; Start-Sleep -Seconds 3
$u = FindId 'rowUpdate'; A shell input tap $u[0] $u[1] | Out-Null; Start-Sleep -Seconds 12
"dialog: " + (((Texts) | Select-Object -First 3) -join ' | ')

# ---- tap 下载并安装 -----------------------------------------------------------
$d = FindText '下载并安装'
if (-not $d) { 'FAIL: 下载并安装 button not found'; exit 1 }
"tapping 下载并安装 ..."
A shell input tap $d[0] $d[1] | Out-Null
Start-Sleep -Seconds 20

"after download:"
Texts | Select-Object -First 8 | ForEach-Object { "    $_" }
""
"current focus:"
A shell "dumpsys window | grep -i mCurrentFocus" 2>&1
""
"downloaded file in app dir:"
A shell "ls -l /sdcard/Android/data/com.phonepc.explorer/files/update/ 2>&1" 2>&1
