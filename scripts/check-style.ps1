# 로컬 스타일 게이트. CI(style.yml)와 같은 규칙을 검사한다.
# 사용:
#   pwsh -NoProfile -File scripts/check-style.ps1            # 변경분(추가된 줄) 검사. CI와 동일 범위·규칙.
#   pwsh -NoProfile -File scripts/check-style.ps1 <경로...>  # 지정 경로를 파일 단위 전체 검사(대상 지정용).
# 인자 없는 기본 모드는 기본 브랜치와의 분기점 이후 추가된 줄만 본다. 레거시 문서의 기존 위반을
# 로컬에서 실패로 잡던 CI 소급 면제 불일치를 없앤다. pathspec 제외·문구 면제는 style.yml과 1:1.
# 종료 코드: 0 검사한 줄이 있고 위반 없음, 1 위반, 2 판정하지 않음(기준 브랜치 없음, 검사 대상 0줄,
# 또는 경로 모드에서 없는 경로나 확장자 필터 뒤 파일이 0개인 디렉터리가 하나라도 있음).
# 검사 대상이 없을 때 통과로 끝내지 않는다. 이미 커밋해 기준 브랜치에 들어간 변경이나
# 추적하지 않는 새 파일은 변경분에 잡히지 않으므로 경로 인자로 검사한다.

param([string[]]$Paths)

# -File로 경로를 여러 개 주면 첫 경로만 $Paths에 묶이고 나머지는 $args로 온다. 합치지 않으면
# 나머지 경로를 조용히 건너뛴다. [Parameter()]로 받지 않는 이유는 docs/ai/engineering.md 30절이다.
$Paths = @(@($Paths) + @($args) | Where-Object { $_ })

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

# 경로 모드가 여는 확장자. 명령 출력 원본(.txt·.log, 증거본과 harness/evidence)은 넣지 않는다.
# 증거 원본은 출력 그대로 보존해야 해서 위반이 나와도 고칠 수 없기 때문이다. 변경분 모드는
# CI와 같게 확장자를 거르지 않으므로 새로 추가한 .txt·.log 줄도 그쪽에서는 검사된다.
$exts = @(".java", ".kt", ".kts", ".ts", ".tsx", ".js", ".mjs", ".css", ".sql", ".yml", ".yaml", ".md", ".ps1", ".json", ".gradle")
$violations = 0
# 양성 증거 축. 몇 파일·몇 줄을 실제로 봤는지 세어 대상이 없는 통과를 가른다.
$checkedFiles = 0
$checkedLines = 0
# 경로 모드에서 검사하지 못한 인자. 하나라도 있으면 위반이 없어도 통과로 끝내지 않는다.
$uncheckedPaths = @()

# 규칙 정의 파일은 금지 문구를 예시로 인용하므로 문구 검사만 면제한다. 대시·이모지 검사는 그대로 적용.
$phraseExempt = @("CLAUDE.md", "CONTRIBUTING.md", "PULL_REQUEST_TEMPLATE.md", "style.yml", "check-style.ps1")

function Test-Line([string]$label, [string]$line, [bool]$checkPhrase) {
    $script:checkedLines++
    if ($line -match $script:dashPattern)  { Write-Host "$label dash 금지";   $script:violations++ }
    if ($line -match $script:emojiPattern) { Write-Host "$label 이모지 금지"; $script:violations++ }
    if ($checkPhrase -and $line -match $script:phrasePattern) { Write-Host "$label AI 어투/서명 금지"; $script:violations++ }
}

function Test-File([string]$file) {
    $script:checkedFiles++
    $lineNo = 0
    $checkPhrase = $script:phraseExempt -notcontains (Split-Path -Leaf $file)
    foreach ($line in [System.IO.File]::ReadLines($file)) {
        $lineNo++
        Test-Line "$file`:$lineNo" $line $checkPhrase
    }
}

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
    Write-Host "변경분 검사: 기준 $base, 분기점 $mergeBase 이후 작업 트리까지."
    $excludes = @('.', ':!*.html', ':!*.pdf', ':!*.svg', ':!*.lock', ':!*.jar',
                  ':!package-lock.json', ':!**/node_modules/**', ':!web/out/**', ':!web/.next/**',
                  # 폐기 문서는 시점 기록이라 축어 보존한다. 규칙을 인용한 줄이 그 규칙에
                  # 걸리는 자기참조를 피한다. 구현 근거로 인용하지 않으므로 무해하다.
                  ':!docs/archive/**')
    $diff = & git diff --unified=0 $mergeBase -- @excludes
    $file = $null
    $checkPhrase = $true
    $fileCounted = $false
    foreach ($raw in $diff) {
        if ($raw.StartsWith('+++ ')) {
            $p = $raw.Substring(4)
            if ($p -eq '/dev/null') { $file = $null; continue }
            if ($p.StartsWith('b/')) { $p = $p.Substring(2) }
            $file = $p
            $fileCounted = $false
            $checkPhrase = $script:phraseExempt -notcontains (Split-Path -Leaf $file)
            continue
        }
        if ($raw.StartsWith('+') -and -not $raw.StartsWith('+++')) {
            # 삭제만 있는 파일은 검사한 파일로 세지 않는다
            if (-not $fileCounted) { $script:checkedFiles++; $fileCounted = $true }
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
        # 지정한 경로 하나가 검사되지 않았는데 나머지가 통과했다고 전체를 통과로 적지 않는다.
        # 없는 경로와 확장자 필터 뒤 파일이 0개인 디렉터리를 모아 끝에서 판정하지 않음으로 낸다.
        if (-not (Test-Path $p)) { $uncheckedPaths += "경로 없음: $p"; continue }
        if (Test-Path $p -PathType Leaf) { Test-File $p; continue }
        $before = $checkedFiles
        Get-ChildItem $p -Recurse -File |
            Where-Object { $exts -contains $_.Extension -and $_.FullName -notmatch "node_modules|\.next|\\out\\" } |
            ForEach-Object { Test-File $_.FullName }
        if ($checkedFiles -eq $before) { $uncheckedPaths += "검사한 파일 0개: $p" }
    }
}

foreach ($u in $uncheckedPaths) { Write-Host $u }

if ($violations -gt 0) {
    Write-Host ""
    Write-Host "위반 $($violations)건(파일 $($checkedFiles)개, 줄 $($checkedLines)개 검사). 규칙은 docs/ai/writing.md와 docs/reference/contracts/하네스_CICD_IaC.md 부록 S."
    exit 1
}
if ($uncheckedPaths.Count -gt 0) {
    Write-Host "판정하지 않음: 지정한 경로 $($uncheckedPaths.Count)개를 검사하지 못했다(나머지 파일 $($checkedFiles)개, 줄 $($checkedLines)개는 위반 없음). 통과가 아니다."
    Write-Host "경로 모드는 다음 확장자만 연다: $($exts -join ' ')"
    exit 2
}
if ($checkedLines -eq 0) {
    Write-Host "검사 대상 없음: 검사한 줄이 0줄이다(파일 $($checkedFiles)개). 통과가 아니다."
    if (-not $Paths -or $Paths.Count -eq 0) {
        Write-Host "커밋한 변경이나 새 파일은 경로 인자로 검사할 것."
    }
    else {
        Write-Host "경로 모드는 다음 확장자만 연다: $($exts -join ' ')"
    }
    exit 2
}
Write-Host "스타일 게이트 통과: 파일 $($checkedFiles)개, 줄 $($checkedLines)개 검사."
