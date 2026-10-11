# Stage extracted Windows ar
tw
$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }
ork into Android resource folders at every density.

param(
    [string]$Root
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not $Root) { $Root = (Get-Location).Path }
$iconsDir  = Join-Path $Root 'tools\icons_raw'
$glyphsDir = Join-Path $Root 'tools\glyphs_raw'
$resDir    = Join-Path $Root 'PhonePC\app\src\main\res'

$densities = [ordered]@{ 'mdpi' = 1.0; 'hdpi' = 1.5; 'xhdpi' = 2.0; 'xxhdpi' = 3.0; 'xxxhdpi' = 4.0 }

# --- content icons: 48dp artwork --------------------------------------------
$content = [ordered]@{
    'ic_folder'        = 'folder'
    'ic_drive'         = 'drive'
    'ic_thispc'        = 'thispc'
    'ic_network'       = 'network'
    'ic_recyclebin'    = 'recyclebin'
    'ic_controlpanel'  = 'controlpanel'
    'ic_gallery'       = 'gallery'
    'ic_desktop'       = 'folder_desk'
    'ic_downloads'     = 'folder_dl'
    'ic_documents'     = 'folder_docs'
    'ic_pictures'      = 'folder_pics'
    'ic_music'         = 'folder_music'
    'ic_videos'        = 'folder_vids'
    'ic_user'          = 'folder_user'
    'ic_file'          = 'file_generic'
    'ic_file_text'     = 'file_txt'
    'ic_file_log'      = 'file_log'
    'ic_file_ini'      = 'file_ini'
    'ic_file_rtf'      = 'file_rtf'
    'ic_file_csv'      = 'file_csv'
    'ic_file_json'     = 'file_json'
    'ic_file_xml'      = 'file_xml'
    'ic_file_html'     = 'file_html'
    'ic_file_code'     = 'file_js'
    'ic_file_image'    = 'file_png'
    'ic_file_jpg'      = 'file_jpg'
    'ic_file_audio'    = 'file_m4a'      # mp3/wav/flac map here too
    'ic_file_video'    = 'file_mp4'      # webm maps here too
    'ic_file_pdf'      = 'file_pdf'
    'ic_file_word'     = 'file_docx'
    'ic_file_excel'    = 'file_xlsx'
    'ic_file_ppt'      = 'file_pptx'
    'ic_file_onenote'  = 'file_one'
    'ic_file_archive'  = 'file_zip'
    'ic_file_iso'      = 'file_iso'
    'ic_file_apk'      = 'file_apk'
    'ic_file_exe'      = 'file_exe'
    'ic_file_dll'      = 'file_dll'
    'ic_file_sys'      = 'file_sys'
    'ic_file_font'     = 'file_ttf'
    'ic_file_db'       = 'file_db'
    'ic_file_lnk'      = 'file_lnk'
    'ic_file_url'      = 'file_url'
    'ic_file_eml'      = 'file_eml'
    'ic_file_ics'      = 'file_ics'
    'ic_file_psd'      = 'file_psd'
    'ic_file_svg'      = 'file_svg'
    'ic_file_bat'      = 'file_bat'
    'ic_file_sql'      = 'file_sql'
    'ic_file_yml'      = 'file_yml'
    'ic_file_excel2'   = 'file_xls'
    'ic_file_word2'    = 'file_doc'
    'ic_file_ppt2'     = 'file_ppt'
    'ic_file_heic'     = 'file_heic'
    'ic_file_webp'     = 'file_webp'
    'ic_file_gif'      = 'file_gif'
    'ic_file_bmp'      = 'file_bmp'
    'ic_file_svgimg'   = 'file_svg'
}

# --- UI glyphs: render black, tint at runtime --------------------------------
$glyphMap = [ordered]@{
    'g_menu'       = 'menu'
    'g_back'       = 'back'
    'g_forward'    = 'forward'
    'g_up'         = 'up'
    'g_refresh'    = 'refresh'
    'g_add'        = 'add'
    'g_new_folder' = 'new_folder'
    'g_cut'        = 'cut'
    'g_copy'       = 'copy'
    'g_paste'      = 'paste'
    'g_rename'     = 'rename'
    'g_delete'     = 'delete'
    'g_share'      = 'share'
    'g_sort'       = 'sort'
    'g_view'       = 'view2'
    'g_search'     = 'search'
    'g_more'       = 'more'
    'g_close'      = 'close'
    'g_chevron_dn' = 'chevron_dn'
    'g_chevron_rt' = 'chevron_rt'
    'g_home'       = 'home'
    'g_grid'       = 'grid'
    'g_list'       = 'list'
    'g_details'    = 'details'
    'g_content'    = 'content'
    'g_tile'       = 'tile'
    'g_settings'   = 'settings'
    'g_info'       = 'info'
    'g_picture'    = 'picture'
    'g_music'      = 'music'
    'g_video'      = 'video'
    'g_doc'        = 'doc'
    'g_download'   = 'download'
    'g_desktop'    = 'desktop'
    'g_thispc'     = 'thispc'
    'g_phone'      = 'phone'
    'g_network'    = 'network'
    'g_select_all' = 'select_all'
    'g_favorite'   = 'favorite'
    'g_pin'        = 'pin'
    'g_folder'     = 'folder'
    'g_save'       = 'save'
    'g_history'    = 'history'
    'g_filter'     = 'filter'
    'g_checkbox'   = 'checkbox'
}

function Resize-Png {
    param([string]$Src, [int]$Size, [string]$Dst)
    $img = [System.Drawing.Image]::FromFile($Src)
    try {
        $bmp = New-Object System.Drawing.Bitmap($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $bmp.SetResolution(96, 96)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.Clear([System.Drawing.Color]::Transparent)
        $g.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
        $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $ia = New-Object System.Drawing.Imaging.ImageAttributes
        $ia.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $rect = New-Object System.Drawing.Rectangle(0, 0, $Size, $Size)
        $g.DrawImage($img, $rect, 0, 0, $img.Width, $img.Height, [System.Drawing.GraphicsUnit]::Pixel, $ia)
        $g.Dispose(); $ia.Dispose()
        $bmp.Save($Dst, [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
    } finally { $img.Dispose() }
}

# Only refresh the generated density folders – layouts, values and xml live
# under res/ as well and must survive a re-run.
Get-ChildItem $resDir -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'drawable-*' -or $_.Name -like 'mipmap-*' } |
    ForEach-Object { Remove-Item $_.FullName -Recurse -Force }

$missing = @()
$written = 0

foreach ($group in @(
    @{ Map = $content;  Src = $iconsDir;  Base = 48 },
    @{ Map = $glyphMap; Src = $glyphsDir; Base = 24 }
)) {
    foreach ($den in $densities.GetEnumerator()) {
        $dir = Join-Path $resDir ("drawable-" + $den.Key)
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $px = [int][math]::Round($group.Base * $den.Value)
        foreach ($m in $group.Map.GetEnumerator()) {
            $src = Join-Path $group.Src ($m.Value + '.png')
            if (-not (Test-Path $src)) { $missing += "$($m.Key) <- $($m.Value).png"; continue }
            Resize-Png -Src $src -Size $px -Dst (Join-Path $dir ($m.Key + '.png'))
            $written++
        }
    }
}

# --- launcher icon: built by tools\make_launcher.ps1, not from a Windows DLL ---
# (running this script must not clobber it)
"png resources written: $written"
if ($missing.Count) { "MISSING:"; $missing | Sort-Object -Unique | ForEach-Object { "  $_" } } else { "MISSING: none" }
"drawable dirs: " + ((Get-ChildItem $resDir -Directory | Select-Object -Expand Name) -join ', ')