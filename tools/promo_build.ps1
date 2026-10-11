$ErrorActionPreference = 'Stop'
$root = 'E:\此电脑\promo'
$raw  = Join-Path $root 'raw'
$work = Join-Path $root 'work'
$out  = 'E:\此电脑\dist\此手机-宣传片.mp4'

$W = 1080; $H = 2400; $FPS = 30
$V = "-c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p -r $FPS -movflags +faststart"

function FF([string]$line) {
    $full = "ffmpeg -y -v error -hide_banner $line"
    $o = Invoke-Expression $full 2>&1
    if ($LASTEXITCODE -ne 0) { Write-Host "FFMPEG FAILED:`n$full`n$o"; exit 1 }
}

FF "-loop 1 -i `"$work\00-title.png`" -t 3.0 -vf `"scale=${W}:${H},fade=in:0:20,fade=out:st=2.5:d=0.5`" $V `"$work\seg00.mp4`""
Write-Host '  seg00  title'
FF "-loop 1 -i `"$work\99-end.png`" -t 4.0 -vf `"scale=${W}:${H},fade=in:0:20,fade=out:st=3.5:d=0.5`" $V `"$work\seg99.mp4`""
Write-Host '  seg99  end'

$clips = @(
    @{ clip = 'a-shell';  seg = '01'; ss = '3.5';  t = '5.0'; cap = 'cap-a-shell'  },
    @{ clip = 'b-search'; seg = '02'; ss = '13.5'; t = '5.0'; cap = 'cap-b-search' },
    @{ clip = 'c-share';  seg = '03'; ss = '8.0';  t = '5.5'; cap = 'cap-c-share'  },
    @{ clip = 'd-undo';   seg = '04'; ss = '12.5'; t = '5.0'; cap = 'cap-d-undo'   }
)

foreach ($c in $clips) {
    $d  = [double]$c.t
    $fo = [math]::Round($d - 0.5, 2)
    $fc = "[0:v]scale=${W}:${H}:force_original_aspect_ratio=decrease," +
          "pad=${W}:${H}:(ow-iw)/2:(oh-ih)/2,fade=in:0:15,fade=out:st=${fo}:d=0.5[v];" +
          "[1:v]scale=${W}:${H}[c];[v][c]overlay=0:0,format=yuv420p[o]"

    $parts = @(
        "-ss $($c.ss) -t $d",
        "-i `"$raw\$($c.clip).mp4`"",
        "-loop 1 -i `"$work\$($c.cap).png`"",
        "-filter_complex `"$fc`"",
        "-map `"[o]`" -t $d",
        "$V `"$work\seg$($c.seg).mp4`""
    )
    FF ($parts -join ' ')
    Write-Host "  seg$($c.seg)  $($c.clip)  ${d}s"
}

$list  = Join-Path $work 'list.txt'
$order = @('seg00', 'seg01', 'seg02', 'seg03', 'seg04', 'seg99')
$lines = $order | ForEach-Object { "file '$_" + ".mp4'" }
[System.IO.File]::WriteAllLines($list, $lines, (New-Object System.Text.UTF8Encoding($false)))

FF "-f concat -safe 0 -i `"$list`" -c copy `"$out`""

Write-Host ''
if (Test-Path $out) {
    $dur = & ffprobe -v error -show_entries format=duration -of csv=p=0 $out
    $res = & ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 $out
    Write-Host "FINAL: $out"
    Write-Host ("  duration : {0:N1} s" -f [double]$dur)
    Write-Host ("  size     : {0:N2} MB" -f ((Get-Item $out).Length / 1MB))
    Write-Host "  video    : $res"
}
else { Write-Host 'no output produced' }
