# 문서 링크 게이트. 저장소 전체에서 docs 경로 인용을 찾아 실재 여부를 검사한다.
# 사용:
#   pwsh -NoProfile -File scripts\check-doclinks.ps1          # 전체 검사. 깨진 참조만 출력.
#   pwsh -NoProfile -File scripts\check-doclinks.ps1 -Stats   # 인용 통계까지 출력.
# 계약 문서를 인용한 코드·문서가 문서 이동·개명 뒤에도 유효한지 본다. 앵커(#절)와 절 표기는
# 파일 존재만 보고 내용은 보지 않는다. check-style.ps1과 같은 규약으로 종료코드 1이 위반이다.
# 규약 정본은 docs/contracts/하네스_CICD_IaC.md 부록 U.
# 발신이 docs/archive/인 인용은 판정하지 않는다. 폐기 문서는 시점 기록이라 축어 보존이
# 원칙이고, style.yml·check-style.ps1의 ':!docs/archive/**' 면제와 규약을 맞춘다.
# 사각이 하나 남는다. 폴더를 넘는 맨 파일명 인용(예: `백엔드_DB_설계.md`)은 경로 접두가
# 없어 집계에도 판정에도 들어가지 않는다. 인용 건수는 전량이 아니라 경로 인용의 수다.

param([switch]$Stats)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$docsRoot = Join-Path $root "docs"
$sep = [System.IO.Path]::DirectorySeparatorChar

$exts = @(".md", ".ps1", ".java", ".ts", ".tsx", ".yml", ".yaml", ".sql", ".js", ".mjs", ".cjs",
          ".json", ".py", ".css", ".csv", ".html", ".txt")
# 빌드 산출물과 의존성 트리는 원천이 아니다. 이름 세그먼트로 자르면 추적 원천인
# 제출_20260901/build/까지 함께 잘리므로 경로 고정형으로 지정한다. 이름 기준은
# 어느 위치에 있어도 원천이 아닌 둘만 남긴다.
$skipNames = @("node_modules", ".git")
# .claude/worktrees/ 는 다른 커밋을 체크아웃한 저장소 사본이라 그 시점의 인용을 그대로 갖는다.
# 문서를 옮긴 뒤 사본이 남아 있으면 이미 고친 인용이 깨진 것으로 다시 잡힌다.
# db/migration/ 은 적용 뒤 한 글자도 못 고친다(30절). 주석의 문서 경로까지 이 게이트가
# 잡으면 문서를 옮길 때마다 마이그레이션을 고치게 되고, 그러면 기존 볼륨을 쥔 노드가
# 체크섬 불일치로 전부 기동을 거부한다. 실제로 2026-08-31 에 주석 한 줄로 그렇게 됐다.
$skipPaths = @("server/build", "server/*/build", "server/.gradle",
               "web/.next", "web/.test-dist", "web/out", "seed-data/out",
               ".claude/worktrees", "server/app/src/main/resources/db/migration")
$skipFiles = @("package-lock.json")
# 폐기 문서 발신은 판정 대상이 아니다. 스타일 게이트의 archive 면제와 같은 규약이다.
$archivePrefix = "docs/archive/"

# 실재 판정은 대소문자를 구분한다. Windows 파일시스템은 구분하지 않으므로 실제 파일명
# 집합을 만들어 서수 비교한다. 배포 대상이 리눅스라 대소문자 오타를 여기서 잡아야 한다.
$docIndex = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
if (Test-Path -LiteralPath $docsRoot) {
    foreach ($f in [System.IO.Directory]::GetFiles($docsRoot, "*", [System.IO.SearchOption]::AllDirectories)) {
        [void]$docIndex.Add($f.Substring($root.Length + 1).Replace('\', '/'))
    }
}

# 경로 토큰은 공백과 인용·괄호·구분자에서 끊는다. 마크다운 링크와 git pathspec을 함께 다룬다.
$pathChars = '[^\s"''`,;|()\[\]{}<>*]+'
# 앞자리 제한이 URL 안 docs 세그먼트(예: 외부 API 문서 주소)를 인용으로 오인하지 않게 막는다.
$reAbs = [regex]('(?<![A-Za-z0-9_./\\-])docs/' + $pathChars)
$reRel = [regex]('(?<![A-Za-z0-9_./\\-])(?:\.\.?/)+' + $pathChars)
# 확장자에서 잘라야 뒤에 붙은 행번호(:258), 범위(:26~27), 앵커(#절), 한글 조사가 떨어진다.
$reExt = [regex]'^(.*?\.(?:md|markdown|pdf|html|htm|css|sql|ts|tsx|js|mjs|cjs|json|yml|yaml|csv|ps1|java|kt|png|jpg|jpeg|svg|txt|xml))(?![A-Za-z0-9])'
# 상대경로는 문서끼리의 상호참조만 본다. 문서 안 코드블록의 컨테이너 마운트 경로가
# 같은 모양이라 확장자로 갈라야 오탐이 없다.
$docExts = @(".md", ".markdown", ".pdf", ".html", ".htm")
# 명명 규칙 예시는 실재 대상이 아니다. 자리표시자 파일명을 판정에서 뺀다.
$rePlaceholder = [regex]'(N{3,}|X{3,})'

function Get-TargetFile {
    $files = New-Object System.Collections.Generic.List[string]
    $stack = New-Object System.Collections.Generic.Stack[string]
    $stack.Push($root)
    while ($stack.Count -gt 0) {
        $dir = $stack.Pop()
        foreach ($sub in [System.IO.Directory]::GetDirectories($dir)) {
            if ($skipNames -contains [System.IO.Path]::GetFileName($sub)) { continue }
            $subRel = $sub.Substring($root.Length + 1).Replace('\', '/')
            $skip = $false
            foreach ($pat in $skipPaths) { if ($subRel -like $pat) { $skip = $true; break } }
            if ($skip) { continue }
            $stack.Push($sub)
        }
        foreach ($f in [System.IO.Directory]::GetFiles($dir)) {
            if ($exts -notcontains [System.IO.Path]::GetExtension($f).ToLowerInvariant()) { continue }
            if ($skipFiles -contains [System.IO.Path]::GetFileName($f)) { continue }
            $files.Add($f)
        }
    }
    return $files
}

# 토큰을 저장소 기준 상대경로로 환원한다. docs 밖으로 나가면 판정 대상이 아니라 $null.
function Resolve-Citation([string]$token, [string]$fileDir) {
    if ($token.Contains('://')) { return $null }
    $m = $reExt.Match($token)
    if (-not $m.Success) { return $null }
    $p = $m.Groups[1].Value
    if ($rePlaceholder.IsMatch([System.IO.Path]::GetFileName($p))) { return $null }
    if ($p.StartsWith('./') -or $p.StartsWith('../')) {
        if ($docExts -notcontains [System.IO.Path]::GetExtension($p).ToLowerInvariant()) { return $null }
        $combined = [System.IO.Path]::Combine($fileDir, $p.Replace('/', $sep))
        $full = [System.IO.Path]::GetFullPath($combined)
        if (-not $full.StartsWith($docsRoot + $sep, [System.StringComparison]::OrdinalIgnoreCase)) { return $null }
        return $full.Substring($root.Length + 1).Replace('\', '/')
    }
    if ($p.StartsWith('docs/')) { return $p }
    return $null
}

# 저장소 기준 상대경로의 실재를 서수 비교로 확인한다. docs 밖도 봐야 해서 $docIndex로는 안 된다.
function Test-RepoFile([string]$rel) {
    $cur = $root
    $parts = $rel.Split('/')
    for ($i = 0; $i -lt $parts.Length - 1; $i++) {
        $next = $null
        foreach ($d in [System.IO.Directory]::GetDirectories($cur)) {
            if ([string]::Equals([System.IO.Path]::GetFileName($d), $parts[$i], [System.StringComparison]::Ordinal)) {
                $next = $d
                break
            }
        }
        if (-not $next) { return $false }
        $cur = $next
    }
    $leaf = $parts[$parts.Length - 1]
    foreach ($f in [System.IO.Directory]::GetFiles($cur)) {
        if ([string]::Equals([System.IO.Path]::GetFileName($f), $leaf, [System.StringComparison]::Ordinal)) { return $true }
    }
    return $false
}

# docs/README.md 색인은 표 안에서 docs/ 기준 상대경로로 적는다(README 7행). 경로 접두가 없어
# 절대·상대 어느 정규식에도 걸리지 않으므로 색인 발신에 한해 별도 규칙으로 판정한다.
# 색인 표에는 CLAUDE.md처럼 docs/ 밖을 가리키는 항목이 섞이므로 저장소 루트 기준도 함께 본다.
$indexFile = "docs/README.md"
$reBacktick = [regex]'`([^`]+)`'

# 색인 항목을 {Token, Target, Ok}로 환원한다. 판정 대상이 아니면 $null.
function Resolve-IndexEntry([string]$token) {
    if ($token.Contains('://')) { return $null }
    if ($token.StartsWith('docs/') -or $token.StartsWith('./') -or $token.StartsWith('../')) { return $null }
    $m = $reExt.Match($token)
    if (-not $m.Success) { return $null }
    $p = $m.Groups[1].Value
    # 문서 확장자로 좁혀야 토큰 파일명 언급(tokens.css)이나 데이터 파일이 섞이지 않는다.
    if ($docExts -notcontains [System.IO.Path]::GetExtension($p).ToLowerInvariant()) { return $null }
    if ($rePlaceholder.IsMatch([System.IO.Path]::GetFileName($p))) { return $null }
    if ($docIndex.Contains("docs/$p")) {
        return [pscustomobject]@{ Token = $token; Target = "docs/$p"; Ok = $true }
    }
    if (Test-RepoFile $p) {
        return [pscustomobject]@{ Token = $token; Target = $p; Ok = $true }
    }
    return [pscustomobject]@{ Token = $token; Target = "docs/$p"; Ok = $false }
}

$broken = New-Object System.Collections.Generic.List[string]
$perDoc = @{}
$totalRefs = 0
$refFiles = 0

foreach ($file in Get-TargetFile) {
    $rel = $file.Substring($root.Length + 1).Replace('\', '/')
    if ($rel.StartsWith($archivePrefix, [System.StringComparison]::Ordinal)) { continue }
    $dir = [System.IO.Path]::GetDirectoryName($file)
    $isIndex = ($rel -eq $indexFile)
    $lineNo = 0
    $hit = $false
    foreach ($line in [System.IO.File]::ReadLines($file, [System.Text.Encoding]::UTF8)) {
        $lineNo++
        $cites = New-Object System.Collections.Generic.List[object]
        if ($line.Contains('docs/') -or $line.Contains('./')) {
            $tokens = New-Object System.Collections.Generic.List[string]
            foreach ($m in $reAbs.Matches($line)) { $tokens.Add($m.Value) }
            foreach ($m in $reRel.Matches($line)) { $tokens.Add($m.Value) }
            foreach ($t in $tokens) {
                $target = Resolve-Citation $t $dir
                if (-not $target) { continue }
                $cites.Add([pscustomobject]@{ Token = $t; Target = $target; Ok = $docIndex.Contains($target) })
            }
        }
        # 색인 표의 행만 본다. 산문 줄의 백틱은 경로가 아니라 이름 언급이 섞인다.
        if ($isIndex -and $line.StartsWith('|')) {
            foreach ($m in $reBacktick.Matches($line)) {
                $entry = Resolve-IndexEntry $m.Groups[1].Value
                if ($entry) { $cites.Add($entry) }
            }
        }
        foreach ($c in $cites) {
            $totalRefs++
            $hit = $true
            if ($perDoc.ContainsKey($c.Target)) { $perDoc[$c.Target]++ } else { $perDoc[$c.Target] = 1 }
            if (-not $c.Ok) { $broken.Add("$rel`:$lineNo -> $($c.Token)") }
        }
    }
    if ($hit) { $refFiles++ }
}

foreach ($b in $broken) { Write-Host $b }

if ($Stats) {
    Write-Host ""
    Write-Host "문서별 인용 상위:"
    $perDoc.GetEnumerator() | Sort-Object -Property Value, Name -Descending | Select-Object -First 20 |
        ForEach-Object { Write-Host ("  {0,4}  {1}" -f $_.Value, $_.Key) }
}

Write-Host ""
Write-Host "경로 인용 $totalRefs 건, 인용 파일 $refFiles 개, 대상 문서 $($perDoc.Count) 종."
Write-Host "폴더를 넘는 맨 파일명 인용과 docs/archive/ 발신은 이 집계와 판정 밖이다."
if ($broken.Count -gt 0) {
    Write-Host "깨진 참조 $($broken.Count) 건. 문서를 옮겼으면 인용부터 고칠 것."
    exit 1
}
Write-Host "문서 링크 게이트 통과."
