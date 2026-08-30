# 제출본 세 종을 정본에서 다시 굽는다. 세부는 build/README.md 를 본다.
$ErrorActionPreference = 'Stop'
$Build = Split-Path -Parent $MyInvocation.MyCommand.Path
$Sub = Split-Path -Parent $Build
$Diagram = Join-Path $Sub 'diagram'

function Step($n) { Write-Host "[$n]" -ForegroundColor Cyan }

Step '1/4 도면'
Push-Location $Diagram
python arch_aws.py | Out-Null
python arch_aws.py --slide | Out-Null
node render.mjs
# SVG 는 편집 가능한 원본으로 남긴다. 슬라이드용 SVG 는 PNG 만 쓰므로 지운다
Move-Item -Force 'arch_aws.svg' '시스템_아키텍처.svg'
Remove-Item -Force 'arch_aws_slide.svg' -ErrorAction SilentlyContinue
Pop-Location

Step '2/4 기획안 docx'
Push-Location $Sub
python (Join-Path $Build 'md2docx.py') '기획안_원본.md' '기획안.docx'
Pop-Location

Step '3/4 기획안 pdf'
# Word COM 을 쓴다. Word 가 이미 떠 있으면 실패할 수 있으니 닫고 실행한다
Push-Location $Sub
python -c "from docx2pdf import convert; convert('기획안.docx', '기획안.pdf')"
Pop-Location

Step '4/4 요약 pptx'
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
