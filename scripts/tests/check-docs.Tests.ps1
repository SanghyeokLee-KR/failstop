# 별도 의존성 없이 실제 임시 파일을 만들고 문서 게이트의 종료 코드와 진단을 검사한다.
# 사용: pwsh -NoProfile -File scripts/tests/check-docs.Tests.ps1

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$gate = Join-Path (Split-Path -Parent $PSScriptRoot) 'check-docs.ps1'
$tempRoot = (Resolve-Path -LiteralPath ([System.IO.Path]::GetTempPath())).ProviderPath
$fixtureName = 'exchange-docs-test-' + [guid]::NewGuid().ToString('N')
$fixtureRoot = Join-Path $tempRoot $fixtureName
[void][System.IO.Directory]::CreateDirectory($fixtureRoot)
$passed = 0

function Write-Fixture([string]$relative, [string]$value) {
    $path = Join-Path $fixtureRoot $relative
    [void][System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($path))
    [System.IO.File]::WriteAllText($path, $value, [System.Text.UTF8Encoding]::new($false))
}

function Assert-Gate([string]$name, [int]$expectedCode, [string]$diagnostic) {
    $output = (& pwsh -NoProfile -File $gate -Root $fixtureRoot 2>&1) -join "`n"
    if ($LASTEXITCODE -ne $expectedCode -or -not $output.Contains($diagnostic)) {
        throw "${name}: 종료 코드 $expectedCode 및 '$diagnostic'을 기대했으나 $LASTEXITCODE 반환.`n$output"
    }
    $script:passed++
    Write-Host "PASS $name"
}

$readme = @'
[bare](docs/reports/overview.md)
[space](<docs/reports/a b.pdf> "title")
[encoded space](docs/reports/a%20b.pdf)
[parentheses](docs/reports/schema(v2).md)
[escaped parentheses](docs/reports/schema\(v2\).md)
[encoded hash](docs/reports/notes%23one.md)
[folder](docs/reports/)
[root](/)
[root relative](/docs/README.md)
[reference][guide]
[guide][]
[guide]
[guide]: docs/reports/overview.md 'title'
[external](https://example.com/missing)
[email](mailto:someone@example.com)
[anchor](#absent-anchor)
[query](?view=1)
[empty]()
[query and anchor](docs/reports/overview.md?x=1#heading)
[nested [label]](docs/reports/overview.md)
<!-- [comment](absent.md) -->
'@
$agents = @'
[workflow](docs/ai/workflow.md)
[engineering](docs/ai/engineering.md)
[code style](docs/ai/code-style.md)
[writing](docs/ai/writing.md)
'@
$skill = '[workflow](../../../docs/ai/workflow.md)'
$claudeRole = @'
---
name: {0}
{1}
---
[entry](../../AGENTS.md)
[role](../../docs/ai/roles/{0}.md)
'@
$codexRole = @'
name = "{0}"
{1}
developer_instructions = """
AGENTS.md와 docs/ai/roles/{0}.md를 먼저 읽는다.
"""
'@
$claudeReviewer = $claudeRole -f 'reviewer', 'tools: Read, Grep, Glob'
$codexReviewer = $codexRole -f 'reviewer', 'sandbox_mode = "read-only"'

try {
    Write-Fixture 'README.md' $readme
    Write-Fixture 'AGENTS.md' $agents
    Write-Fixture 'CLAUDE.md' '@AGENTS.md'
    Write-Fixture 'docs/README.md' '[overview](reports/overview.md)'
    Write-Fixture 'docs/ROADMAP.md' '# Current status'
    Write-Fixture (Join-Path '아키텍쳐' 'README.md') '[overview](../docs/reports/overview.md)'
    Write-Fixture (Join-Path (Join-Path '아키텍쳐' '영상') 'README.md') '[overview](../../docs/reports/overview.md)'
    Write-Fixture (Join-Path (Join-Path '아키텍쳐' '캡처') 'README.md') '[overview](../../docs/reports/overview.md)'
    Write-Fixture (Join-Path (Join-Path '아키텍쳐' '교수님_보고본') 'original.png') '[unparsed](absent.md)'
    foreach ($rule in @('workflow', 'engineering', 'code-style', 'writing')) {
        Write-Fixture "docs/ai/$rule.md" '# Rule'
    }
    Write-Fixture '.agents/skills/exchange-docs/SKILL.md' $skill
    Write-Fixture '.claude/skills/exchange-docs/SKILL.md' $skill
    foreach ($role in @('backend-engineer', 'frontend-engineer', 'harness-engineer', 'reviewer')) {
        Write-Fixture "docs/ai/roles/$role.md" '# Shared role'
        Write-Fixture ".claude/agents/$role.md" ($claudeRole -f $role, '')
        Write-Fixture ".codex/agents/$role.toml" ($codexRole -f $role, '')
    }
    Write-Fixture '.claude/agents/reviewer.md' $claudeReviewer
    Write-Fixture '.codex/agents/reviewer.toml' $codexReviewer
    Write-Fixture 'docs/reports/overview.md' @'
# Overview
[relative](../README.md)
```md
[example](missing.md)
```
~~~md
[example](missing-again.md)
~~~
`[inline](absent.md)` and ``[inline](absent.md)``
    [indented](absent.md)
'@
    foreach ($file in @('a b.pdf', 'schema(v2).md', 'notes#one.md')) {
        Write-Fixture "docs/reports/$file" 'fixture'
    }
    foreach ($ignored in @('docs/evidence/original.md', 'docs/raw/original.md', 'docs/build/generated.md',
                           '.claude/worktrees/other/docs/README.md', 'harness/evidence/raw.md')) {
        Write-Fixture $ignored '[ignored](does-not-exist.md)'
    }
    Assert-Gate '정상 링크와 연결, 코드 예시와 제외 폴더' 0 '문서 구조 검사 통과'

    foreach ($folder in @('아키텍쳐', '아키텍쳐/영상', '아키텍쳐/캡처')) {
        $document = Join-Path $folder 'README.md'
        $original = [System.IO.File]::ReadAllText((Join-Path $fixtureRoot $document))
        Write-Fixture $document '[broken](absent.md)'
        Assert-Gate "$folder 문서의 깨진 링크" 1 ($folder + '/README.md:1')
        Write-Fixture $document $original
    }

    # fixture에는 Git 저장소 자체가 없다. 추적 여부에 기대지 않고 새 파일도 읽어야 한다.
    Write-Fixture 'docs/new-untracked.md' '[broken](missing.md)'
    Assert-Gate '신규 untracked 문서' 1 'docs/new-untracked.md:1'
    Remove-Item -LiteralPath (Join-Path $fixtureRoot 'docs/new-untracked.md')
    foreach ($case in @(
        @('깨진 로컬 링크', '[broken](docs/reports/missing.md)', 'missing.md'),
        @('잘못된 대소문자', '[case](docs/reports/Overview.md)', 'Overview.md'),
        @('정의 없는 reference 링크', '[undefined][missing-ref]', 'reference 정의가 없다'),
        @('저장소 밖의 경로', '[outside](../outside.md)', '저장소 밖의 링크')
    )) {
        Write-Fixture 'README.md' ($readme + "`n" + $case[1])
        Assert-Gate $case[0] 1 $case[2]
    }
    Write-Fixture 'README.md' $readme

    Write-Fixture '.claude/skills/exchange-docs/SKILL.md' ($skill + "`nDivergent rule")
    Assert-Gate '두 도구의 skill 불일치' 1 '내용이 다르다'
    Write-Fixture '.claude/skills/exchange-docs/SKILL.md' $skill
    Write-Fixture 'CLAUDE.md' '`@AGENTS.md`'
    Assert-Gate '코드 예시에만 있는 진입점' 1 'import가 없다'
    Write-Fixture 'CLAUDE.md' '@AGENTS.md'
    Write-Fixture 'AGENTS.md' '[workflow](docs/ai/workflow.md)'
    Assert-Gate '공통 규칙 연결 누락' 1 '공통 규칙 링크가 없다'
    Write-Fixture 'AGENTS.md' $agents
    Remove-Item -LiteralPath (Join-Path $fixtureRoot 'docs/ai/writing.md')
    Assert-Gate '공통 규칙 파일 누락' 1 '필수 문서가 없다: docs/ai/writing.md'
    Write-Fixture 'docs/ai/writing.md' '# Rule'

    Write-Fixture '.claude/agents/reviewer.md' $claudeReviewer.Replace('Read, Grep, Glob', 'Read, Grep, Bash')
    Assert-Gate 'Claude reviewer의 실행 도구 추가' 1 'Read, Grep, Glob만 허용'
    Write-Fixture '.claude/agents/reviewer.md' $claudeReviewer.Replace('../../docs/ai/roles/reviewer.md', '../../AGENTS.md')
    Assert-Gate 'Claude 공통 역할 연결 누락' 1 '공통 진입점 또는 역할 문서 링크가 없다'
    Write-Fixture '.claude/agents/reviewer.md' $claudeReviewer
    Write-Fixture '.codex/agents/reviewer.toml' $codexReviewer.Replace('read-only', 'workspace-write')
    Assert-Gate 'Codex reviewer의 쓰기 허용' 1 'sandbox_mode는 read-only'
    Write-Fixture '.codex/agents/reviewer.toml' $codexReviewer.Replace('docs/ai/roles/reviewer.md', 'docs/ai/workflow.md')
    Assert-Gate 'Codex 공통 역할 연결 누락' 1 'developer_instructions에 공통 문서 참조가 없다'
    Write-Fixture '.codex/agents/reviewer.toml' $codexReviewer
    Remove-Item -LiteralPath (Join-Path $fixtureRoot 'docs/ai/roles/harness-engineer.md')
    Assert-Gate '공통 역할 파일 누락' 1 '필수 문서가 없다: docs/ai/roles/harness-engineer.md'
    Write-Fixture 'docs/ai/roles/harness-engineer.md' '# Shared role'
    Assert-Gate '복구한 fixture' 0 '문서 구조 검사 통과'
    Write-Host "회귀 검사 $passed 건 통과."
} finally {
    # 생성한 고유 temp 폴더만 지운다. 실패한 테스트도 같은 검사를 거쳐 정리한다.
    $resolvedFixture = (Resolve-Path -LiteralPath $fixtureRoot).ProviderPath
    $expectedFixture = [System.IO.Path]::GetFullPath((Join-Path $tempRoot $fixtureName))
    if ($resolvedFixture -cne $expectedFixture -or
        [System.IO.Path]::GetFileName($resolvedFixture) -cne $fixtureName -or
        [System.IO.Path]::GetFullPath([System.IO.Path]::GetDirectoryName($resolvedFixture)) -cne
        [System.IO.Path]::TrimEndingDirectorySeparator([System.IO.Path]::GetFullPath($tempRoot))) {
        throw "생성한 임시 폴더와 일치하지 않아 삭제하지 않았다: $resolvedFixture"
    }
    Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
}
