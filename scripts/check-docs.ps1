# 문서 이동 뒤의 링크와 Claude/Codex 공통 규칙 연결을 검사한다.
# 사용: pwsh -NoProfile -File scripts/check-docs.ps1 [-Root <검사할 저장소>]
# Git 추적 여부와 관계없이 지정된 Markdown만 읽는다. 증거 원본, 빌드 결과와 다른
# worktree는 탐색하지 않는다. 일반 인라인 링크와 reference 링크를 지원한다.
# CommonMark 전체 구현은 아니다. HTML 링크, 앵커의 존재, 외부 URL, 코드 예시,
# 링크로 작성하지 않은 경로와 보고서 문장의 품질은 검사하지 않는다.
# 역할 설정은 현재 사용하는 단순 frontmatter/TOML 형식의 연결과 reviewer 제한만
# 확인한다. TOML 전체 문법, 다른 설정 파일과 실행 시의 실제 권한은 별도로 검증한다.

[CmdletBinding()]
param([string]$Root = (Split-Path -Parent $PSScriptRoot))

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::TrimEndingDirectorySeparator([System.IO.Path]::GetFullPath($Root))
if (-not [System.IO.Directory]::Exists($repoRoot)) {
    Write-Host "검사할 저장소가 없다: $repoRoot"
    exit 1
}

$issues = [System.Collections.Generic.List[string]]::new()
$files = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$connections = @{}
$codexSources = @{}
$pathCache = [System.Collections.Generic.Dictionary[string, bool]]::new([System.StringComparer]::Ordinal)
$localLinks = 0
$externalLinks = 0
$skipDirectories = @('.git', 'node_modules', 'build', 'out', '.next', '.gradle',
                     '.test-dist', 'artifacts', 'evidence', 'raw', 'worktrees')
$sharedRules = @('docs/ai/workflow.md', 'docs/ai/engineering.md',
                 'docs/ai/code-style.md', 'docs/ai/writing.md')
$skillFiles = @('.agents/skills/exchange-docs/SKILL.md', '.claude/skills/exchange-docs/SKILL.md')
$roles = @('backend-engineer', 'frontend-engineer', 'harness-engineer', 'reviewer')
$requiredFiles = @('README.md', 'AGENTS.md', 'CLAUDE.md', 'docs/README.md', 'docs/ROADMAP.md') +
                 $sharedRules + $skillFiles
foreach ($role in $roles) {
    $requiredFiles += @("docs/ai/roles/$role.md", ".claude/agents/$role.md", ".codex/agents/$role.toml")
}

function Get-RepoPath([string]$relative) {
    return [System.IO.Path]::Combine($repoRoot, $relative.Replace('/', [System.IO.Path]::DirectorySeparatorChar))
}

function Get-RelativePath([string]$path) {
    return [System.IO.Path]::GetRelativePath($repoRoot, $path).Replace('\', '/')
}

# Windows에서도 실제 파일명과 대소문자가 맞아야 Linux의 링크가 유지된다.
function Test-ExactPath([string]$relative) {
    if ($pathCache.ContainsKey($relative)) { return $pathCache[$relative] }
    if ($relative -eq '.') { return $true }
    $current = $repoRoot
    $parts = @($relative.Split('/', [System.StringSplitOptions]::RemoveEmptyEntries))
    for ($i = 0; $i -lt $parts.Count; $i++) {
        if (-not [System.IO.Directory]::Exists($current)) {
            $pathCache[$relative] = $false
            return $false
        }
        $match = $null
        foreach ($entry in [System.IO.Directory]::EnumerateFileSystemEntries($current)) {
            if ([System.IO.Path]::GetFileName($entry) -ceq $parts[$i]) { $match = $entry; break }
        }
        if ($null -eq $match) { $pathCache[$relative] = $false; return $false }
        # 링크 자체의 존재는 확인하지만 하위 junction/symlink 안으로 들어가지는 않는다.
        if ($i -lt $parts.Count - 1 -and
            ([System.IO.File]::GetAttributes($match) -band [System.IO.FileAttributes]::ReparsePoint)) {
            $pathCache[$relative] = $false
            return $false
        }
        $current = $match
    }
    $pathCache[$relative] = $true
    return $true
}

function Add-MarkdownFiles([string]$directory) {
    if (-not [System.IO.Directory]::Exists($directory)) { return }
    if ([System.IO.File]::GetAttributes($directory) -band [System.IO.FileAttributes]::ReparsePoint) { return }
    $stack = [System.Collections.Generic.Stack[string]]::new()
    $stack.Push($directory)
    while ($stack.Count -gt 0) {
        $current = $stack.Pop()
        foreach ($entry in [System.IO.Directory]::EnumerateFileSystemEntries($current)) {
            $attributes = [System.IO.File]::GetAttributes($entry)
            if ($attributes -band [System.IO.FileAttributes]::ReparsePoint) { continue }
            if ($attributes -band [System.IO.FileAttributes]::Directory) {
                if ($skipDirectories -notcontains [System.IO.Path]::GetFileName($entry)) { $stack.Push($entry) }
            } elseif ([System.IO.Path]::GetExtension($entry) -ieq '.md') {
                [void]$files.Add($entry)
            }
        }
    }
}

function Hide-Text([string]$value) { return [regex]::Replace($value, '[^\r\n]', ' ') }

# 문자 위치와 개행을 남겨 검사 결과에 원래 줄 번호를 표시한다.
function Get-VisibleMarkdown([string]$source) {
    $withoutComments = [regex]::Replace($source, '(?s)<!--.*?-->',
        [System.Text.RegularExpressions.MatchEvaluator]{ param($match) Hide-Text $match.Value })
    $builder = [System.Text.StringBuilder]::new()
    $fenceCharacter = ''
    $fenceLength = 0
    foreach ($line in ($withoutComments -split "`n", 0, 'SimpleMatch')) {
        $marker = [regex]::Match($line, '^ {0,3}(`{3,}|~{3,})')
        if ($fenceLength -gt 0) {
            [void]$builder.AppendLine((Hide-Text $line))
            if ($marker.Success -and $marker.Groups[1].Value[0].ToString() -eq $fenceCharacter -and
                $marker.Groups[1].Length -ge $fenceLength -and
                $line.Substring($marker.Index + $marker.Length).Trim().Length -eq 0) {
                $fenceLength = 0
            }
            continue
        }
        if ($marker.Success) {
            $fenceCharacter = $marker.Groups[1].Value[0].ToString()
            $fenceLength = $marker.Groups[1].Length
            [void]$builder.AppendLine((Hide-Text $line))
        } elseif ($line -match '^(?: {4}|\t)') {
            [void]$builder.AppendLine((Hide-Text $line))
        } else {
            [void]$builder.AppendLine($line)
        }
    }
    return [regex]::Replace($builder.ToString(), '(?s)(?<!`)(`+)(?!`).*?(?<!`)\1(?!`)',
        [System.Text.RegularExpressions.MatchEvaluator]{ param($match) Hide-Text $match.Value })
}

function Get-LineNumber([string]$source, [int]$position) {
    return 1 + [regex]::Matches($source.Substring(0, $position), "`n").Count
}

function Read-Destination([string]$source, [int]$start) {
    $position = $start
    while ($position -lt $source.Length -and [char]::IsWhiteSpace($source[$position])) { $position++ }
    $valueStart = $position
    if ($position -lt $source.Length -and $source[$position] -eq '<') {
        $valueStart = ++$position
        while ($position -lt $source.Length) {
            if ($source[$position] -eq '\') { $position += 2; continue }
            if ($source[$position] -eq '>') {
                return @{ Value = $source.Substring($valueStart, $position - $valueStart); End = $position + 1 }
            }
            if ($source[$position] -eq "`n") { return $null }
            $position++
        }
        return $null
    }
    $depth = 0
    while ($position -lt $source.Length) {
        $character = $source[$position]
        if ($character -eq '\') { $position += 2; continue }
        if ([char]::IsWhiteSpace($character)) { break }
        if ($character -eq '(') { $depth++ }
        if ($character -eq ')') {
            if ($depth -eq 0) { break }
            $depth--
        }
        $position++
    }
    if ($depth -ne 0 -or $position -gt $source.Length) { return $null }
    return @{ Value = $source.Substring($valueStart, $position - $valueStart); End = $position }
}

function Find-ClosingBracket([string]$source, [int]$start) {
    $depth = 1
    for ($position = $start + 1; $position -lt $source.Length; $position++) {
        if ($source[$position] -eq '\') { $position++; continue }
        if ($source[$position] -eq '[') { $depth++ }
        if ($source[$position] -eq ']') { $depth-- }
        if ($depth -eq 0) { return $position }
    }
    return -1
}

function Normalize-Reference([string]$label) { return ([regex]::Replace($label.Trim(), '\s+', ' ')).ToLowerInvariant() }

function Get-MarkdownLinks([string]$source, [string]$relative) {
    $visible = Get-VisibleMarkdown $source
    $targets = [System.Collections.Generic.List[object]]::new()
    $definitions = @{}
    $definitionPattern = '(?m)^ {0,3}\[([^\]\r\n]+)\]:[ \t]*'
    foreach ($match in [regex]::Matches($visible, $definitionPattern)) {
        $destination = Read-Destination $visible ($match.Index + $match.Length)
        if ($null -eq $destination -or $destination.Value.Length -eq 0) { continue }
        $label = Normalize-Reference $match.Groups[1].Value
        if (-not $definitions.ContainsKey($label)) {
            $definitions[$label] = $destination.Value
            $targets.Add(@{ Value = $destination.Value; Line = (Get-LineNumber $visible $match.Index) })
        }
    }
    $visible = [regex]::Replace($visible, '(?m)^ {0,3}\[[^\]\r\n]+\]:[^\r\n]*',
        [System.Text.RegularExpressions.MatchEvaluator]{ param($match) Hide-Text $match.Value })
    for ($position = 0; $position -lt $visible.Length; $position++) {
        if ($visible[$position] -eq '\') { $position++; continue }
        if ($visible[$position] -ne '[') { continue }
        $end = Find-ClosingBracket $visible $position
        if ($end -lt 0) { continue }
        $next = $end + 1
        if ($next -lt $visible.Length -and $visible[$next] -eq '(') {
            $destination = Read-Destination $visible ($next + 1)
            if ($null -eq $destination) { continue }
            $close = $destination.End
            while ($close -lt $visible.Length -and [char]::IsWhiteSpace($visible[$close])) { $close++ }
            # 선택적인 링크 제목은 경로가 아니다.
            if ($close -lt $visible.Length -and $visible[$close] -in @('"', "'", '(')) {
                $quote = $visible[$close]
                if ($quote -eq '(') { $quote = ')' }
                $close++
                while ($close -lt $visible.Length -and $visible[$close] -ne $quote) {
                    if ($visible[$close] -eq '\') { $close++ }
                    $close++
                }
                $close++
                while ($close -lt $visible.Length -and [char]::IsWhiteSpace($visible[$close])) { $close++ }
            }
            if ($close -lt $visible.Length -and $visible[$close] -eq ')') {
                $targets.Add(@{ Value = $destination.Value; Line = (Get-LineNumber $visible $position) })
                $position = $close
            }
            continue
        }
        if ($next -lt $visible.Length -and $visible[$next] -eq '[') {
            $referenceEnd = Find-ClosingBracket $visible $next
            if ($referenceEnd -lt 0) { continue }
            $label = $visible.Substring($next + 1, $referenceEnd - $next - 1)
            if ($label.Length -eq 0) { $label = $visible.Substring($position + 1, $end - $position - 1) }
            if (-not $definitions.ContainsKey((Normalize-Reference $label))) {
                $issues.Add("${relative}:$(Get-LineNumber $visible $position) -> reference 정의가 없다: [$label]")
            }
            $position = $referenceEnd
        } else {
            $position = $end
        }
    }
    return ,$targets
}

function Test-Link([string]$value, [string]$file, [int]$line) {
    $relative = Get-RelativePath $file
    $value = [System.Net.WebUtility]::HtmlDecode($value)
    $value = [regex]::Replace($value, '\\([!"#$%&''()*+,\-./:;<=>?@\[\]\\^_`{|}~])', '$1')
    if ($value.Length -eq 0 -or $value.StartsWith('#') -or $value.StartsWith('?')) { return }
    if ($value.StartsWith('//') -or
        ($value -match '^[A-Za-z][A-Za-z0-9+.-]*:' -and $value -notmatch '^[A-Za-z]:[\\/]')) {
        $script:externalLinks++
        return
    }
    $script:localLinks++
    $path = [System.Uri]::UnescapeDataString(($value -split '[#?]', 2)[0])
    $path = $path.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
    try {
        if ($value.StartsWith('/')) {
            $full = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $path.TrimStart([char[]]'\/')))
        } else {
            $full = [System.IO.Path]::GetFullPath($path, [System.IO.Path]::GetDirectoryName($file))
        }
        $target = Get-RelativePath $full
        if ($target -eq '..' -or $target.StartsWith('../') -or [System.IO.Path]::IsPathRooted($target)) {
            $issues.Add("${relative}:$line -> 저장소 밖의 링크: $value")
            return
        }
        [void]$connections[$relative].Add($target)
        if (-not (Test-ExactPath $target) -or
            -not ([System.IO.File]::Exists($full) -or [System.IO.Directory]::Exists($full))) {
            $issues.Add("${relative}:$line -> 대상이 없거나 대소문자가 다르다: $value (해석: $target)")
        }
    } catch {
        $issues.Add("${relative}:$line -> 경로를 해석할 수 없다: $value")
    }
}

# 현재 역할 파일은 최상위 문자열 설정을 사용한다. TOML 문법 전체를 대신 파싱하지 않는다.
function Get-SimpleTomlValue([string]$source, [string]$key) {
    $pattern = '(?m)^[ \t]*' + [regex]::Escape($key) +
               '[ \t]*=[ \t]*(?:"(?<double>[^"\r\n]*)"|''(?<single>[^''\r\n]*)'')[ \t]*(?:#.*)?\r?$'
    $fieldMatches = [regex]::Matches($source, $pattern)
    if ($fieldMatches.Count -ne 1) { return $null }
    if ($fieldMatches[0].Groups['double'].Success) { return $fieldMatches[0].Groups['double'].Value }
    return $fieldMatches[0].Groups['single'].Value
}

foreach ($relative in $requiredFiles) {
    $file = Get-RepoPath $relative
    if (-not (Test-ExactPath $relative) -or -not [System.IO.File]::Exists($file)) {
        $issues.Add("필수 문서가 없다: $relative")
    } elseif ([System.IO.File]::GetAttributes($file) -band [System.IO.FileAttributes]::ReparsePoint) {
        $issues.Add("필수 문서를 symlink/junction으로 대체할 수 없다: $relative")
    } elseif ([System.IO.Path]::GetExtension($file) -eq '.toml') {
        $codexSources[$relative] = [System.IO.File]::ReadAllText($file, [System.Text.Encoding]::UTF8)
    } else { [void]$files.Add($file) }
}
Add-MarkdownFiles (Get-RepoPath 'docs')
Add-MarkdownFiles (Get-RepoPath '아키텍쳐')
$roleDirectory = Get-RepoPath '.claude/agents'
if ((Test-ExactPath '.claude/agents') -and [System.IO.Directory]::Exists($roleDirectory) -and
    -not ([System.IO.File]::GetAttributes($roleDirectory) -band [System.IO.FileAttributes]::ReparsePoint)) {
    foreach ($file in [System.IO.Directory]::EnumerateFiles($roleDirectory, '*.md')) {
        if (-not ([System.IO.File]::GetAttributes($file) -band [System.IO.FileAttributes]::ReparsePoint)) {
            [void]$files.Add($file)
        }
    }
}
foreach ($file in ($files | Sort-Object)) {
    $relative = Get-RelativePath $file
    $connections[$relative] = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $source = [System.IO.File]::ReadAllText($file, [System.Text.Encoding]::UTF8)
    foreach ($link in (Get-MarkdownLinks $source $relative)) { Test-Link $link.Value $file $link.Line }
}

$claudePath = Get-RepoPath 'CLAUDE.md'
if ($files.Contains($claudePath)) {
    $claude = Get-VisibleMarkdown ([System.IO.File]::ReadAllText($claudePath))
    if ($claude -notmatch '(?m)(?<![\w@])@(?:\./)?AGENTS\.md(?![\w./])') {
        $issues.Add('CLAUDE.md: 공통 진입점을 읽는 @AGENTS.md import가 없다.')
    }
}
if ($connections.ContainsKey('AGENTS.md')) {
    foreach ($rule in $sharedRules) {
        if (-not $connections['AGENTS.md'].Contains($rule)) { $issues.Add("AGENTS.md: 공통 규칙 링크가 없다: $rule") }
    }
}
foreach ($skill in $skillFiles) {
    if ($connections.ContainsKey($skill) -and -not $connections[$skill].Contains('docs/ai/workflow.md')) {
        $issues.Add("${skill}: 공통 workflow 링크가 없다.")
    }
}
if ($connections.ContainsKey($skillFiles[0]) -and $connections.ContainsKey($skillFiles[1])) {
    $codexSkill = [System.IO.File]::ReadAllText((Get-RepoPath $skillFiles[0])).Replace("`r`n", "`n")
    $claudeSkill = [System.IO.File]::ReadAllText((Get-RepoPath $skillFiles[1])).Replace("`r`n", "`n")
    if ($codexSkill -cne $claudeSkill) { $issues.Add('Claude와 Codex의 exchange-docs SKILL.md 내용이 다르다.') }
}

foreach ($role in $roles) {
    $roleDocument = "docs/ai/roles/$role.md"
    $claudeFile = ".claude/agents/$role.md"
    $codexFile = ".codex/agents/$role.toml"
    if ($connections.ContainsKey($claudeFile)) {
        foreach ($target in @('AGENTS.md', $roleDocument)) {
            if (-not $connections[$claudeFile].Contains($target)) {
                $issues.Add("${claudeFile}: 공통 진입점 또는 역할 문서 링크가 없다: $target")
            }
        }
        $source = [System.IO.File]::ReadAllText((Get-RepoPath $claudeFile))
        $frontmatter = [regex]::Match($source, '(?s)\A---\r?\n(?<metadata>.*?)\r?\n---(?:\r?\n|$)')
        $metadata = $frontmatter.Groups['metadata'].Value
        $names = [regex]::Matches($metadata, '(?m)^name:[ \t]*([^\r\n]+)\r?$')
        if ($names.Count -ne 1 -or $names[0].Groups[1].Value.Trim() -cne $role) {
            $issues.Add("${claudeFile}: frontmatter의 name이 역할 이름과 다르다.")
        }
        if ($role -eq 'reviewer') {
            $toolLines = [regex]::Matches($metadata, '(?m)^tools:[ \t]*([^\r\n]+)\r?$')
            $tools = @()
            if ($toolLines.Count -eq 1) { $tools = @($toolLines[0].Groups[1].Value.Split(',').Trim()) }
            if ($tools.Count -ne 3 -or
                @($tools | Where-Object { $_ -cnotin @('Read', 'Grep', 'Glob') }).Count -gt 0 -or
                @($tools | Select-Object -Unique).Count -ne 3) {
                $issues.Add("${claudeFile}: reviewer tools는 Read, Grep, Glob만 허용한다.")
            }
        }
    }
    if ($codexSources.ContainsKey($codexFile)) {
        $source = $codexSources[$codexFile]
        $instructionsPattern = '(?ms)^developer_instructions[ \t]*=[ \t]*"""(?<instructions>.*?)"""'
        $instructionBlocks = [regex]::Matches($source, $instructionsPattern)
        $instructions = ''
        if ($instructionBlocks.Count -eq 1) { $instructions = $instructionBlocks[0].Groups['instructions'].Value }
        foreach ($target in @('AGENTS.md', $roleDocument)) {
            $referencePattern = '(?<![A-Za-z0-9_./-])' + [regex]::Escape($target) + '(?![A-Za-z0-9_./-])'
            if ($instructions -cnotmatch $referencePattern) {
                $issues.Add("${codexFile}: developer_instructions에 공통 문서 참조가 없다: $target")
            }
        }
        $settings = [regex]::Replace($source, $instructionsPattern, '')
        $settings = ($settings -split '(?m)^[ \t]*\[', 2)[0]
        if ((Get-SimpleTomlValue $settings 'name') -cne $role) {
            $issues.Add("${codexFile}: name이 역할 이름과 다르다.")
        }
        if ($role -eq 'reviewer' -and (Get-SimpleTomlValue $settings 'sandbox_mode') -cne 'read-only') {
            $issues.Add("${codexFile}: reviewer sandbox_mode는 read-only여야 한다.")
        }
    }
}

foreach ($issue in $issues) { Write-Host $issue }
Write-Host "Markdown $($files.Count)개, 로컬 링크 ${localLinks}개 검사. 외부 링크 ${externalLinks}개 제외."
Write-Host '범위 밖: HTML 링크, 앵커 내용, 코드 예시, 일반 텍스트 경로, 외부 URL의 응답, 보고서 문장 품질.'
Write-Host '역할 설정은 공통 문서 연결과 reviewer의 제한만 검사한다. TOML 전체 문법과 실행 시 권한은 별도 확인한다.'
if ($issues.Count -gt 0) {
    Write-Host "문서 구조 검사 실패: $($issues.Count)건."
    exit 1
}
Write-Host '문서 구조 검사 통과.'
exit 0
