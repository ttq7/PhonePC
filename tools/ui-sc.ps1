# v1.9: This PC shortcut page, phone icon, renamed 文件共享.
param([int]$Port = 5554)

$ErrorActionPreference = 'Continue'
$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }
$sdk  = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb  = Join-Path $sdk 'platform-tools\adb.exe'
$dev  = "emulator-$Port"
$dir  = Join-Path $root 'preview\sc'
if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dir | Out-Null

function A { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { A shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 1100 }
function Long([int]$x, [int]$y) { A shell input swipe $x $y $x $y 1100 | Out-Null; Start-Sleep -Milliseconds 1200 }
function Shot([string]$n) {
    $r = "/sdcard/_s_$n.png"
    A shell screencap -p $r | Out-Null
    $l = Join-Path $dir "$n.png"
    if (Test-Path $l) { Remove-Item $l -Force }
    A pull $r $l 2>&1 | Out-Null
    A shell rm $r | Out-Null
    if (Test-Path $l) { "  $n  ($((Get-Item $l).Length) bytes)" }
}
function Ui {
    A shell "uiautomator dump /sdcard/ui.xml" 2>&1 | Out-Null
    $l = Join-Path $env:TEMP 'ui.xml'
    A pull /sdcard/ui.xml $l 2>&1 | Out-Null
    (Get-Content $l -Raw -Encoding UTF8)
}
function Find([string]$text) {
    $x = Ui
    foreach ($m in [regex]::Matches($x, '<node[^>]*>')) {
        $n = $m.Value
        if ($n -match ('text="' + [regex]::Escape($text) + '"') -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            return @([int](([int]$Matches[1] + [int]$Matches[3]) / 2), [int](([int]$Matches[2] + [int]$Matches[4]) / 2))
        }
    }
    return $null
}

A shell am force-stop com.phonepc.explorer | Out-Null
Start-Sleep -Milliseconds 600
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 4

"== 1. the This PC page =="
Shot 's1-thispc'
$x = Ui
"  has 内部存储:  " + ($x -match '内部存储')
"  has 添加 tile: " + ($x -match '添加快捷路径')
foreach ($n in '下载','文档','图片','相机','音乐','视频') { "  default shortcut $n : " + ($x -match "text=`"$n`"") }

"== 2. drawer: phone icon, no collapsible group, 文件共享 =="
Tap 72 178
Shot 's2-drawer'
$x = Ui
"  has 文件共享: " + ($x -match '文件共享')
"  has 网络:     " + ($x -match 'text="网络"')
A shell input keyevent 4 | Out-Null; Start-Sleep -Milliseconds 900

"== 3. add 回收站 =="
$add = Find '添加快捷路径'
"  add tile at: $($add -join ',')"
if ($add) {
    Tap $add[0] $add[1]
    Shot 's3-add-picker'
    $rec = Find '回收站'
    "  picker 回收站 at: $($rec -join ',')"
    if ($rec) { Tap $rec[0] $rec[1]; Start-Sleep -Seconds 1 }
    Shot 's4-after-add'
    "  now has 回收站 row: " + ((Ui) -match '回收站')
}

"== 4. long-press a shortcut to remove =="
$img = Find '图片'
"  图片 shortcut at: $($img -join ',')"
if ($img) {
    Long $img[0] $img[1]
    Shot 's5-remove-dialog'
    $rm = Find '移除快捷方式'
    if ($rm) { Tap $rm[0] $rm[1]; Start-Sleep -Seconds 1 }
    Shot 's6-after-remove'
    "  图片 still present: " + ((Ui) -match 'text="图片"')
}

"== 5. long-press 内部存储 must not offer removal =="
$int = Find '内部存储'
"  内部存储 at: $($int -join ',')"
if ($int) { Long $int[0] $int[1]; Shot 's7-internal-longpress' }