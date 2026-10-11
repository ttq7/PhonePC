# Extract authentic Windows shell icons at 256px using IShellItemImageFactory,
# the same API Explorer uses to render icons. Extension probes are real
# (empty) files so the shell resolves the real file-type association.

param(
    [string]$OutDir
)

$ErrorActionPreference = 'Stop'
if (-not $OutDir) { $OutDir = Join-Path (Get-Location).Path 'tools\icons_raw' }

Add-Type -AssemblyName System.Drawing

$sig = @'
using System;
using System.Runtime.InteropServices;
using System.Drawing;
using System.Drawing.Imaging;

public static class ShellImage
{
    [StructLayout(LayoutKind.Sequential)]
    public struct SIZE { public int cx; public int cy;
        public SIZE(int x, int y) { cx = x; cy = y; } }

    [Flags]
    public enum SIIGBF : uint
    {
        RESIZETOFIT    = 0x00,
        BIGGERSIZEOK   = 0x01,
        MEMORYONLY     = 0x02,
        ICONONLY       = 0x04,
        THUMBNAILONLY  = 0x08,
        INCACHEONLY    = 0x10
    }

    [ComImport, Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IShellItemImageFactory
    {
        [PreserveSig] int GetImage(SIZE size, SIIGBF flags, out IntPtr phbm);
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern int SHCreateItemFromParsingName(
        string pszPath, IntPtr pbc, ref Guid riid,
        [MarshalAs(UnmanagedType.Interface)] out object ppv);

    [DllImport("gdi32.dll")]
    private static extern bool DeleteObject(IntPtr hObject);

    [DllImport("gdi32.dll")]
    private static extern int GetDIBits(IntPtr hdc, IntPtr hbm, uint start, uint lines,
        byte[] bits, ref BITMAPINFO bmi, uint usage);

    [DllImport("user32.dll")]
    private static extern IntPtr GetDC(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);

    [StructLayout(LayoutKind.Sequential)]
    private struct BITMAPINFOHEADER
    {
        public int biSize; public int biWidth; public int biHeight;
        public short biPlanes; public short biBitCount; public int biCompression;
        public int biSizeImage; public int biXPelsPerMeter; public int biYPelsPerMeter;
        public int biClrUsed; public int biClrImportant;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BITMAPINFO
    {
        public BITMAPINFOHEADER bmiHeader;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 256)] public uint[] bmiColors;
    }

    private static readonly Guid IID_IShellItemImageFactory =
        new Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b");

    // HBITMAP -> 32bpp ARGB bitmap via GetDIBits (FromHbitmap drops alpha).
    private static Bitmap ToArgbBitmap(IntPtr hbm, int size)
    {
        BITMAPINFO bmi = new BITMAPINFO();
        bmi.bmiHeader.biSize = Marshal.SizeOf(typeof(BITMAPINFOHEADER));
        bmi.bmiHeader.biWidth = size;
        bmi.bmiHeader.biHeight = -size;      // top-down
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = 0;     // BI_RGB

        byte[] bits = new byte[size * size * 4];
        IntPtr hdc = GetDC(IntPtr.Zero);
        int lines;
        try { lines = GetDIBits(hdc, hbm, 0, (uint)size, bits, ref bmi, 0); }
        finally { ReleaseDC(IntPtr.Zero, hdc); }
        if (lines == 0) throw new Exception("GetDIBits returned 0 lines");

        // shell bitmaps are premultiplied BGRA; PNG wants straight alpha
        for (int i = 0; i < bits.Length; i += 4)
        {
            byte a = bits[i + 3];
            if (a != 0 && a != 255)
            {
                int b = bits[i] * 255 / a;
                int g = bits[i + 1] * 255 / a;
                int r = bits[i + 2] * 255 / a;
                bits[i]     = (byte)(b > 255 ? 255 : b);
                bits[i + 1] = (byte)(g > 255 ? 255 : g);
                bits[i + 2] = (byte)(r > 255 ? 255 : r);
            }
        }

        Bitmap bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb);
        BitmapData bd = bmp.LockBits(new Rectangle(0, 0, size, size),
            ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
        try { Marshal.Copy(bits, 0, bd.Scan0, bits.Length); }
        finally { bmp.UnlockBits(bd); }
        return bmp;
    }

    // Returns null on success, otherwise an error string.
    public static string SaveIcon(string path, int size, string target)
    {
        Guid iid = IID_IShellItemImageFactory;
        object obj;
        int hr = SHCreateItemFromParsingName(path, IntPtr.Zero, ref iid, out obj);
        if (hr != 0) return "SHCreateItemFromParsingName 0x" + hr.ToString("X8");

        IShellItemImageFactory factory = (IShellItemImageFactory)obj;
        IntPtr hbm;
        hr = factory.GetImage(new SIZE(size, size), SIIGBF.ICONONLY, out hbm);
        if (hr != 0)
            hr = factory.GetImage(new SIZE(size, size), SIIGBF.ICONONLY | SIIGBF.BIGGERSIZEOK, out hbm);
        if (hr != 0) return "GetImage 0x" + hr.ToString("X8");

        try
        {
            using (Bitmap bmp = ToArgbBitmap(hbm, size))
            {
                bmp.Save(target, ImageFormat.Png);
            }
        }
        finally { DeleteObject(hbm); }
        return null;
    }
}
'@
Add-Type -TypeDefinition $sig -ReferencedAssemblies 'System.Drawing' -ErrorAction Stop

if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# Real empty probe files so the shell resolves real associations.
$probeDir = Join-Path $env:TEMP 'phonepc_probe'
if (Test-Path $probeDir) { Remove-Item $probeDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $probeDir | Out-Null

$exts = @(
    'txt','log','ini','md','rtf','csv','json','xml','html','js','ts','kt','java','py','c','cpp','cs','sh','bat','sql','yml',
    'jpg','jpeg','png','gif','bmp','webp','svg','psd','heic',
    'mp3','wav','flac','aac','m4a','ogg','wma',
    'mp4','mkv','avi','mov','wmv','flv','webm',
    'pdf','doc','docx','xls','xlsx','ppt','pptx','one',
    'zip','rar','7z','tar','gz','iso',
    'apk','msi','dll','sys','lnk','url',
    'ttf','otf','db','sqlite','vcf','ics','eml'
)

$report = @()

foreach ($e in $exts) {
    $probe = Join-Path $probeDir "probe.$e"
    [System.IO.File]::WriteAllText($probe, '')
    $png = Join-Path $OutDir "file_$e.png"
    $err = [ShellImage]::SaveIcon($probe, 256, $png)
    $report += [pscustomobject]@{ Name = "file_$e"; Ok = (-not $err); Note = $err }
}

# generic "unknown file": no extension
$probe = Join-Path $probeDir 'probe_noext'
[System.IO.File]::WriteAllText($probe, '')
$err = [ShellImage]::SaveIcon($probe, 256, (Join-Path $OutDir 'file_generic.png'))
$report += [pscustomobject]@{ Name = 'file_generic'; Ok = (-not $err); Note = $err }

# A generic application icon: an empty .exe falls back to the shell default.
$probe = Join-Path $probeDir 'probe.exe'
[System.IO.File]::WriteAllText($probe, '')
$err = [ShellImage]::SaveIcon($probe, 256, (Join-Path $OutDir 'file_exe.png'))
$report += [pscustomobject]@{ Name = 'file_exe'; Ok = (-not $err); Note = $err }

$specials = [ordered]@{
    folder       = $env:SystemRoot
    folder_docs  = [Environment]::GetFolderPath('MyDocuments')
    folder_pics  = [Environment]::GetFolderPath('MyPictures')
    folder_music = [Environment]::GetFolderPath('MyMusic')
    folder_vids  = [Environment]::GetFolderPath('MyVideos')
    folder_dl    = (Join-Path $env:USERPROFILE 'Downloads')
    folder_desk  = [Environment]::GetFolderPath('Desktop')
    folder_user  = $env:USERPROFILE
    drive        = 'C:\'
    thispc       = '::{20D04FE0-3AEA-1069-A2D8-08002B30309D}'
    network      = '::{208D2C60-3AEA-1069-A2D7-08002B30309D}'
    recyclebin   = '::{645FF040-5081-101B-9F08-00AA002F954E}'
    controlpanel = '::{21EC2020-3AEA-1069-A2DD-08002B30309D}'
    quickaccess  = '::{679f85cb-0220-4080-b29b-5540cc05aab6}'
    gallery      = '::{e88865ea-0e1c-4e20-9aa6-edcd0212c87c}'
    onedrive     = '::{018D5C66-4533-4307-9B53-224DE2ED1FE6}'
}
foreach ($kv in $specials.GetEnumerator()) {
    $png = Join-Path $OutDir "$($kv.Key).png"
    $err = [ShellImage]::SaveIcon($kv.Value, 256, $png)
    $report += [pscustomobject]@{ Name = $kv.Key; Ok = (-not $err); Note = $err }
}

$report | Where-Object { -not $_.Ok } | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
"---"
"ok: $(($report | Where-Object Ok).Count) / $($report.Count)"
"png files: $((Get-ChildItem $OutDir -Filter *.png).Count)"
"sizes: " + ((Get-ChildItem $OutDir -Filter *.png | ForEach-Object { ([System.Drawing.Image]::FromFile($_.FullName)).Width } | Sort-Object -Unique) -join ',')
