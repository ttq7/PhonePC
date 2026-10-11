# v2.0: + tile, phone launcher icon, long-press context menu.
$sdk = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$dev = 'emulator-5554'
$dir = 'E:\此电脑\preview\v20'
if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dir | Out-Null

function A { & $adb -s $dev @args }
function Tap([int]$x, [int]$y) { A shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 1200 }
function Long([int]$x, [int]$y) { A shell input swipe $x $y $x $y 1200 | Out-Null; Start-Sleep -Milliseconds 1300 }
function Key([int]$k) { A shell input keyevent $k | Out-Null; Start-Sleep -Milliseconds 900 }
function Shot([string]$n) {
    $r = "/sdcard/_v_$n.png"
    A shell screencap -p $r | Out-Null
    $l = Join-Path $dir "$n.png"
    if (Test-Path $l) { Remove-Item $l -Force }
    A pull $r $l 2>&1 | Out-Null
    A shell rm $r | Out-Null
    if (Test-Path $l) { "  $n  ($((Get-Item $l).Length) bytes)" }
}
function Ui {
    A shell "uiautomator dump /sdcard/ui.xml" 2>&1 | Out-Null
    A pull /sdcard/ui.xml (Join-Path $env:TEMP 'ui.xml') 2>&1 | Out-Null
    (Get-Content (Join-Path $env:TEMP 'ui.xml') -Raw -Encoding UTF8)
}
function Find([string]$t) {
    $x = Ui
    foreach ($m in [regex]::Matches($x, 'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
        if ($m.Groups[1].Value -eq $t) {
            return @([int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2),
                     [int](([int]$m.Groups[3].Value + [int]$m.Groups[5].Value) / 2))
        }
    }
    return $null
}

A shell am force-stop com.phonepc.explorer | Out-Null
Start-Sleep -Milliseconds 600
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 4

"== 1. the + tile on the This PC page =="
Shot 'v1-thispc-plus'
$x = Ui
"  tile label 添加: " + ($x -match 'text="添加"')
"  hint 添加快捷路径: " + ($x -match '添加快捷路径')

"== 2. long-press a file -> context menu =="
Tap 72 178; Tap 200 553; Start-Sleep -Seconds 2      # Download
Long 320 974                                          # 手机助手.apk
Shot 'v2-context-menu'
$m = Ui
foreach ($o in '打开','打开方式','复制','剪切','重命名','共享','属性','删除','多选') {
    "  menu has $o : " + ($m -match "text=`"$o`"")
}

"== 3. choose 打开 -> the APK info dialog should open =="
$open = Find '打开'
"  打开 at: $($open -join ',')"
if ($open) { Tap $open[0] $open[1]; Start-Sleep -Seconds 3 }
Shot 'v3-opened'

"== 4. long-press a folder -> menu without 打开方式 =="
Key 4; Key 4; Key 4
A shell am start -n com.phonepc.explorer/.MainActivity | Out-Null
Start-Sleep -Seconds 3
Long 320 974
Shot 'v4-folder-menu'
$fm = Ui
$x2 = $fm
"  folder menu has 打开:   " + ($x2 -match 'text="打开"')
"  folder menu has 打开方式: " + ($x2 -match '打开方式')

"== 5. launcher icon =="
Key 4
A shell input keyevent 3 | Out-Null
Start-Sleep -Seconds 2
Shot 'v5-launcher'
Key 4
Start-Sleep -Seconds 1
