# project root: this script lives in <root>\tools
$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot }
        elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT }
        else { (Get-Location).Path }

# v2.1: quick find (fuzzy + source-aware).
$sdk = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$dev = 'emulator-5554'
$dir = (Join-Path $root '')
if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dir | Out-Null

function A { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { A shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 1200 }
function Key([int]$k) { A shell input keyevent $k | Out-Null; Start-Sleep -Milliseconds 800 }
function Shot([string]$n) {
    $r = "/sdcard/_q_$n.png"
    A shell screencap -p $r | Out-Null
    $l = Join-Path $dir "$n.png"
    if (Test-Path $l) { Remove-Item $l -Force }
    A pull $r $l 2>&1 | Out-Null
    A shell rm $r | Out-Null
    if (Test-Path $l) { "  $n ($((Get-Item $l).Length) bytes)" }
}
function Ui {
    A shell "uiautomator dump /sdcard/ui.xml" 2>&1 | Out-Null
    A pull /sdcard/ui.xml (Join-Path $env:TEMP 'ui.xml') 2>&1 | Out-Null
    (Get-Content (Join-Path $env:TEMP 'ui.xml') -Raw -Encoding UTF8)
}
function Type([string]$t) {
    for ($i = 0; $i -lt 90; $i++) { A shell input keyevent 67 | Out-Null }
    A shell input text $t | Out-Null
    Start-Sleep -Milliseconds 1500
}
function ResultCount {
    $x = Ui
    if ($x -match '找到 (\d+) 个结果') { return [int]$Matches[1] }
    return -1
}

# --- seed a realistic "downloaded by QQ Browser" layout ----------------------
A shell "mkdir -p /sdcard/QQBrowser/download" | Out-Null
A shell "mkdir -p /sdcard/tencent/MicroMsg/Download" | Out-Null
A shell "echo 'contract body' > /sdcard/QQBrowser/download/report-2026.pdf" | Out-Null
A shell "echo 'wechat body' > /sdcard/tencent/MicroMsg/Download/wechat_file.docx" | Out-Null
A shell "echo 'notes' > /sdcard/Download/meeting_notes_2026.txt" | Out-Null

A shell am force-stop com.phonepc.explorer | Out-Null
Start-Sleep -Milliseconds 600
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 4

"== 1. drawer has 快捷查找 =="
Tap 72 178
Shot 'q1-drawer'
$x = Ui
"  drawer 快捷查找: " + ($x -match '快捷查找')
$qf = $null
foreach ($m in [regex]::Matches($x, 'text="快捷查找"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
    $qf = @([int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2),
            [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2))
}
"  快捷查找 at: $($qf -join ',')"

"== 2. open quick find, wait for the index =="
if ($qf) { Tap $qf[0] $qf[1] }
Start-Sleep -Seconds 8
Shot 'q2-open'
"  status: " + ((Ui) -replace '(?s).*id/statusText[^>]*text="([^"]*)".*', '$1')

"== 3. fuzzy name query: rdme -> readme =="
Type 'rdme'
Shot 'q3-fuzzy-rdme'
"  results: $(ResultCount)"

"== 4. source query: qq -> files QQ Browser wrote =="
Type 'qq'
Shot 'q4-source-qq'
"  results: $(ResultCount)"
$y = Ui
"  found report-2026.pdf: " + ($y -match 'report-2026')

"== 5. source query: wechat =="
Type 'wechat'
Shot 'q5-source-wechat'
"  results: $(ResultCount)"
"  found wechat_file.docx: " + ((Ui) -match 'wechat_file')

"== 6. plain name query =="
Type 'notes'
Shot 'q6-name-notes'
"  results: $(ResultCount)"

"== 7. no match =="
Type 'zzzznope'
Shot 'q7-none'
"  results: $(ResultCount)"
