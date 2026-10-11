# Generate real, decodable text / json / wav test files for the built-in viewer.
param([string]$OutDir)

$ErrorActionPreference = 'Stop'
if (-not $OutDir) { $OutDir = Join-Path $env:TEMP 'phonepc_media' }
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# ---- text ------------------------------------------------------------------
$lines = @()
$lines += "# 此手机 · 内置文本查看器验证"
$lines += ""
$lines += "这是一段用于验证内置文本编辑器真实读写的多行内容。"
$lines += "行号统计、滚动、编辑、保存都会基于这个文件进行实测。"
$lines += ""
for ($i = 1; $i -le 60; $i++) {
    $lines += ("第 {0:D2} 行：The quick brown fox jumps over the lazy dog.  0123456789" -f $i)
}
$lines += ""
$lines += "结尾行 —— 如果你在编辑器里把这段改掉并保存，重新打开应该能看到修改。"
[System.IO.File]::WriteAllLines((Join-Path $OutDir 'a_notes.txt'), $lines, (New-Object System.Text.UTF8Encoding($false)))
"notes.txt  : $($lines.Count) lines"

# ---- json ------------------------------------------------------------------
$json = @'
{
  "name": "此手机",
  "version": "1.5",
  "features": [
    "内置图片查看器（缩放 / 翻页）",
    "内置文本查看与编辑",
    "内置音视频播放",
    "压缩包免解压浏览",
    "APK 信息查看"
  ],
  "builtInViewer": true,
  "newFileTypes": 19
}
'@
[System.IO.File]::WriteAllText((Join-Path $OutDir 'b_config.json'), $json, (New-Object System.Text.UTF8Encoding($false)))
"config.json: $((Get-Item (Join-Path $OutDir 'b_config.json')).Length) bytes"

# ---- wav (3s 22050Hz mono 16-bit sine sweep) -------------------------------
$rate = 22050
$seconds = 3
$count = $rate * $seconds
$path = Join-Path $OutDir 'c_tone.wav'
$fs = [System.IO.File]::Create($path)
$bw = New-Object System.IO.BinaryWriter($fs)
$dataSize = $count * 2
$bw.Write([char[]]'RIFF'); $bw.Write([uint32](36 + $dataSize))
$bw.Write([char[]]'WAVE'); $bw.Write([char[]]'fmt ')
$bw.Write([uint32]16); $bw.Write([uint16]1); $bw.Write([uint16]1)
$bw.Write([uint32]$rate); $bw.Write([uint32]($rate * 2)); $bw.Write([uint16]2); $bw.Write([uint16]16)
$bw.Write([char[]]'data'); $bw.Write([uint32]$dataSize)
for ($i = 0; $i -lt $count; $i++) {
    $t = $i / $rate
    $freq = 220 + 440 * ($t / $seconds)
    $env = [math]::Min(1.0, [math]::Min($t * 8, ($seconds - $t) * 8))
    $v = [int16]([math]::Sin(2 * [math]::PI * $freq * $t) * 12000 * $env)
    $bw.Write($v)
}
$bw.Flush(); $bw.Close(); $fs.Close()
"tone.wav   : $([math]::Round((Get-Item $path).Length/1KB)) KB"

Get-ChildItem $OutDir | Select-Object Name, @{n='KB';e={[math]::Round($_.Length/1KB)}}