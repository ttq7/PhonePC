# Build a test archive with nested folders, for the archive browser.
param([string]$OutDir)

$ErrorActionPreference = 'Stop'
if (-not $OutDir) { $OutDir = Join-Path $env:TEMP 'phonepc_zip' }
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
$src = Join-Path $OutDir 'src'
New-Item -ItemType Directory -Force -Path $src | Out-Null

$tree = @{
    '文档'            = @('说明.txt', '季度报告.docx', '预算表.xlsx')
    '文档\附件'       = @('明细.csv', '备注.md')
    '文档\附件\历史'  = @('2025汇总.txt', '2024汇总.txt')
    '图片'            = @('照片01.jpg', '照片02.png', '照片10.jpg')
    '安装包'          = @('setup.exe', 'readme.txt')
    ''                = @('readme.txt', 'data.json', 'license.txt')
}
foreach ($d in $tree.Keys) {
    $path = if ($d -eq '') { $src } else { Join-Path $src $d }
    New-Item -ItemType Directory -Force -Path $path | Out-Null
    foreach ($f in $tree[$d]) {
        $text = "此文件位于压缩包内：$d/$f`n生成时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')`n" +
                ("占位内容 " * 40)
        Set-Content -Path (Join-Path $path $f) -Value $text -Encoding UTF8
    }
}
$zip = Join-Path $OutDir 'test_archive.zip'
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $src '*') -DestinationPath $zip -CompressionLevel Optimal
"zip: $zip  ($([math]::Round((Get-Item $zip).Length/1KB)) KB)"

# what is inside, as the app should see it
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead($zip)
$z.Entries | Sort-Object FullName | ForEach-Object { "  {0,-28} {1,7} -> {2,6}" -f $_.FullName, $_.Length, $_.CompressedLength }
$z.Dispose()
