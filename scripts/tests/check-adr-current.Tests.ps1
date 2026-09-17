# 실제 임시 파일로 해시와 ADR 연결 검사의 회귀를 확인한다. 외부 모듈은 필요 없다.
# 사용: pwsh -NoProfile -File scripts/tests/check-adr-current.Tests.ps1

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$gate = Join-Path (Split-Path -Parent $PSScriptRoot) 'check-adr-current.ps1'
$tempRoot = (Resolve-Path -LiteralPath ([System.IO.Path]::GetTempPath())).ProviderPath
$fixtureName = 'exchange-adr-test-' + [guid]::NewGuid().ToString('N')
$fixtureRoot = Join-Path $tempRoot $fixtureName
[void][System.IO.Directory]::CreateDirectory($fixtureRoot)
$adrDirectory = Join-Path (Join-Path 'docs' 'reference') 'adr'
$masterRelative = Join-Path $adrDirectory '0099-current-architecture.md'
$firstRelative = Join-Path $adrDirectory '0001-first.md'
$secondRelative = Join-Path $adrDirectory '0002-second.md'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$passed = 0

function Write-Fixture([string]$relative, [string]$content) {
    $path = Join-Path $fixtureRoot $relative
    [void][System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($path))
    [System.IO.File]::WriteAllText($path, $content, $utf8)
}

function Get-FixtureDigest {
    $output = (& pwsh -NoProfile -File $gate -Root $fixtureRoot -PrintDigest 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $output -cnotmatch '^[0-9a-f]{64}$') {
        throw "해시만 출력해야 한다: $output"
    }
    return $output
}

function Assert-Gate([int]$expectedCode, [string]$message) {
    $output = (& pwsh -NoProfile -File $gate -Root $fixtureRoot 2>&1) -join "`n"
    if ($LASTEXITCODE -ne $expectedCode -or -not $output.Contains($message)) {
        throw "종료 코드 $expectedCode 및 '$message'을 기대했으나 $LASTEXITCODE 반환.`n$output"
    }
}

function Write-Master([string]$body, [string]$digest) {
    Write-Fixture $masterRelative ("# Current architecture`n`n$body`n`n<!-- sources-sha256: $digest -->`n")
}

function Pass([string]$name) {
    $script:passed++
    Write-Host "PASS $name"
}

try {
    Write-Fixture $firstRelative "# First decision`n한글 본문`n"
    $digest = Get-FixtureDigest
    Assert-Gate 1 '0099 종합본은'
    Write-Master '[first](0001-first.md)' $digest
    Assert-Gate 0 'ADR 종합본 검사 통과'
    if ((Get-FixtureDigest) -cne $digest) { throw '종합본 자체가 원본 해시에 포함됐다.' }
    Pass '종합본 없는 해시 출력, 생성 후 통과, 종합본은 해시에서 제외'

    Write-Master @'
<!-- [hidden](0001-first.md) -->
```md
[example](0001-first.md)
```
`[inline](0001-first.md)`
0001-first.md
'@ $digest
    Assert-Gate 1 'ADR 본문 링크가 없다'
    Write-Master '[first](<./0001-first.md#heading> "title")' $digest
    Assert-Gate 0 'ADR 종합본 검사 통과'
    Pass '코드와 주석은 연결로 세지 않고 실제 인라인 링크만 허용'

    Write-Fixture $secondRelative '# Second decision'
    Assert-Gate 1 '원본 자료가 바뀌었다'
    $digest = Get-FixtureDigest
    Write-Master '[first](0001-first.md)' $digest
    Assert-Gate 1 '0002-second.md'
    $links = "[first](0001-first.md)`n[second](0002-second.md)"
    Write-Master $links $digest
    Assert-Gate 0 'ADR 종합본 검사 통과'
    Pass '새 ADR은 해시 갱신과 종합본 링크를 모두 요구'

    $architectureReadme = Join-Path '아키텍쳐' 'README.md'
    Write-Fixture $architectureReadme '# Current presentation structure'
    $architectureDigest = Get-FixtureDigest
    Write-Master $links $architectureDigest
    Assert-Gate 0 'ADR 종합본 검사 통과'
    Write-Fixture $architectureReadme '# Updated presentation structure'
    if ((Get-FixtureDigest) -ceq $architectureDigest) { throw '아키텍쳐 안내의 변경을 놓쳤다.' }
    Assert-Gate 1 '원본 자료가 바뀌었다'
    Pass '아키텍쳐 안내 변경을 해시와 종합본 검사에서 감지'

    $sourcePaths = @($firstRelative, (Join-Path 'docs' 'ROADMAP.md'),
                     (Join-Path 'deploy' 'README.md'), $architectureReadme)
    foreach ($folder in @('architecture', 'contracts', 'product', 'operations')) {
        $sourcePaths += Join-Path (Join-Path (Join-Path 'docs' 'reference') $folder) 'sample.md'
    }
    $sourcePaths += Join-Path (Join-Path 'docs' 'measurements') 'sample.md'
    $sourcePaths += Join-Path 'deploy' 'compose.node-app.yml'
    foreach ($source in $sourcePaths) {
        Write-Fixture $source "first`nsecond`n"
        $before = Get-FixtureDigest
        Write-Fixture $source "first`nchanged`n"
        if ((Get-FixtureDigest) -ceq $before) { throw "본문 변경을 놓쳤다: $source" }
    }
    Assert-Gate 1 '원본 자료가 바뀌었다'
    Pass '모든 지정 자료 종류의 본문 변경 감지'

    $before = Get-FixtureDigest
    foreach ($source in $sourcePaths) {
        $path = Join-Path $fixtureRoot $source
        $content = [System.IO.File]::ReadAllText($path, $utf8).Replace("`n", "`r`n")
        [System.IO.File]::WriteAllText($path, $content, [System.Text.UTF8Encoding]::new($true))
    }
    if ((Get-FixtureDigest) -cne $before) { throw 'CRLF 또는 BOM 차이가 해시를 바꿨다.' }
    Pass 'LF와 CRLF, UTF-8 BOM 유무가 같은 해시를 생성'

    $digest = Get-FixtureDigest
    foreach ($invalid in @('', ('A' * 64), ('a' * 63))) {
        Write-Master $links $invalid
        Assert-Gate 1 '소문자 64자리 해시'
    }
    Write-Master ($links + "`n<!-- sources-sha256: $digest -->") $digest
    Assert-Gate 1 '정확히 한 번'
    Write-Master $links $digest
    Assert-Gate 0 'ADR 종합본 검사 통과'
    Pass '잘못된 해시 형식과 중복 검토 주석 거부'

    $duplicate = Join-Path $adrDirectory '0001-duplicate.md'
    Write-Fixture $duplicate '# Duplicate number'
    Assert-Gate 1 'ADR 번호 중복'
    Remove-Item -LiteralPath (Join-Path $fixtureRoot $duplicate)
    $secondMaster = Join-Path $adrDirectory '0099-other.md'
    Write-Fixture $secondMaster '# Duplicate master'
    Assert-Gate 1 '0099 종합본은'
    Remove-Item -LiteralPath (Join-Path $fixtureRoot $secondMaster)
    Pass '결정 ADR 번호 중복과 0099 복수 파일 거부'

    $renamed = Join-Path $adrDirectory '0002-renamed.md'
    $secondPath = Join-Path $fixtureRoot $secondRelative
    Write-Fixture $renamed ([System.IO.File]::ReadAllText($secondPath, $utf8))
    Remove-Item -LiteralPath $secondPath
    if ((Get-FixtureDigest) -ceq $digest) { throw '동일 본문의 파일명 변경을 놓쳤다.' }
    Assert-Gate 1 '0002-renamed.md'
    Write-Master $links.Replace('0002-second.md', '0002-renamed.md') (Get-FixtureDigest)
    Assert-Gate 0 'ADR 종합본 검사 통과'
    Pass '파일명 변경도 해시와 링크에 반영'
    Write-Host "회귀 검사 $passed 건 통과."
} finally {
    # 이 테스트가 만든 고유 temp 디렉터리인지 확인한 뒤 그 안만 정리한다.
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
