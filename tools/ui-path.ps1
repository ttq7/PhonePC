# Verify the editable address bar (path input).
param([int]$Port = 5554)

$ErrorActionPreference = 'Continue'
$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }
$sdk  = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb  = Join-Path $sdk 'platform-tools\adb.exe'
$dev  = "emulator-$Port"
$dir  = Join-Path $root 'preview\path'
if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dir | Out-Null

function A { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { A shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 1000 }
function Shot([string]$n) {
    $r = "/sdcard/_p_$n.png"
    A shell screencap -p $r | Out-Null
    $l = Join-Path $dir "$n.png"
    if (Test-Path $l) { Remove-Item $l -Force }
    A pull $r $l 2>&1 | Out-Null
    A shell rm $r | Out-Null
    if (Test-Path $l) { "  $n  ($((Get-Item $l).Length) bytes)" }
}
function UiText {
    A shell "uiautomator dump /sdcard/ui.xml" 2>&1 | Out-Null
    $local = Join-Path $env:TEMP 'ui.xml'
    A pull /sdcard/ui.xml $local 2>&1 | Out-Null
    (Get-Content $local -Raw -Encoding UTF8)
}
function CurrentTitle {
    $x = UiText
    if ($x -match 'resource-id="com.phonepc.explorer:id/titleText"[^>]*text="([^"]*)"') { return $Matches[1] }
    if ($x -match 'text="([^"]*)"[^>]*resource-id="com.phonepc.explorer:id/titleText"') { return $Matches[1] }
    return '?'
}
function EditablePath {
    $x = UiText
    if ($x -match 'resource-id="com.phonepc.explorer:id/pathInput"[^>]*text="([^"]*)"') { return $Matches[1] }
    if ($x -match 'text="([^"]*)"[^>]*resource-id="com.phonepc.explorer:id/pathInput"') { return $Matches[1] }
    return $null
}
function EnterPath([string]$p) {
    Tap 470 318                       # tap the address bar -> edit mode
    $shown = EditablePath
    "    edit mode: " + $(if ($shown -ne $null) { "yes (prefilled '$shown')" } else { 'NO' })
    if ($shown -eq $null) { return $false }
    # clear the prefilled text
    for ($i = 0; $i -lt 80; $i++) { A shell input keyevent 67 | Out-Null }
    A shell input text $p | Out-Null
    Start-Sleep -Milliseconds 700
    A shell input keyevent 66 | Out-Null   # ENTER
    Start-Sleep -Seconds 2
    return $true
}

A shell am force-stop com.phonepc.explorer | Out-Null
Start-Sleep -Milliseconds 600
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 4

"== 1. tap address bar =="
Tap 470 318
Shot 'p1-edit-mode'
A shell input keyevent 4 | Out-Null      # cancel
Start-Sleep -Milliseconds 900

"== 2. absolute path =="
if (EnterPath '/sdcard/Documents') { "    now at: $(CurrentTitle)" }
Shot 'p2-absolute'

"== 3. relative path (../Pictures) =="
if (EnterPath '../Pictures') { "    now at: $(CurrentTitle)" }
Shot 'p3-relative'

"== 4. tilde = internal storage =="
if (EnterPath '~') { "    now at: $(CurrentTitle)" }
Shot 'p4-tilde'

"== 5. a file path (opens parent + selects the file) =="
if (EnterPath '/sdcard/Documents/config.json') { "    now at: $(CurrentTitle)" }
Shot 'p5-file-path'

"== 6. a bad path =="
if (EnterPath '/sdcard/NoSuchFolder123') { "    stayed at: $(CurrentTitle)" }
Shot 'p6-bad-path'
