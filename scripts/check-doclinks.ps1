# 문서 링크 게이트. 저장소 전체에서 docs 경로 인용을 찾아 실재 여부를 검사한다.
# 사용:
#   pwsh -NoProfile -File scripts\check-doclinks.ps1          # 전체 검사. 깨진 참조만 출력.
#   pwsh -NoProfile -File scripts\check-doclinks.ps1 -Stats   # 인용 통계까지 출력.
# 계약 문서를 인용한 코드·문서가 문서 이동·개명 뒤에도 유효한지 본다. 앵커(#절)와 절 표기는
# 파일 존재만 보고 내용은 보지 않는다. check-style.ps1과 같은 규약으로 종료코드 1이 위반이다.

param([switch]$Stats)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$docsRoot = Join-Path $root "docs"
$sep = [System.IO.Path]::DirectorySeparatorChar

$exts = @(".md", ".ps1", ".java", ".ts", ".tsx", ".yml", ".yaml", ".sql", ".js", ".json", ".py")
# 빌드 산출물과 의존성 트리는 원천이 아니다. 이름이 같은 디렉터리 세그먼트 단위로 자른다.
$skipDirs = @("node_modules", ".next", ".git", "out", "build", "dist", ".test-dist", "target", "coverage", ".gradle")
$skipFiles = @("package-lock.json")

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
            if ($skipDirs -contains [System.IO.Path]::GetFileName($sub)) { continue }
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

$broken = New-Object System.Collections.Generic.List[string]
$perDoc = @{}
$totalRefs = 0
$refFiles = 0

foreach ($file in Get-TargetFile) {
    $rel = $file.Substring($root.Length + 1).Replace('\', '/')
    $dir = [System.IO.Path]::GetDirectoryName($file)
    $lineNo = 0
    $hit = $false
    foreach ($line in [System.IO.File]::ReadLines($file, [System.Text.Encoding]::UTF8)) {
        $lineNo++
        if (-not ($line.Contains('docs/') -or $line.Contains('./'))) { continue }
        $tokens = New-Object System.Collections.Generic.List[string]
        foreach ($m in $reAbs.Matches($line)) { $tokens.Add($m.Value) }
        foreach ($m in $reRel.Matches($line)) { $tokens.Add($m.Value) }
        foreach ($t in $tokens) {
            $target = Resolve-Citation $t $dir
            if (-not $target) { continue }
            $totalRefs++
            $hit = $true
            if ($perDoc.ContainsKey($target)) { $perDoc[$target]++ } else { $perDoc[$target] = 1 }
            if (-not $docIndex.Contains($target)) { $broken.Add("$rel`:$lineNo -> $t") }
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
Write-Host "인용 $totalRefs 건, 인용 파일 $refFiles 개, 대상 문서 $($perDoc.Count) 종."
if ($broken.Count -gt 0) {
    Write-Host "깨진 참조 $($broken.Count) 건. 문서를 옮겼으면 인용부터 고칠 것."
    exit 1
}
Write-Host "문서 링크 게이트 통과."
