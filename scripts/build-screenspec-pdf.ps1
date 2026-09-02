# 웹 화면명세서 PDF 생성기.
#
# 왜 저장소 안에 있는가. 13절이 생성물과 원본을 가르고, 생성기가 저장소 밖에 있으면
# 재현이 끊긴다고 못박는다. 실제로 이 PDF 는 한동안 수동 인쇄로만 갱신됐고, 그래서
# 원본 HTML 이 앞서 가고 PDF 가 뒤처지는 드리프트가 두 번 났다.
#
# 이 PDF 는 생성물이지만 10절이 6-2 를 백엔드 수용 기준 정본으로 지정했다. 그래서
# 두 사본(docs/contracts, 제출_20260901)을 같은 바이트로 유지한다. 원본은 항상 HTML 이다.
#
# 사용. pwsh -NoProfile -File scripts/build-screenspec-pdf.ps1
#       -Check 를 주면 생성하지 않고 두 사본의 해시 일치만 검사한다(게이트용).

param([switch]$Check)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$html = Join-Path $repo 'docs\contracts\웹_화면명세서.html'
$targets = @(
    (Join-Path $repo 'docs\contracts\웹_화면명세서.pdf'),
    (Join-Path $repo '제출_20260901\웹_화면명세서.pdf')
)

if ($Check) {
    $hashes = $targets | ForEach-Object { (Get-FileHash $_).Hash }
    if ($hashes[0] -ne $hashes[1]) {
        Write-Error "PDF 두 사본이 다르다. 이 스크립트를 인자 없이 돌려 다시 생성하라."
    }
    Write-Output "PDF 사본 일치. $($hashes[0].Substring(0,16))"
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
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
    Write-Error "PDF 생성 실패. 브라우저 종료 코드 $($proc.ExitCode)."
}

foreach ($t in $targets) { Copy-Item $out $t -Force }
$hash = (Get-FileHash $targets[0]).Hash
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue

Write-Output "생성 완료. $($targets.Count) 사본, sha256 $($hash.Substring(0,16))"
