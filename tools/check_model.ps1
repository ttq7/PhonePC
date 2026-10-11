$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT } else { (Get-Location).Path }

# project root: this script lives in <root>\tools
        elseif ($env:PHONEPC_ROOT) { $env:PHONEPC_ROOT }
        else { (Get-Location).Path }

# Validate the bge-small-zh ONNX bundle before integrating it.
#
# A surprisingly common failure: the "download" is actually an HTML error page
# or a git-LFS pointer, saved with the right name and the wrong content. This
# catches that before it turns into a confusing runtime error.

$ErrorActionPreference = 'Stop'
$dir = (Join-Path $root '')

if (-not (Test-Path $dir)) {
    "MISSING: $dir does not exist"
    "Create it and drop the files in — see README / the chat for the exact list."
    exit 1
}

$expect = @(
    @{ Name = 'model_quantized.onnx'; MinMB = 5;   MaxMB = 120; Kind = 'onnx' },
    @{ Name = 'tokenizer.json';       MinMB = 0.2; MaxMB = 20;  Kind = 'json' },
    @{ Name = 'config.json';          MinMB = 0.0005; MaxMB = 1; Kind = 'json' }
)

$ok = $true
foreach ($e in $expect) {
    $p = Join-Path $dir $e.Name
    if (-not (Test-Path $p)) {
        "  [MISSING] {0}" -f $e.Name
        $ok = $false
        continue
    }
    $len = (Get-Item $p).Length
    $mb = $len / 1MB
    $bytes = [System.IO.File]::ReadAllBytes($p)
    $head = [System.Text.Encoding]::ASCII.GetString($bytes, 0, [Math]::Min(200, $len))

    # git-lfs pointer files are tiny and start with "version https://git-lfs"
    if ($head.StartsWith('version https://git-lfs')) {
        "  [LFS POINTER, not the file] {0}  ({1:N3} MB)" -f $e.Name, $mb
        $ok = $false
        continue
    }
    # an HTML error page saved under the model's name
    if ($head.TrimStart().StartsWith('<')) {
        "  [HTML, not the file] {0}  ({1:N3} MB)" -f $e.Name, $mb
        $ok = $false
        continue
    }
    if ($mb -lt $e.MinMB -or $mb -gt $e.MaxMB) {
        "  [SUSPICIOUS SIZE] {0}  {1:N2} MB (expected {2}-{3} MB)" -f $e.Name, $mb, $e.MinMB, $e.MaxMB
        $ok = $false
        continue
    }

    if ($e.Kind -eq 'json') {
        try {
            $null = [System.Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
            "  [OK] {0}  {1:N2} MB  (valid JSON)" -f $e.Name, $mb
        } catch {
            "  [BAD JSON] {0}  {1}" -f $e.Name, $_.Exception.Message
            $ok = $false
        }
    } else {
        # ONNX is protobuf: field 1 (ir_version) => first byte 0x08
        $first = $bytes[0]
        if ($first -eq 0x08) {
            "  [OK] {0}  {1:N2} MB  (protobuf header looks like ONNX)" -f $e.Name, $mb
        } else {
            "  [NOT ONNX] {0}  first byte 0x{1:X2}, expected 0x08" -f $e.Name, $first
            $ok = $false
        }
    }
}

""
if ($ok) {
    "ALL GOOD — the bundle is ready to integrate."
} else {
    "NOT READY — fix the entries marked above."
    exit 1
}