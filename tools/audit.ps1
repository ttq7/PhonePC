# Button audit: tap every control and check whether the screen actually changes.
# A button whose before/after screenshots are byte-identical did nothing.
param([int]$Port = 5554)

$ErrorActionPreference = 'Continue'
$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }
$sdk  = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb  = Join-Path $sdk 'platform-tools\adb.exe'
$dev  = "emulator-$Port"
$tmp  = Join-Path $env:TEMP 'phonepc_audit'
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function A { & $adb -s $dev @args }
function Snap([string]$tag) {
    $remote = "/sdcard/_a_$tag.png"
    $local = Join-Path $tmp "$tag.png"
    A shell screencap -p $remote | Out-Null
    A pull $remote $local 2>&1 | Out-Null
    A shell rm $remote | Out-Null
    if (Test-Path $local) { (Get-FileHash $local -Algorithm MD5).Hash } else { 'MISSING' }
}
function Tap([int]$x, [int]$y) { A shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 1100 }
function Key([int]$k) { A shell input keyevent $k | Out-Null; Start-Sleep -Milliseconds 900 }
function Down([int]$n) { for ($i = 0; $i -lt $n; $i++) { A shell input keyevent 20 | Out-Null; Start-Sleep -Milliseconds 140 } }

$results = @()
function Probe([string]$name, [int]$x, [int]$y, [string]$reset = '') {
    $before = Snap 'b'
    Tap $x $y
    $after = Snap 'a'
    $changed = ($before -ne $after)
    $script:results += [pscustomobject]@{ Button = $name; X = $x; Y = $y; Reacted = $changed }
    "  {0,-26} ({1,4},{2,4})  {3}" -f $name, $x, $y, $(if ($changed) { 'OK' } else { 'NO CHANGE' })
    if ($reset -eq 'back') { Key 4 }
    elseif ($reset -eq 'back2') { Key 4; Key 4 }
    elseif ($reset -eq 'back3') { Key 4; Key 4; Key 4 }
}

A shell am force-stop com.phonepc.explorer | Out-Null
Start-Sleep -Milliseconds 500
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 4

"=== A. main window: title + toolbar (inside 内部存储) ==="
Tap 72 178; Tap 200 1121      # drawer -> 内部存储
Start-Sleep -Seconds 2
# after entering, drawer is closed
Probe '标题栏 ☰ 菜单'        72 178   'back'
Probe '地址栏 ← 返回'        58 318
Probe '地址栏 → 前进'        125 318
Probe '地址栏 ↑ 上一级'      192 318
Probe '地址栏 ⟳ 刷新'        259 318
Probe '标题栏 ▾ 视图菜单'    975 182  'back'
Probe '标题栏 ＋ 新建标签'   1029 182
Probe '命令栏 新建'          129 415  'back'
Probe '命令栏 剪切'          275 415
Probe '命令栏 复制'          410 415
Probe '命令栏 粘贴'          535 415
Probe '命令栏 重命名'        680 415
Probe '命令栏 ⋯ 更多'        1006 415 'back'
Probe '搜索框 清除按钮区'    1010 318
Probe '状态栏 详细信息按钮'  954 2333
Probe '状态栏 图标按钮'      1025 2333

"=== B. nav pane items ==="
Tap 72 178
Probe '导航 主页'            200 278  'back'
Tap 72 178
Probe '导航 图库'            200 361  'back'
Tap 72 178
Probe '导航 下载'            200 553  'back'
Tap 72 178
Probe '导航 文档'            200 648  'back'
Tap 72 178
Probe '导航 图片'            200 742  'back'
Tap 72 178
Probe '导航 相机'            200 837  'back'
Tap 72 178
Probe '导航 音乐'            200 932  'back'
Tap 72 178
Probe '导航 视频'            200 1026 'back'
Tap 72 178
Probe '导航 内部存储'        200 1121 'back'
Tap 72 178
Probe '导航 网络'            200 1217 'back'
Tap 72 178
Probe '导航 回收站'          200 1312 'back'

"=== summary ==="
$results | Where-Object { -not $_.Reacted } | ForEach-Object {
    "NO REACTION: {0}  at ({1},{2})" -f $_.Button, $_.X, $_.Y
}
"total probed: $($results.Count)   dead: $(($results | Where-Object { -not $_.Reacted }).Count)"
