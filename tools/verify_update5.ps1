$sdk = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$dev = 'emulator-5554'
function A { & $adb -s $dev @args }
function Dump {
    A shell "uiautomator dump /sdcard/u.xml" 2>&1 | Out-Null
    A pull /sdcard/u.xml (Join-Path $env:TEMP 'u.xml') 2>&1 | Out-Null
    (Get-Content (Join-Path $env:TEMP 'u.xml') -Raw -Encoding UTF8)
}
# locate by resource-id, not by hand-written numbers
function FindId([string]$id) {
    $x = Dump
    $m = [regex]::Match($x, 'resource-id="com.phonepc.explorer:id/' + [regex]::Escape($id) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Success) {
        return @([int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2),
                 [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2))
    }
    return $null
}
function FindText([string]$t) {
    $x = Dump
    $m = [regex]::Match($x, 'text="' + [regex]::Escape($t) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Success) {
        return @([int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2),
                 [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2))
    }
    return $null
}
function Texts { [regex]::Matches((Dump), 'text="([^"]{2,110})"') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique }
function Shot([string]$n) {
    A shell screencap -p /sdcard/s.png | Out-Null
    A pull /sdcard/s.png "E:\此电脑\preview\update\$n.png" 2>&1 | Out-Null
    A shell rm /sdcard/s.png | Out-Null
}
New-Item -ItemType Directory -Force -Path 'E:\此电脑\preview\update' | Out-Null

$p = FindId 'btnMenu'
if (-not $p) { 'FAIL: btnMenu not found'; exit 1 }
"  btnMenu at $($p -join ',')"
A shell input tap $p[0] $p[1] | Out-Null
Start-Sleep -Seconds 2

$s = FindText '设置'
if (-not $s) { 'FAIL: 设置 not found after opening drawer'; Texts | Select-Object -First 10 | ForEach-Object { "   $_" }; exit 1 }
"  设置 at $($s -join ',')"
A shell input tap $s[0] $s[1] | Out-Null
Start-Sleep -Seconds 3

$u = FindId 'rowUpdate'
if (-not $u) { 'FAIL: rowUpdate not found'; exit 1 }
"  rowUpdate at $($u -join ',')"
A shell input tap $u[0] $u[1] | Out-Null
Start-Sleep -Seconds 12

"  dialog after 检查更新:"
Texts | Select-Object -First 12 | ForEach-Object { "      $_" }
Shot 'found'
