# project root: this script lives in <root>\tools
$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot }
        elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT }
        else { (Get-Location).Path }

$sdk = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$dev = 'emulator-5554'
$dir = (Join-Path $root '')

function A { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { A shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 1300 }
function Shot([string]$n) {
    $r = "/sdcard/_q_$n.png"
    A shell screencap -p $r | Out-Null
    $l = Join-Path $dir "$n.png"
    if (Test-Path $l) { Remove-Item $l -Force }
    A pull $r $l 2>&1 | Out-Null
    A shell rm $r | Out-Null
    if (Test-Path $l) { "  $n ($((Get-Item $l).Length) bytes)" }
}
function Dump {
    A shell "uiautomator dump /sdcard/ui.xml" 2>&1 | Out-Null
    A pull /sdcard/ui.xml (Join-Path $env:TEMP 'ui.xml') 2>&1 | Out-Null
    (Get-Content (Join-Path $env:TEMP 'ui.xml') -Raw -Encoding UTF8)
}
function Query([string]$q) {
    for ($i = 0; $i -lt 90; $i++) { A shell input keyevent 67 | Out-Null }
    A shell input text $q | Out-Null
    Start-Sleep -Milliseconds 1800
    $x = Dump
    $n = -1
    if ($x -match '找到 (\d+) 个结果') { $n = [int]$Matches[1] }
    $src = ''
    if ($x -match '来源「([^」]*)」') { $src = $Matches[1] }
    return @{ Count = $n; Source = $src; Xml = $x }
}

A shell am force-stop com.phonepc.explorer | Out-Null
Start-Sleep -Milliseconds 700
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 4
Tap 72 178
Start-Sleep -Seconds 1
$d = Dump
$pt = $null
foreach ($m in [regex]::Matches($d, 'text="快捷查找"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
    $pt = @([int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2),
            [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2))
}
"快捷查找 entry at: $($pt -join ',')"
if ($pt) { Tap $pt[0] $pt[1] }
Start-Sleep -Seconds 9
Shot 'f0-open'
"status: " + ([regex]::Match((Dump), 'id/statusText[^>]*text="([^"]*)"').Groups[1].Value)

$r = Query 'qq'
Shot 'f1-qq'
"qq       -> results=$($r.Count)  source='$($r.Source)'  report-2026=$($r.Xml -match 'report-2026')"

$r = Query 'rdme'
Shot 'f2-rdme'
"rdme     -> results=$($r.Count)  readme=$($r.Xml -match 'readme')"

$r = Query 'wechat'
Shot 'f3-wechat'
"wechat   -> results=$($r.Count)  source='$($r.Source)'  wechat_file=$($r.Xml -match 'wechat_file')"

$r = Query 'notes'
Shot 'f4-notes'
"notes    -> results=$($r.Count)  meeting_notes=$($r.Xml -match 'meeting_notes')"

$r = Query 'zzzznope'
Shot 'f5-none'
"zzzznope -> results=$($r.Count)"
