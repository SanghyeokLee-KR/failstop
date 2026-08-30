# 역할 체계 산출 실측. docs/records/역할체계_운영기록.md 의 수치를 재생성한다.
#
# 사용:
#   pwsh -NoProfile -File scripts/measure-role-system.ps1
#   pwsh -NoProfile -File scripts/measure-role-system.ps1 -Base 581d5f3
#
# 커밋 축으로 읽는다. git ls-files 와 Get-Content 는 작업 트리를 보므로 커밋되지 않은
# 편집이 남아 있으면 값이 흔들린다. 실제로 harness 행 수가 한 세션 안에서 6953, 6954,
# 6955 로 움직였다. git ls-tree 와 git show 로 그 의존을 없앴다.
param([string]$Base = "HEAD")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root

# core.quotepath=false 가 없으면 git 이 한글 경로를 따옴표로 감싸 docs 가 "docs 와 docs 로 갈린다
$all = git -c core.quotepath=false ls-tree -r --name-only $Base

function Pick($pat) { $all | Where-Object { $_ -like $pat } }

# 빈 줄을 포함한 총 행. Measure-Object -Line 은 빈 줄을 세지 않아 기준이 달라진다
function Lines($fs) { $n = 0; foreach ($p in $fs) { $n += @(git show "${Base}:$p").Count }; return $n }

function Report($label, $pats) {
    $f = @(); foreach ($p in $pats) { $f += Pick $p }
    '{0,-28} {1,5} 파일 {2,8} 행' -f $label, $f.Count, (Lines $f)
}

'기준 커밋 {0} ({1})' -f (git rev-parse --short $Base), (git log -1 --format='%ad' --date=short $Base)
'추적 파일 {0}' -f $all.Count
''
'== 트리별 산출 =='
Report 'server (java)'   @('server/*.java')
Report 'web (tsx)'       @('web/*.tsx')
Report 'web (ts)'        @('web/*.ts')
Report 'harness'         @('harness/*')
Report 'scripts'         @('scripts/*')
Report '.github'         @('.github/*')
Report 'deploy'          @('deploy/*')
''
'== 세부 분해 (파일 수) =='
'  server main java      {0}' -f @(Pick 'server/*/src/main/java/*.java').Count
'  server test java      {0}' -f @(Pick 'server/*/src/test/java/*.java').Count
'  Flyway 마이그레이션   {0}' -f @(Pick 'server/*/src/main/resources/db/migration/*.sql').Count
'  web page.tsx          {0}' -f @(Pick 'web/app/*page.tsx').Count
'  web components        {0}' -f @(Pick 'web/components/*.tsx').Count
'  web css               {0}' -f @(Pick 'web/*.css').Count
''
'== 커밋 축 =='
# rev-list --count 를 쓴다. log 를 Measure-Object -Line 으로 세면 제목이 빈 커밋이 누락된다
foreach ($t in @('', 'web', 'server', 'docs', 'harness', 'scripts', 'deploy')) {
    $n = if ($t) { git rev-list --count $Base -- $t } else { git rev-list --count $Base }
    '  {0,-10} {1,5}' -f ($(if ($t) { $t } else { '(전체)' })), $n
}
''
# 커밋별 최상위 디렉터리 집합 크기가 1이면 단독 트리 커밋이다. 역할 경계가 커밋 단위로
# 지켜졌는지를 재는 축이다
'== 단독 트리 커밋 =='
$raw = git -c core.quotepath=false log --format="C:%H" --name-only $Base
$cur = $null; $map = @{}
foreach ($l in $raw) {
    if ($l -like 'C:*') { $cur = $l.Substring(2); $map[$cur] = New-Object System.Collections.Generic.HashSet[string] }
    elseif ($l.Trim() -ne '' -and $cur) { [void]$map[$cur].Add((($l -split '/')[0])) }
}
$single = @{}; $singleTotal = 0; $multi = 0; $empty = 0
foreach ($k in $map.Keys) {
    if ($map[$k].Count -eq 1) { $t = @($map[$k])[0]; $single[$t] = 1 + $single[$t]; $singleTotal++ }
    elseif ($map[$k].Count -eq 0) { $empty++ } else { $multi++ }
}
'  전체 {0} / 단독 {1} / 복수 {2} / 빈 커밋 {3}' -f $map.Count, $singleTotal, $multi, $empty
$single.GetEnumerator() | Sort-Object @{E='Value';Descending=$true}, Name | ForEach-Object {
    '    {0,-20} {1,4}' -f $_.Key, $_.Value
}
''
'== 권한 분리 =='
# tools 행을 가진 정의가 reviewer 하나뿐이어야 한다. 나머지 셋은 전체 도구를 상속한다
$tools = git grep -n "^tools:" -- '.claude/agents/*.md'
'  tools 선언: {0}' -f ($(if ($tools) { $tools -join '; ' } else { '없음' }))
$mirror = git ls-files | Select-String 'agents/' | Where-Object { $_ -notmatch '^\.claude/agents/' }
'  정의 미러: {0}' -f ($(if ($mirror) { $mirror -join '; ' } else { '없음. 정본 하나' }))

Pop-Location
