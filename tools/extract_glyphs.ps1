# Render Win11 toolbar glyphs from Segoe Fluent Icons into black-on-transparent
# PNGs (the app tints them per theme, exactly like Explorer does).

param(
    [string]$OutDir,
    [string]$Sheet
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
if (-not $OutDir) { $OutDir = Join-Path (Get-Location).Path 'tools\glyphs_raw' }

$fonts = (New-Object System.Drawing.Text.InstalledFontCollection).Families |
         ForEach-Object { $_.Name } | Where-Object { $_ -match 'Segoe|Symbol' }
"installed icon-ish fonts: $($fonts -join ' | ')"

$familyName = 'Segoe Fluent Icons'
$ff = $null
foreach ($n in @('Segoe Fluent Icons', 'Segoe MDL2 Assets')) {
    try {
        $t = New-Object System.Drawing.FontFamily($n)
        $ff = $t; $familyName = $n; break
    } catch { }
}
if (-not $ff) { throw "no Segoe icon font available" }
"using font: $familyName"

# name -> codepoint (candidates; verified visually before use)
$glyphs = [ordered]@{
    menu        = 0xE700
    chevron_dn  = 0xE70D
    chevron_up  = 0xE70E
    chevron_rt  = 0xE76C
    add         = 0xE710
    close       = 0xE711
    more        = 0xE712
    settings    = 0xE713
    search      = 0xE721
    forward     = 0xE72A
    back        = 0xE72B
    refresh     = 0xE72C
    share       = 0xE72D
    favorite    = 0xE734
    up          = 0xE74A
    delete      = 0xE74D
    save        = 0xE74E
    sort        = 0xE8CB
    view        = 0xE890
    view2       = 0xE8A9
    list        = 0xE8FD
    grid        = 0xE80A
    home        = 0xE80F
    folder      = 0xE8B7
    new_folder  = 0xE8F4
    cut         = 0xE8C6
    copy        = 0xE8C8
    paste       = 0xE77F
    rename      = 0xE8AC
    select_all  = 0xE8B3
    properties  = 0xE8B1
    info        = 0xE946
    picture     = 0xE91B
    music       = 0xE8D6
    video       = 0xE714
    doc         = 0xE8A5
    download    = 0xE896
    desktop     = 0xE8FC
    thispc      = 0xE7F4
    phone       = 0xE8EA
    drive       = 0xE8B7
    network     = 0xE968
    history     = 0xE81C
    filter      = 0xE71C
    checkbox    = 0xE73A
    pin         = 0xE718
    sync        = 0xE895
    zip         = 0xE8B7
    details     = 0xE8FD
    tile        = 0xE8A9
    content     = 0xE8A9
    small_icon  = 0xE8A9
    med_icon    = 0xE80A
    lg_icon     = 0xE80A
    xl_icon     = 0xE80A
}

if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$PX = 256
foreach ($kv in $glyphs.GetEnumerator()) {
    $ch = [char]$kv.Value
    $bmp = New-Object System.Drawing.Bitmap($PX, $PX, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $fmt = [System.Drawing.StringFormat]::GenericTypographic
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $em = [single]($PX * 0.78)
    $path.AddString([string]$ch, $ff, [int][System.Drawing.FontStyle]::Regular, $em,
        (New-Object System.Drawing.PointF(0, 0)), $fmt)
    $b = $path.GetBounds()
    $g.TranslateTransform(($PX - $b.Width) / 2 - $b.X, ($PX - $b.Height) / 2 - $b.Y)
    $g.FillPath([System.Drawing.Brushes]::Black, $path)
    $g.ResetTransform()
    $g.Dispose(); $path.Dispose()
    $bmp.Save((Join-Path $OutDir "$($kv.Key).png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    "{0,-12} U+{1:X4}" -f $kv.Key, $kv.Value
}
"glyphs written: $((Get-ChildItem $OutDir -Filter *.png).Count)"
