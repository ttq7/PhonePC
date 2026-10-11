$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }


$sdk = 'C:\Users\asd32\AppData\Local\TyranoToolKit\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$dev = 'emulator-5554'
function A { & $adb -s $dev @args }
function Dump {
    A shell "uiautomator dump /sdcard/ui.xml" 2>&1 | Out-Null
    A pull /sdcard/ui.xml (Join-Path $env:TEMP 'ui.xml') 2>&1 | Out-Null
    return (Get-Content (Join-Path $env:TEMP 'ui.xml') -Raw -Encoding UTF8)
}
function Shot([string]$n) {
    $r = "/sdcard/_s_$n.png"
    A shell screencap -p $r | Out-Null
    $l = (Join-Path $root "preview\sc\$n.png")
    if (Test-Path $l) { Remove-Item $l -Force }
    A pull $r $l 2>&1 | Out-Null
    A shell rm $r | Out-Null
}

# tap the positive button of the still-open removal dialog
A shell input tap 870 1416 | Out-Null
Start-Sleep -Seconds 2
Shot 's8-removed'
$after = Dump
"  图片 removed:   " + (-not ($after -match 'text="图片"'))
"  回收站 kept:    " + ($after -match 'text="回收站"')
"  内部存储 kept:  " + ($after -match 'text="内部存储"')
"  下载 kept:      " + ($after -match 'text="下载"')
"  添加 kept:      " + ($after -match '添加快捷路径')

"== long-press 内部存储 (the first row) =="
A shell input swipe 320 503 320 503 1200 | Out-Null
Start-Sleep -Seconds 1
Shot 's9-internal-longpress'
$d = Dump
"  removal dialog offered: " + ($d -match '移除快捷方式')

A shell input keyevent 4 | Out-Null
Start-Sleep -Milliseconds 500
A shell input keyevent 4 | Out-Null
Start-Sleep -Milliseconds 500