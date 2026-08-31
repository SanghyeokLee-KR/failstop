# 제출본 세 종을 정본에서 다시 굽는다. 세부는 build/README.md 를 본다.
$ErrorActionPreference = 'Stop'
$Build = Split-Path -Parent $MyInvocation.MyCommand.Path
$Sub = Split-Path -Parent $Build
$Diagram = Join-Path $Sub 'diagram'

function Step($n) { Write-Host "[$n]" -ForegroundColor Cyan }

# Windows 는 python.exe 이름으로 Microsoft Store 실행 별칭을 PATH 앞에 둔다. 그 별칭은
# 파이썬이 깔려 있어도 스토어 안내문만 내고 종료코드 49 로 죽는다. 실체를 먼저 찾는다.
$Python = @(
    (Join-Path $env:LOCALAPPDATA 'Programs\Python\Python313\python.exe'),
    (Join-Path $env:LOCALAPPDATA 'Programs\Python\Python312\python.exe'),
    (Join-Path $env:LOCALAPPDATA 'Programs\Python\Python311\python.exe')
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $Python) {
    $cand = (Get-Command python.exe -ErrorAction SilentlyContinue).Source
    if ($cand -and $cand -notlike '*WindowsApps*') { $Python = $cand }
}
if (-not $Python) { throw '파이썬을 찾지 못했다. python.org 배포판을 설치한다.' }

Step '1/4 도면'
Push-Location $Diagram
# 생성기는 Node 다. 종전 파이썬판은 diagrams 패키지 리소스 경로를 절대경로로 박아
# 그 패키지가 깔린 PC 에서만 돌았다. 아이콘을 diagram/icons/ 로 뽑아 그 의존을 끊었다.
if (-not (Test-Path 'node_modules')) { npm install }
# 도면 셋을 전부 굽는다. 하나만 돌리면 나머지는 이전 SVG 가 남아 낡은 PNG 가 제출본에 섞인다
$Gen = @('arch_aws', 'incident_flow', 'layer_triage')
foreach ($g in $Gen) {
    node "$g.mjs" | Out-Null
    node "$g.mjs" --slide | Out-Null
}
node render.mjs
# draw.io 판은 손으로 고치는 편집본이다. 내용 원천은 lib/content.mjs 하나다
node drawio.mjs
# SVG 는 편집 가능한 원본으로 남긴다. 슬라이드용 SVG 는 PNG 만 쓰므로 지운다
$Keep = @{ 'arch_aws' = '시스템_아키텍처.svg'; 'incident_flow' = '장애대응_흐름.svg'; 'layer_triage' = '계층판별_트리.svg' }
foreach ($g in $Gen) {
    Move-Item -Force "$g.svg" $Keep[$g]
    Remove-Item -Force "${g}_slide.svg" -ErrorAction SilentlyContinue
}
Pop-Location

Step '2/4 기획안 docx'
Push-Location $Sub
& $Python (Join-Path $Build 'md2docx.py') '기획안_원본.md' '기획안.docx'
Pop-Location

Step '3/4 기획안 pdf'
# 변환기는 있는 것을 쓴다. LibreOffice 는 Word 설치를 요구하지 않아 빌드 PC 를 가리지 않고,
# docx2pdf 는 Word COM 이라 Word 가 깔린 PC 에서만 돈다. 종전 판은 docx2pdf 단독이라
# Word 가 없는 PC 에서는 제출본을 아예 만들 수 없었다.
Push-Location $Sub
$soffice = @(
    (Join-Path $env:ProgramFiles 'LibreOffice\program\soffice.com'),
    (Join-Path ${env:ProgramFiles(x86)} 'LibreOffice\program\soffice.com')
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

Remove-Item '기획안.pdf' -Force -ErrorAction SilentlyContinue
if ($soffice) {
    # 전용 프로필로 돌린다. 기본 프로필을 쓰면 LibreOffice 창이 떠 있을 때 변환이 거부된다
    # LibreOffice 26.x 는 stderr 에 "Could not find platform independent libraries" 를 낸다.
    # 자체 번들 파이썬의 기동 잡음이고 변환에는 영향이 없다. 종료코드와 산출물 존재로 판정한다.
    $profile = Join-Path ([IO.Path]::GetTempPath()) 'lo-build-profile'
    $profileUrl = 'file:///' + ($profile -replace '\\', '/')
    & $soffice --headless --norestore "-env:UserInstallation=$profileUrl" `
        --convert-to pdf --outdir $Sub '기획안.docx' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "LibreOffice 변환 실패 (exit $LASTEXITCODE)" }
} else {
    # Word COM. Word 가 이미 떠 있으면 실패할 수 있으니 닫고 실행한다
    & $Python -c "from docx2pdf import convert; convert('기획안.docx', '기획안.pdf')"
}
# 변환기가 조용히 아무것도 안 내는 경우가 있어 산출물 존재를 직접 본다
if (-not (Test-Path '기획안.pdf')) { throw '기획안.pdf 가 생성되지 않았다' }
Pop-Location

Step '4/4 요약 pptx'
# pptxgenjs 는 전역 설치라 Node 가 기본 경로로 못 찾는다
$env:NODE_PATH = Join-Path $env:APPDATA 'npm/node_modules'
# deck.js 는 도면 PNG 를 상대 경로로 읽는다. diagram 에서 실행해야 찾는다
Push-Location $Diagram
node (Join-Path $Build 'deck.js')
Move-Item -Force '기획안_요약.pptx' (Join-Path $Sub '기획안_요약.pptx')
Pop-Location

Write-Host ''
Get-ChildItem $Sub -Filter '기획안*' | ForEach-Object {
    '{0,10:N0} KB  {1}' -f ($_.Length / 1KB), $_.Name
}
Get-ChildItem $Diagram -Filter '*.png' | ForEach-Object {
    '{0,10:N0} KB  diagram/{1}' -f ($_.Length / 1KB), $_.Name
}
