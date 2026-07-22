# 로컬 스타일 게이트. CI(style.yml)와 같은 규칙을 파일 단위로 검사한다.
# 사용: powershell -File scripts\check-style.ps1 [경로...]
# 경로 생략 시 server, web(app·lib·components·styles), harness, docs, .github, scripts, 루트 md를 검사한다.

param([string[]]$Paths)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if (-not $Paths -or $Paths.Count -eq 0) {
    $Paths = @(
        (Join-Path $root "server"),
        (Join-Path $root "web\app"),
        (Join-Path $root "web\lib"),
        (Join-Path $root "web\components"),
        (Join-Path $root "web\styles"),
        (Join-Path $root "harness"),
        (Join-Path $root "docs"),
        (Join-Path $root ".github"),
        (Join-Path $root "scripts"),
        (Join-Path $root "CLAUDE.md")
    )
}

# 금지 문자는 리터럴로 두지 않는다. 이 파일 자체가 게이트를 통과해야 하기 때문.
$emDash = [string][char]0x2014
$enDash = [string][char]0x2013
$dashPattern = "$emDash|$enDash"
# 이모지 범위도 코드포인트로 조립한다. 소스에 리터럴이 남으면 자기 자신을 위반으로 잡는다.
$e1 = [char]0x2600; $e2 = [char]0x27BF; $vs = [char]0xFE0F
$emojiPattern = "[\uD83C-\uD83E][\uDC00-\uDFFF]|[$e1-$e2]|$vs"
$phrasePattern = "Generated with|Co-Authored-By: Claude|물론입니다|해 드리겠습니다|드리겠습니다!|Here is the (completed|finalized|final) document|Now writing the completed|I hope this helps|Let me know if you|Certainly!"

$exts = @(".java", ".kt", ".kts", ".ts", ".tsx", ".css", ".sql", ".yml", ".yaml", ".md", ".ps1", ".json", ".gradle")
$violations = 0

# 규칙 정의 파일은 금지 문구를 예시로 인용하므로 문구 검사만 면제한다. 대시·이모지 검사는 그대로 적용.
$phraseExempt = @("CLAUDE.md", "CONTRIBUTING.md", "PULL_REQUEST_TEMPLATE.md", "style.yml", "check-style.ps1")

function Test-File([string]$file) {
    $lineNo = 0
    $checkPhrase = $script:phraseExempt -notcontains (Split-Path -Leaf $file)
    foreach ($line in [System.IO.File]::ReadLines($file)) {
        $lineNo++
        if ($line -match $script:dashPattern)   { Write-Host "$file`:$lineNo dash 금지";   $script:violations++ }
        if ($line -match $script:emojiPattern)  { Write-Host "$file`:$lineNo 이모지 금지"; $script:violations++ }
        if ($checkPhrase -and $line -match $script:phrasePattern) { Write-Host "$file`:$lineNo AI 어투/서명 금지"; $script:violations++ }
    }
}

foreach ($p in $Paths) {
    if (-not (Test-Path $p)) { continue }
    if (Test-Path $p -PathType Leaf) { Test-File $p; continue }
    Get-ChildItem $p -Recurse -File |
        Where-Object { $exts -contains $_.Extension -and $_.FullName -notmatch "node_modules|\.next|\\out\\" } |
        ForEach-Object { Test-File $_.FullName }
}

if ($violations -gt 0) {
    Write-Host ""
    Write-Host "위반 $violations 건. CLAUDE.md 산출물 스타일 규칙 참조."
    exit 1
}
Write-Host "스타일 게이트 통과."
