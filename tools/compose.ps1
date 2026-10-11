# Compose the real emulator screenshots into one overview image.
param(
    [string]$Dir,
    [string]$Out,
    [int]$Cols = 4,
    [int]$CellW = 320
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
if (-not $Dir) { $Dir = (Get-Location).Path + '\preview\screens' }
if (-not $Out) { $Out = (Split-Path $Dir -Parent) + '\screens-overview.png' }

$files = Get-ChildItem $Dir -Filter '*.png' | Sort-Object Name
if ($files.Count -eq 0) { throw "no screenshots in $Dir" }

$pad = 14
$labelH = 30
$rows = [math]::Ceiling($files.Count / $Cols)
$first = [System.Drawing.Image]::FromFile($files[0].FullName)
$cellH = [int]($CellW * $first.Height / $first.Width)
$first.Dispose()

$W = $pad + $Cols * ($CellW + $pad)
$H = $pad + $rows * ($cellH + $labelH + $pad)

$bmp = New-Object System.Drawing.Bitmap($W, $H)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.Clear([System.Drawing.Color]::FromArgb(222, 222, 228))
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$font = New-Object System.Drawing.Font('Microsoft YaHei UI', 10)
$brush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(38, 38, 42))

for ($i = 0; $i -lt $files.Count; $i++) {
    $c = $i % $Cols
    $r = [math]::Floor($i / $Cols)
    $x = $pad + $c * ($CellW + $pad)
    $y = $pad + $r * ($cellH + $labelH + $pad)

    $img = [System.Drawing.Image]::FromFile($files[$i].FullName)
    $g.DrawImage($img, $x, $y, $CellW, $cellH)
    $img.Dispose()

    $label = $files[$i].BaseName -replace '^\d+-', ''
    $sz = $g.MeasureString($label, $font)
    $g.DrawString($label, $font, $brush, ($x + ($CellW - $sz.Width) / 2), ($y + $cellH + 6))
}
$g.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"overview: $Out  ($W x $H)"
