$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }


# Build the promo film: title card, captioned clips, end card.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = (Join-Path $root '')
$raw  = Join-Path $root 'raw'
$work = Join-Path $root 'work'
$out  = (Join-Path $root '')
foreach ($d in @($work, (Split-Path $out))) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
Get-ChildItem $work -ErrorAction SilentlyContinue | Remove-Item -Force

$W = 1080; $H = 2400
function Font([single]$size, [string]$style = 'Regular') {
    New-Object System.Drawing.Font('Microsoft YaHei', $size, [System.Drawing.FontStyle]::$style,
        [System.Drawing.GraphicsUnit]::Pixel)
}
function NewCanvas {
    $bmp = New-Object System.Drawing.Bitmap($W, $H, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.TextRenderingHint = 'AntiAliasGridFit'
    $g.Clear([System.Drawing.Color]::Transparent)
    return @($bmp, $g)
}
function Center([System.Drawing.Graphics]$g, [string]$text, [System.Drawing.Font]$font,
                [System.Drawing.Brush]$brush, [single]$y, [single]$w = $W) {
    $sz = $g.MeasureString($text, $font)
    $g.DrawString($text, $font, $brush, ($w - $sz.Width) / 2, $y)
}

$accent = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 0, 120, 212))
$white  = [System.Drawing.Brushes]::White
$grey   = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 150, 155, 160))

# ---- title card ------------------------------------------------------------
$c = NewCanvas; $bmp = $c[0]; $g = $c[1]
$bg = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 32, 32, 32))
$g.FillRectangle($bg, 0, 0, $W, $H)
Center $g '此手机' (Font 190 'Bold') $white 880
Center $g '手机上的 Windows 文件管理器' (Font 52) $grey 1140
$g.FillRectangle($accent, 440, 1300, 200, 6)
Center $g 'Android · 原生 · 6 MB' (Font 40) $grey 1360
$bmp.Save("$work\00-title.png", [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

# ---- caption overlays (transparent, text near the bottom) ------------------
$captions = [ordered]@{
    'a-shell'  = @('Windows 11 界面', '标签页 · 面包屑 · 命令栏 · 深色模式')
    'b-search' = @('说你想找的，不用记得文件名', '「我上午在百度下载的图片」')
    'c-share'  = @('手机变 HTTP 服务器', '电脑浏览器直接浏览、下载、上传')
    'd-undo'   = @('删除进回收站 · 一键撤销', '长按弹出完整操作菜单')
}
foreach ($k in $captions.Keys) {
    $c = NewCanvas; $bmp = $c[0]; $g = $c[1]
    $band = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(190, 20, 20, 20))
    $g.FillRectangle($band, 0, 1980, $W, 300)
    Center $g $captions[$k][0] (Font 62 'Bold') $white 2030
    Center $g $captions[$k][1] (Font 40) $grey 2140
    $bmp.Save("$work\cap-$k.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose(); $band.Dispose()
}

# ---- end card --------------------------------------------------------------
$c = NewCanvas; $bmp = $c[0]; $g = $c[1]
$g.FillRectangle($bg, 0, 0, $W, $H)
Center $g '此手机' (Font 110 'Bold') $white 520
Center $g 'v2.4' (Font 44) $grey 690
$lines = @('自然语言搜索 · 局域网共享 · 回收站', '压缩包免解压浏览 · 内置看图/文本编辑',
           'Windows 图标 · RE 逆向/签名', 'APK 6 MB · 无广告 · 无联网')
$y = 900
foreach ($l in $lines) {
    $col = if ($l -like '*RE 逆向*') { $grey } else { $white }
    Center $g $l (Font 44) $col $y
    $y += 110
}
Center $g 'dist\此手机-2.4-debug.apk' (Font 40) $accent 1520
$bmp.Save("$work\99-end.png", [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose(); $bg.Dispose(); $accent.Dispose(); $grey.Dispose()

Get-ChildItem $work |
  ForEach-Object { "  {0,-20} {1,7:N0} KB" -f $_.Name, ($_.Length/1KB) }