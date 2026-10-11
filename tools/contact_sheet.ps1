# Build a labelled contact sheet from a folder of PNG icons so the picks can be
# verified visually.

param(
    [string]$Dir,
    [string]$Out,
    [int]$Cols = 8,
    [int]$Cell = 110
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not $Dir) { $Dir = Join-Path (Get-Location).Path 'tools\icons_raw' }
if (-not $Out) { $Out = Join-Path (Split-Path $Dir -Parent) 'contact_sheet.png' }

$files = Get-ChildItem $Dir -Filter *.png | Sort-Object Name
$rows = [math]::Ceiling($files.Count / $Cols)
$labelH = 26
$cellH = $Cell + $labelH
$W = $Cols * $Cell
$H = $rows * $cellH

$bmp = New-Object System.Drawing.Bitmap($W, $H)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.Clear([System.Drawing.Color]::FromArgb(32, 32, 32))
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$font = New-Object System.Drawing.Font('Consolas', 8.5)
$brush = [System.Drawing.Brushes]::White
$pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(70, 70, 70))

"source icons: $($files.Count)"
"sizes: " + (($files | ForEach-Object { ([System.Drawing.Image]::FromFile($_.FullName)).Width } | Sort-Object -Unique) -join ',')

for ($i = 0; $i -lt $files.Count; $i++) {
    $f = $files[$i]
    $c = $i % $Cols
    $r = [math]::Floor($i / $Cols)
    $x = $c * $Cell
    $y = $r * $cellH

    $g.DrawRectangle($pen, $x, $y, $Cell - 1, $cellH - 1)

    $img = [System.Drawing.Image]::FromFile($f.FullName)
    $pad = 4
    $avail = $Cell - 2 * $pad
    $scale = [math]::Min($avail / $img.Width, ($Cell - 2 * $pad) / $img.Height)
    $dw = [int]($img.Width * $scale)
    $dh = [int]($img.Height * $scale)
    $g.DrawImage($img, ($x + [int](($Cell - $dw) / 2)), ($y + $pad + [int](($Cell - 2 * $pad - $dh) / 2)), $dw, $dh)
    $img.Dispose()

    $label = $f.BaseName -replace '^file_', ''
    $g.DrawString($label, $font, $brush, ($x + 3), ($y + $Cell + 4))
}

$g.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"contact sheet: $Out  ($W x $H)"