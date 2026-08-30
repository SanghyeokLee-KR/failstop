# 로컬 스타일 게이트. CI(style.yml)와 같은 규칙을 검사한다.
# 사용:
#   powershell -File scripts\check-style.ps1            # 변경분(추가된 줄) 검사. CI와 동일 범위·규칙.
#   powershell -File scripts\check-style.ps1 <경로...>  # 지정 경로를 파일 단위 전체 검사(대상 지정용).
# 인자 없는 기본 모드는 기본 브랜치와의 분기점 이후 추가된 줄만 본다. 레거시 문서의 기존 위반을
# 로컬에서 실패로 잡던 CI 소급 면제 불일치를 없앤다. pathspec 제외·문구 면제는 style.yml과 1:1.

param([string[]]$Paths)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# 금지 문자는 리터럴로 두지 않는다. 이 파일 자체가 게이트를 통과해야 하기 때문.
$emDash = [string][char]0x2014
$enDash = [string][char]0x2013
$dashPattern = "$emDash|$enDash"
# 이모지 범위도 코드포인트로 조립한다. 소스에 리터럴이 남으면 자기 자신을 위반으로 잡는다.
$e1 = [char]0x2600; $e2 = [char]0x27BF; $vs = [char]0xFE0F
$emojiPattern = "[\uD83C-\uD83E][\uDC00-\uDFFF]|[$e1-$e2]|$vs"
$phrasePattern = "Generated with|Co-Authored-By: Claude|물론입니다|해 드리겠습니다|드리겠습니다!|Here is the (completed|finalized|final) document|Now writing the completed|I hope this helps|Let me know if you|Certainly!"

$exts = @(".java", ".kt", ".kts", ".ts", ".tsx", ".js", ".mjs", ".css", ".sql", ".yml", ".yaml", ".md", ".ps1", ".json", ".gradle")
$violations = 0

# 규칙 정의 파일은 금지 문구를 예시로 인용하므로 문구 검사만 면제한다. 대시·이모지 검사는 그대로 적용.
$phraseExempt = @("CLAUDE.md", "CONTRIBUTING.md", "PULL_REQUEST_TEMPLATE.md", "style.yml", "check-style.ps1")

function Test-Line([string]$label, [string]$line, [bool]$checkPhrase) {
    if ($line -match $script:dashPattern)  { Write-Host "$label dash 금지";   $script:violations++ }
    if ($line -match $script:emojiPattern) { Write-Host "$label 이모지 금지"; $script:violations++ }
    if ($checkPhrase -and $line -match $script:phrasePattern) { Write-Host "$label AI 어투/서명 금지"; $script:violations++ }
}

function Test-File([string]$file) {
    $lineNo = 0
    $checkPhrase = $script:phraseExempt -notcontains (Split-Path -Leaf $file)
    foreach ($line in [System.IO.File]::ReadLines($file)) {
        $lineNo++
        Test-Line "$file`:$lineNo" $line $checkPhrase
    }
}

# 기본 브랜치 해석. origin 우선, 로컬 폴백. 하나도 없으면 $null.
function Resolve-BaseRef {
    foreach ($ref in @('origin/main', 'main', 'origin/develop', 'develop')) {
        & git rev-parse --verify --quiet $ref *> $null
        if ($LASTEXITCODE -eq 0) { return $ref }
    }
    return $null
}

# 변경분 검사. style.yml과 동일하게 추가된 줄만, 동일 pathspec 제외로 본다.
# 분기점(merge-base) 이후 작업 트리까지 비교해 커밋 전 스테이징·미스테이징 변경도 잡는다.
function Test-Diff {
    $base = Resolve-BaseRef
    if (-not $base) {
        Write-Host "기본 브랜치를 찾지 못해 변경분 검사를 건너뛴다. 경로 인자로 파일 단위 검사를 쓸 것."
        return $false
    }
    $mergeBase = (& git merge-base $base HEAD).Trim()
    if ([string]::IsNullOrWhiteSpace($mergeBase)) { $mergeBase = $base }
    $excludes = @('.', ':!*.html', ':!*.pdf', ':!*.svg', ':!*.lock', ':!*.jar',
                  ':!package-lock.json', ':!**/node_modules/**', ':!web/out/**', ':!web/.next/**',
                  # 폐기 문서는 시점 기록이라 축어 보존한다. 규칙을 인용한 줄이 그 규칙에
                  # 걸리는 자기참조를 피한다. 구현 근거로 인용하지 않으므로 무해하다.
                  ':!docs/archive/**')
    $diff = & git diff --unified=0 $mergeBase -- @excludes
    $file = $null
    $checkPhrase = $true
    foreach ($raw in $diff) {
        if ($raw.StartsWith('+++ ')) {
            $p = $raw.Substring(4)
            if ($p -eq '/dev/null') { $file = $null; continue }
            if ($p.StartsWith('b/')) { $p = $p.Substring(2) }
            $file = $p
            $checkPhrase = $script:phraseExempt -notcontains (Split-Path -Leaf $file)
            continue
        }
        if ($raw.StartsWith('+') -and -not $raw.StartsWith('+++')) {
            $label = if ($file) { $file } else { '(diff)' }
            Test-Line $label $raw.Substring(1) $checkPhrase
        }
    }
    return $true
}

if (-not $Paths -or $Paths.Count -eq 0) {
    if (-not (Test-Diff)) { exit 2 }
}
else {
    foreach ($p in $Paths) {
        if (-not (Test-Path $p)) { continue }
        if (Test-Path $p -PathType Leaf) { Test-File $p; continue }
        Get-ChildItem $p -Recurse -File |
            Where-Object { $exts -contains $_.Extension -and $_.FullName -notmatch "node_modules|\.next|\\out\\" } |
            ForEach-Object { Test-File $_.FullName }
    }
}

if ($violations -gt 0) {
    Write-Host ""
    Write-Host "위반 $violations 건. CLAUDE.md 산출물 스타일 규칙 참조."
    exit 1
}
Write-Host "스타일 게이트 통과."
