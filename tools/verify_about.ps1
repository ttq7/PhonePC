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
"  settings opened: " + ((A shell "dumpsys window | grep -i mCurrentFocus") -match 'SettingsActivity')

$a = Find '关于'
if (-not $a) { 'FAIL: 关于 row missing'; exit 1 }
"  关于 row found at $($a -join ',')"
A shell input tap $a[0] $a[1] | Out-Null
Start-Sleep -Seconds 3

$out = 'E:\此电脑\preview\about'
New-Item -ItemType Directory -Force -Path $out | Out-Null
A shell screencap -p /sdcard/ab.png | Out-Null
A pull /sdcard/ab.png "$out\about.png" 2>&1 | Out-Null
A shell rm /sdcard/ab.png | Out-Null

$x = Dump
"  dialog contents:"
[regex]::Matches($x, 'text="([^"]{2,44})"') | ForEach-Object { $_.Groups[1].Value } |
    Select-Object -Unique | Select-Object -First 12 | ForEach-Object { "      $_" }
