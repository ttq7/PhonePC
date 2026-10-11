# Compose the launcher

$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }
 icon: Windows accent tile + white phone glyph.
param([string]$Root)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not $Root) { $Root = (Get-Location).Path }
$resDir = Join-Path $Root 'PhonePC\app\src\main\res'

$ff = $null
foreach ($name in @('Segoe Fluent Icons', 'Segoe MDL2 Assets')) {
    try { $ff = New-Object System.Drawing.FontFamily($name); break } catch { }
}
if (-not $ff) { throw 'no Segoe icon font found' }

function New-RoundedPath([single]$w, [single]$h, [single]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $p.AddArc(0, 0, $d, $d, 180, 90)
    $p.AddArc($w - $d, 0, $d, $d, 270, 90)
    $p.AddArc($w - $d, $h - $d, $d, $d, 0, 90)
    $p.AddArc(0, $h - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

$bg = [System.Drawing.Color]::FromArgb(255, 0, 120, 212)   # Windows 11 accent blue
$sizes = [ordered]@{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }

foreach ($k in $sizes.Keys) {
    $px = [int]$sizes[$k]
    $dir = Join-Path $resDir ("mipmap-" + $k)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    foreach ($round in @($false, $true)) {
        $bmp = New-Object System.Drawing.Bitmap($px, $px, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
        $g.Clear([System.Drawing.Color]::Transparent)

        $shape = if ($round) {
            $p = New-Object System.Drawing.Drawing2D.GraphicsPath
            $p.AddEllipse(0, 0, $px, $px)
            $p
        } else { New-RoundedPath $px $px ([single]($px * 0.22)) }

        $brush = New-Object System.Drawing.SolidBrush($bg)
        $g.FillPath($brush, $shape)

        # the phone glyph, white, optically centred
        $gp = New-Object System.Drawing.Drawing2D.GraphicsPath
        $em = [single]($px * 0.56)
        $fmt = [System.Drawing.StringFormat]::GenericTypographic
        $gp.AddString([string][char]0xE8EA, $ff, [int][System.Drawing.FontStyle]::Regular, $em,
            (New-Object System.Drawing.PointF(0, 0)), $fmt)
        $b = $gp.GetBounds()
        $g.TranslateTransform(($px - $b.Width) / 2 - $b.X, ($px - $b.Height) / 2 - $b.Y)
        $g.FillPath([System.Drawing.Brushes]::White, $gp)
        $g.ResetTransform()

        $g.Dispose(); $shape.Dispose(); $brush.Dispose(); $gp.Dispose()
        $leaf = if ($round) { 'ic_launcher_round.png' } else { 'ic_launcher.png' }
        $bmp.Save((Join-Path $dir $leaf), [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
    }
    "mipmap-$k  ${px}x${px}"
}

# --- a coloured "+" for the shortcut tile (list icons are not tinted) --------
foreach ($k in $sizes.Keys) {
    $px = [int]([int]$sizes[$k] * 1.6)
    $dir = Join-Path $resDir ("drawable-" + $k)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $bmp = New-Object System.Drawing.Bitmap($px, $px, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.Clear([System.Drawing.Color]::Transparent)
    $gp = New-Object System.Drawing.Drawing2D.GraphicsPath
    $em = [single]($px * 0.80)
    $fmt = [System.Drawing.StringFormat]::GenericTypographic
    $gp.AddString([string][char]0xE710, $ff, [int][System.Drawing.FontStyle]::Regular, $em,
        (New-Object System.Drawing.PointF(0, 0)), $fmt)
    $b = $gp.GetBounds()
    $g.TranslateTransform(($px - $b.Width) / 2 - $b.X, ($px - $b.Height) / 2 - $b.Y)
    $brush = New-Object System.Drawing.SolidBrush($bg)
    $g.FillPath($brush, $gp)
    $g.ResetTransform()
    $g.Dispose(); $gp.Dispose(); $brush.Dispose()
    $bmp.Save((Join-Path $dir 'ic_shortcut_add.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}
"plus icon written"