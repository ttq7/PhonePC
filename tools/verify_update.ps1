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
function Texts { [regex]::Matches((Dump), 'text="([^"]{2,90})"') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique }

"=== 装回 2.4 release 的包（内部 2.2），允许降级 ==="
A install -r -d 'E:\此电脑\.tmp\rel\v24.apk' 2>&1 | Select-Object -Last 2
A shell appops set com.phonepc.explorer MANAGE_EXTERNAL_STORAGE allow | Out-Null
Start-Sleep -Seconds 1

"=== 打开 设置 -> 检查更新 ==="
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 4
A shell input tap 72 178 | Out-Null
Start-Sleep -Seconds 2
$s = Find '设置'
if (-not $s) { 'FAIL: 设置 not found'; exit 1 }
A shell input tap $s[0] $s[1] | Out-Null
Start-Sleep -Seconds 3
"  settings version row:"
Texts | Where-Object { $_ -like '当前版本*' } | ForEach-Object { "      $_" }

$u = Find '检查更新'
if (-not $u) { 'FAIL: 检查更新 not found'; exit 1 }
A shell input tap $u[0] $u[1] | Out-Null
Start-Sleep -Seconds 8
"  after tapping 检查更新:"
Texts | Select-Object -First 12 | ForEach-Object { "      $_" }

$out = 'E:\此电脑\preview\update'
New-Item -ItemType Directory -Force -Path $out | Out-Null
A shell screencap -p /sdcard/u1.png | Out-Null
A pull /sdcard/u1.png "$out\found.png" 2>&1 | Out-Null
A shell rm /sdcard/u1.png | Out-Null
