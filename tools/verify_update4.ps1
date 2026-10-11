$sdk = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$dev = 'emulator-5554'
function A { & $adb -s $dev @args }
function Dump {
    A shell "uiautomator dump /sdcard/u.xml" 2>&1 | Out-Null
    A pull /sdcard/u.xml (Join-Path $env:TEMP 'u.xml') 2>&1 | Out-Null
    (Get-Content (Join-Path $env:TEMP 'u.xml') -Raw -Encoding UTF8)
}
function Attempt([string]$label, [scriptblock]$act) {
    & $act
    Start-Sleep -Seconds 2
    $ok = (Dump) -match 'text="设置"'
    "  {0,-26} -> drawer open: {1}" -f $label, $ok
    return $ok
}

$opened = Attempt 'tap (50,190)' { A shell input tap 50 190 | Out-Null }
if (-not $opened) { $opened = Attempt 'swipe from left edge' { A shell input swipe 5 1200 700 1200 250 | Out-Null } }
if (-not $opened) { $opened = Attempt 'tap (72,178) old coord' { A shell input tap 72 178 | Out-Null } }

if ($opened) {
    $x = Dump
    $m = [regex]::Match($x, 'text="设置"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Success) {
        $cx = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
        $cy = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
        A shell input tap $cx $cy | Out-Null
        Start-Sleep -Seconds 3
        $s = Dump
        "  settings screen: " + ($s -match 'text="检查更新"')
        $u = [regex]::Match($s, 'text="检查更新"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if ($u.Success) {
            A shell input tap ([int](([int]$u.Groups[1].Value + [int]$u.Groups[3].Value) / 2)) ([int](([int]$u.Groups[2].Value + [int]$u.Groups[4].Value) / 2)) | Out-Null
            Start-Sleep -Seconds 12
            "  after 检查更新:"
            [regex]::Matches((Dump), 'text="([^"]{2,110})"') | ForEach-Object { $_.Groups[1].Value } |
                Select-Object -Unique | Select-Object -First 10 | ForEach-Object { "      $_" }
            New-Item -ItemType Directory -Force -Path 'E:\此电脑\preview\update' | Out-Null
            A shell screencap -p /sdcard/s.png | Out-Null
            A pull /sdcard/s.png 'E:\此电脑\preview\update\found.png' 2>&1 | Out-Null
            A shell rm /sdcard/s.png | Out-Null
        }
    }
} else {
    "  drawer never opened; the tap coordinates for this build are unknown"
}
