# Generate real (decodable) test photos so thumbnail loading can be verified.
param([string]$OutDir)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
if (-not $OutDir) { $OutDir = (Get-Location).Path + '\tools\photos' }
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$specs = @(
    @{ N = '海边日落.jpg';   W = 1600; H = 900;  A = @(255,94,58);  B = @(255,196,84);  T = 'SUNSET' },
    @{ N = '雪山.png';       W = 900;  H = 1600; A = @(120,170,220); B = @(245,248,252); T = 'SNOW' },
    @{ N = '森林小径.jpg';   W = 1400; H = 1400; A = @(28,92,58);   B = @(140,200,120); T = 'FOREST' },
    @{ N = '城市夜景.jpg';   W = 1920; H = 1080; A = @(18,22,48);   B = @(90,60,160);   T = 'CITY' },
    @{ N = '花朵特写.png';   W = 1200; H = 800;  A = @(220,60,140); B = @(255,200,230); T = 'FLOWER' },
    @{ N = '沙漠公路.jpg';   W = 1500; H = 1000; A = @(210,160,90); B = @(250,225,175); T = 'DESERT' },
    @{ N = '海浪.jpg';       W = 1280; H = 720;  A = @(20,110,160); B = @(170,225,240); T = 'WAVE' },
    @{ N = '星空.png';       W = 1080; H = 1080; A = @(10,10,30);   B = @(70,70,140);   T = 'STARS' }
)

foreach ($s in $specs) {
    $bmp = New-Object System.Drawing.Bitmap($s.W, $s.H)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $rect = New-Object System.Drawing.Rectangle(0, 0, $s.W, $s.H)
    $c1 = [System.Drawing.Color]::FromArgb(255, $s.A[0], $s.A[1], $s.A[2])
    $c2 = [System.Drawing.Color]::FromArgb(255, $s.B[0], $s.B[1], $s.B[2])
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $c1, $c2, 45.0)
    $g.FillRectangle($brush, $rect)
    # a few shapes so the thumbnail is obviously the photo, not a flat colour
    $white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(70, 255, 255, 255))
    for ($i = 0; $i -lt 6; $i++) {
        $r = [int]($s.W * (0.08 + 0.10 * $i))
        $x = [int]($s.W * (0.06 * $i))
        $y = [int]($s.H * 0.55 - $r / 2)
        $g.FillEllipse($white, $x, $y, $r, $r)
    }
    $font = New-Object System.Drawing.Font('Segoe UI', [int]($s.W / 14), [System.Drawing.FontStyle]::Bold)
    $g.DrawString($s.T, $font, [System.Drawing.Brushes]::White, 40, 40)
    $g.Dispose(); $brush.Dispose(); $white.Dispose(); $font.Dispose()

    $path = Join-Path $OutDir $s.N
    if ($s.N -like '*.png') {
        $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    } else {
        $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Jpeg)
    }
    $bmp.Dispose()
    "  {0,-16} {1}x{2}  {3} KB" -f $s.N, $s.W, $s.H, [math]::Round((Get-Item $path).Length / 1KB)
}
"photos written to $OutDir"
