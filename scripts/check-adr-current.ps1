# 0099가 모든 결정 ADR을 연결하고, 마지막 검토 뒤 원본이 바뀌지 않았는지 확인한다.
# 내용의 정확성이나 설계 충돌을 판정하지 않는다. 자료가 바뀌면 사람이 내용을 대조한 뒤
# 0099 본문과 sources-sha256 주석을 함께 갱신한다. 이 스크립트는 파일을 수정하지 않는다.
# 사용: pwsh -NoProfile -File scripts/check-adr-current.ps1 [-Root <저장소>] [-PrintDigest]
# 링크는 본문의 인라인 Markdown 링크를 검사한다. 코드 예시와 HTML 주석은 제외한다.

[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [switch]$PrintDigest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath($Root)
if (-not [System.IO.Directory]::Exists($repoRoot)) {
    Write-Host "검사할 저장소가 없다: $repoRoot"
    exit 1
}

$adrRelative = 'docs/reference/adr'
$adrDirectory = Join-Path $repoRoot $adrRelative
$masterName = '0099-current-architecture.md'
$masterPath = Join-Path $adrDirectory $masterName
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)
$sources = [System.Collections.Generic.List[string]]::new()
$adrs = @()
if ([System.IO.Directory]::Exists($adrDirectory)) {
    $adrs = @(Get-ChildItem -LiteralPath $adrDirectory -File |
        Where-Object { $_.Name -cmatch '^\d{4}(?:-.+)?\.md$' })
}
foreach ($adr in $adrs) {
    if (-not $adr.Name.StartsWith('0099', [System.StringComparison]::Ordinal)) {
        $sources.Add("$adrRelative/$($adr.Name)")
    }
}
foreach ($relative in @('docs/ROADMAP.md', 'deploy/README.md', '아키텍쳐/README.md')) {
    if ([System.IO.File]::Exists((Join-Path $repoRoot $relative))) { $sources.Add($relative) }
}
foreach ($directory in @('docs/reference/architecture', 'docs/reference/contracts',
                          'docs/reference/product', 'docs/reference/operations', 'docs/measurements')) {
    $absolute = Join-Path $repoRoot $directory
    if (-not [System.IO.Directory]::Exists($absolute)) { continue }
    foreach ($file in Get-ChildItem -LiteralPath $absolute -File -Filter '*.md') {
        $sources.Add("$directory/$($file.Name)")
    }
}
$deployDirectory = Join-Path $repoRoot 'deploy'
if ([System.IO.Directory]::Exists($deployDirectory)) {
    foreach ($file in Get-ChildItem -LiteralPath $deployDirectory -File -Filter 'compose.node-*.yml') {
        $sources.Add("deploy/$($file.Name)")
    }
}
$sources.Sort([System.StringComparer]::Ordinal)

function Read-NormalizedText([string]$path) {
    return [System.IO.File]::ReadAllText($path, $utf8).Replace("`r`n", "`n").Replace("`r", "`n")
}

# 각 파일을 경로, 본문 순서로 넣는다. 각 항목 앞의 UTF-8 바이트 길이와 LF가 경계를
# 정하므로 본문이나 파일명에 구분 문자와 같은 글자가 있어도 다른 입력과 섞이지 않는다.
# 경로 구분자는 /, 정렬은 서수 비교, 본문 개행은 LF다. BOM은 ReadAllText가 제거한다.
$hash = [System.Security.Cryptography.IncrementalHash]::CreateHash(
    [System.Security.Cryptography.HashAlgorithmName]::SHA256)
try {
    foreach ($relative in $sources) {
        foreach ($value in @($relative, (Read-NormalizedText (Join-Path $repoRoot $relative)))) {
            $bytes = $utf8.GetBytes($value)
            $length = $bytes.Length.ToString([System.Globalization.CultureInfo]::InvariantCulture)
            $hash.AppendData($utf8.GetBytes($length + "`n"))
            $hash.AppendData($bytes)
        }
    }
    $digest = [System.Convert]::ToHexString($hash.GetHashAndReset()).ToLowerInvariant()
} finally {
    $hash.Dispose()
}
if ($PrintDigest) {
    Write-Output $digest
    exit 0
}

$issues = [System.Collections.Generic.List[string]]::new()
$numbers = [System.Collections.Generic.Dictionary[string, string]]::new([System.StringComparer]::Ordinal)
foreach ($adr in $adrs) {
    $number = $adr.Name.Substring(0, 4)
    if ($numbers.ContainsKey($number)) {
        $issues.Add("ADR 번호 중복: $($numbers[$number]), $($adr.Name)")
    } else {
        $numbers.Add($number, $adr.Name)
    }
}
$masters = @($adrs | Where-Object { $_.Name.StartsWith('0099', [System.StringComparison]::Ordinal) })
if ($masters.Count -ne 1 -or $masters[0].Name -cne $masterName) {
    $issues.Add("0099 종합본은 $masterName 한 파일이어야 한다.")
}

function Get-VisibleMarkdown([string]$source) {
    $source = [regex]::Replace($source, '(?s)<!--.*?-->', '')
    $builder = [System.Text.StringBuilder]::new()
    $fence = ''
    $fenceLength = 0
    foreach ($line in ($source -split "`n")) {
        $marker = [regex]::Match($line, '^ {0,3}(`{3,}|~{3,})')
        if ($fenceLength -gt 0) {
            if ($marker.Success -and $marker.Groups[1].Value[0].ToString() -eq $fence -and
                $marker.Groups[1].Length -ge $fenceLength -and
                $line.Substring($marker.Index + $marker.Length).Trim().Length -eq 0) {
                $fenceLength = 0
            }
            continue
        }
        if ($marker.Success) {
            $fence = $marker.Groups[1].Value[0].ToString()
            $fenceLength = $marker.Groups[1].Length
        } elseif ($line -notmatch '^(?: {4}|\t)') {
            [void]$builder.AppendLine($line)
        }
    }
    return [regex]::Replace($builder.ToString(), '(?s)(?<!`)(`+)(?!`).*?(?<!`)\1(?!`)', '')
}

if ([System.IO.File]::Exists($masterPath)) {
    $master = Read-NormalizedText $masterPath
    $markers = [regex]::Matches($master, '(?m)^<!-- sources-sha256: ([0-9a-f]{64}) -->$')
    $markerStarts = [regex]::Matches($master, '<!--\s*sources-sha256:')
    if ($markers.Count -ne 1 -or $markerStarts.Count -ne 1) {
        $issues.Add('sources-sha256 주석은 소문자 64자리 해시로 정확히 한 번 적어야 한다.')
    } elseif ($markers[0].Groups[1].Value -cne $digest) {
        $issues.Add('원본 자료가 바뀌었다. 0099 내용을 검토한 뒤 sources-sha256을 갱신한다.')
    }

    $linked = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $linkPattern = '(?<!!)\[[^\]\r\n]+\]\(\s*(?:<(?<target>[^>\r\n]+)>|(?<target>[^\s()]+))(?:\s+["''][^)\r\n]*["''])?\s*\)'
    foreach ($link in [regex]::Matches((Get-VisibleMarkdown $master), $linkPattern)) {
        $target = ($link.Groups['target'].Value -split '[?#]', 2)[0]
        if (-not $target -or $target -match '^[a-zA-Z][a-zA-Z0-9+.-]*:' -or $target.StartsWith('//')) { continue }
        $target = [System.Uri]::UnescapeDataString($target)
        try {
            $base = $adrDirectory
            if ($target.StartsWith('/')) { $base = $repoRoot; $target = $target.TrimStart('/') }
            $resolved = [System.IO.Path]::GetFullPath($target, $base)
            [void]$linked.Add([System.IO.Path]::GetRelativePath($repoRoot, $resolved).Replace('\', '/'))
        } catch {
            # 잘못된 경로는 유효한 ADR 연결로 세지 않는다. 일반 링크 검사가 별도로 진단한다.
            continue
        }
    }
    foreach ($adr in $adrs) {
        if ($adr.Name.StartsWith('0099', [System.StringComparison]::Ordinal)) { continue }
        if (-not $linked.Contains("$adrRelative/$($adr.Name)")) {
            $issues.Add("0099에 ADR 본문 링크가 없다: $($adr.Name)")
        }
    }
}

if ($issues.Count -gt 0) {
    foreach ($issue in $issues) { Write-Host $issue }
    Write-Host '검토 후 -PrintDigest로 새 해시를 확인한다. 해시만 갱신해도 내용 검토가 보장되지는 않는다.'
    exit 1
}
Write-Host "ADR 종합본 검사 통과: 결정 ADR $($adrs.Count - 1)개, 원본 자료 $($sources.Count)개."
exit 0
