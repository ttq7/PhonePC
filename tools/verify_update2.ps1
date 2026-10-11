$sdk = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$dev = 'emulator-5554'
function A { & $adb -s $dev @args }
function Dump {
    A shell "uiautomator dump /sdcard/u.xml" 2>&1 | Out-Null
    A pull /sdcard/u.xml (Join-Path $env:TEMP 'u.xml') 2>&1 | Out-Null
    (Get-Content (Join-Path $env:TEMP 'u.xml') -Raw -Encoding UTF8)
}
function Find([string]$t) {
    $x = Dump
    $m = [regex]::Match($x, 'text="' + [regex]::Escape($t) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Success) {
        return @([int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2),
                 [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2))
    }
    return $null
}
function Texts { [regex]::Matches((Dump), 'text="([^"]{2,100})"') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique }
function Shot([string]$n) {
    A shell screencap -p /sdcard/s.png | Out-Null
    A pull /sdcard/s.png "E:\此电脑\preview\update\$n.png" 2>&1 | Out-Null
    A shell rm /sdcard/s.png | Out-Null
}
New-Item -ItemType Directory -Force -Path 'E:\此电脑\preview\update' | Out-Null

A shell am force-stop com.phonepc.explorer | Out-Null
Start-Sleep -Milliseconds 700
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 4

A shell input tap 72 178 | Out-Null
Start-Sleep -Seconds 2
$s = Find '设置'
if (-not $s) { 'FAIL: 设置 not in drawer'; exit 1 }
A shell input tap $s[0] $s[1] | Out-Null
Start-Sleep -Seconds 3
"settings opened"
Texts | Where-Object { $_ -like '当前版本*' } | ForEach-Object { "    $_" }

$u = Find '检查更新'
if (-not $u) { 'FAIL: 检查更新 row missing'; exit 1 }
A shell input tap $u[0] $u[1] | Out-Null
Start-Sleep -Seconds 10
"after 检查更新:"
Texts | Select-Object -First 14 | ForEach-Object { "    $_" }
Shot 'dialog'
