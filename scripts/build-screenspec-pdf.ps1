# 웹 화면명세서 PDF 생성기.
#
# 원본 HTML과 PDF는 docs/reference/contracts에 함께 둔다.
# 생성물 관리 기준은 docs/ai/writing.md, PDF §6.2는 백엔드 수용 기준이다.
#
# 사용. pwsh -NoProfile -File scripts/build-screenspec-pdf.ps1
#       -Check 는 기존 PDF의 존재와 헤더만 검사한다. HTML과 내용 일치는 판정하지 않는다.

param([switch]$Check)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$html = Join-Path $repo 'docs\reference\contracts\웹_화면명세서.html'
$targets = @(
    (Join-Path $repo 'docs\reference\contracts\웹_화면명세서.pdf')
)

if ($Check) {
    if (-not (Test-Path -LiteralPath $html -PathType Leaf)) { Write-Error 'PDF 원본 HTML이 없다.' }
    $bytes = [System.IO.File]::ReadAllBytes($targets[0])
    if ($bytes.Length -lt 5 -or [System.Text.Encoding]::ASCII.GetString($bytes, 0, 5) -ne '%PDF-') {
        Write-Error 'PDF 파일 헤더가 올바르지 않다.'
    }
    Write-Output 'PDF 파일과 헤더 확인. 원본 HTML과의 내용 일치는 별도 확인이 필요하다.'
    exit 0
}

# 크롬 계열이면 무엇이든 된다. 헤드리스 인쇄만 쓴다
$browser = @(
    "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
    "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
    "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
    "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $browser) { Write-Error "크롬 또는 엣지를 찾지 못했다. 헤드리스 인쇄에 필요하다." }

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("screenspec-" + [System.Guid]::NewGuid().ToString('N'))
function Remove-PrintWorkspace {
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/')
    $resolvedWork = [System.IO.Path]::GetFullPath($work)
    if (-not $resolvedWork.StartsWith($tempRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase) -or
        [System.IO.Path]::GetFileName($resolvedWork) -notmatch '^screenspec-[0-9a-f]{32}$') {
        throw '예상한 PDF 임시 폴더가 아니다.'
    }
    Remove-Item -LiteralPath $resolvedWork -Recurse -Force -ErrorAction SilentlyContinue
}
New-Item -ItemType Directory -Path $work | Out-Null
$out = Join-Path $work 'spec.pdf'

# 경로에 한글이 있어 파일 URI 를 인코딩해 넘긴다. 인코딩하지 않으면 크롬이 조용히
# 빈 페이지를 인쇄하고 종료 코드는 0 이다. 실패가 성공처럼 보이는 경로라 여기서 막는다
$uri = ([System.Uri]$html).AbsoluteUri

$proc = Start-Process -FilePath $browser -NoNewWindow -Wait -PassThru -ArgumentList @(
    '--headless=new', '--disable-gpu', '--no-first-run',
    "--user-data-dir=$work\profile",
    '--virtual-time-budget=20000', '--no-pdf-header-footer',
    "--print-to-pdf=$out", $uri
)

if ($proc.ExitCode -ne 0 -or -not (Test-Path $out)) {
    Remove-PrintWorkspace
    Write-Error "PDF 생성 실패. 브라우저 종료 코드 $($proc.ExitCode)."
}

foreach ($t in $targets) { Copy-Item $out $t -Force }
$hash = (Get-FileHash $targets[0]).Hash
Remove-PrintWorkspace

Write-Output "생성 완료. $($targets.Count) 사본, sha256 $($hash.Substring(0,16))"
