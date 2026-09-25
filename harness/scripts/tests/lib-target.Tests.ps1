# 하네스 공용 스크립트의 대상 스택 기본값을 고정한다(Patroni 설계 8-1·10절, 검토 R7). 값을 주지 않으면 카오스 스택(chaos-*)
# 그대로여야 카오스 실험과 S5·S6 관문 스크립트의 동작이 바뀌지 않는다. Docker 에 명령을 보내지 않는다.
# 사용: pwsh -NoProfile -File harness/scripts/tests/lib-target.Tests.ps1

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$scripts = Split-Path -Parent $PSScriptRoot
$lib = Join-Path $scripts 'lib.ps1'
$loadAcks = Join-Path $scripts 'load-acks.ps1'
$passed = 0
$targetVars = @('CHAOS_PSQL_CONTAINER', 'CHAOS_API1_PORT', 'CHAOS_API2_PORT', 'CHAOS_LB_PORT', 'CHAOS_API_CONTAINERS',
    'CHAOS_BOTS_CONTAINER', 'CHAOS_ENV_FILE', 'CHAOS_COMPOSE_FILE', 'CHAOS_PROJECT', 'CHAOS_PSQL_TIMEOUT_SEC')

# 새 pwsh 에서 환경 변수를 정한 뒤 lib.ps1 을 읽고 대상 설정을 JSON 으로 받는다. 이 프로세스의 환경은 건드리지 않는다.
function Read-Target([hashtable] $EnvValues) {
    $lines = @()
    foreach ($name in $targetVars) { $lines += "Remove-Item Env:$name -ErrorAction SilentlyContinue" }
    foreach ($key in $EnvValues.Keys) { $lines += ('$env:{0} = ''{1}''' -f $key, $EnvValues[$key]) }
    $lines += ". '$lib'"
    $lines += '$c = $Global:CHAOS'
    $lines += '[pscustomobject]@{ Pg = $c.Pg; Api1Port = $c.Api1Port; Api2Port = $c.Api2Port; LbPort = $c.LbPort; ' +
        'ApiContainers = @($c.ApiContainers); BotsContainer = $c.BotsContainer; EnvFile = $c.EnvFile; ' +
        'ComposeFile = $c.ComposeFile; Project = $c.Project } | ConvertTo-Json -Compress'
    $script = $lines -join "`n"
    $output = & pwsh -NoProfile -Command $script 2>&1
    if ($LASTEXITCODE -ne 0) { throw "lib.ps1 읽기 실패: $($output -join "`n")" }
    return ($output | Select-Object -Last 1) | ConvertFrom-Json
}

function Assert-Equal($expected, $actual, [string] $what) {
    if ("$expected" -ne "$actual") { throw "${what}: '$expected' 를 기대했으나 '$actual'" }
}

function Pass([string] $name) {
    $script:passed++
    Write-Host "PASS $name"
}

# 1) 기본값은 카오스 스택이다.
$d = Read-Target @{}
Assert-Equal 'chaos-pg' $d.Pg 'psql 컨테이너 기본값'
Assert-Equal 18080 $d.Api1Port 'api-1 포트 기본값'
Assert-Equal 28080 $d.Api2Port 'api-2 포트 기본값'
Assert-Equal 58080 $d.LbPort 'LB 포트 기본값'
Assert-Equal 'chaos-api-1,chaos-api-2' ($d.ApiContainers -join ',') '리더 후보 기본값'
Assert-Equal 'chaos-bots' $d.BotsContainer '봇 컨테이너 기본값'
Assert-Equal 'exchange-chaos' $d.Project '프로젝트 기본값'
if (-not $d.EnvFile.EndsWith('.env.chaos')) { throw "env 파일 기본값: $($d.EnvFile)" }
if (-not $d.ComposeFile.EndsWith('docker-compose.chaos.yml')) { throw "compose 파일 기본값: $($d.ComposeFile)" }
Pass 'defaults-are-chaos-stack'

# 2) Patroni 스택 값으로 바꾼다.
$p = Read-Target @{
    CHAOS_PSQL_CONTAINER = 'patroni-toolbox'; CHAOS_API1_PORT = '59181'; CHAOS_API2_PORT = '59182'; CHAOS_LB_PORT = '59180'
    CHAOS_API_CONTAINERS = 'patroni-api-1, patroni-api-2'; CHAOS_BOTS_CONTAINER = 'patroni-bots'
}
Assert-Equal 'patroni-toolbox' $p.Pg 'psql 컨테이너'
Assert-Equal 59181 $p.Api1Port 'api-1 포트'
Assert-Equal 59182 $p.Api2Port 'api-2 포트'
Assert-Equal 59180 $p.LbPort 'LB 포트'
Assert-Equal 'patroni-api-1,patroni-api-2' ($p.ApiContainers -join ',') '리더 후보(공백 제거)'
Assert-Equal 'patroni-bots' $p.BotsContainer '봇 컨테이너'
Pass 'patroni-target-overrides'

# 3) cmd 문자열에 들어가는 이름은 형식을 막는다.
foreach ($bad in @(@{ CHAOS_PSQL_CONTAINER = 'chaos-pg & del x' }, @{ CHAOS_BOTS_CONTAINER = 'bots x' },
        @{ CHAOS_API_CONTAINERS = 'chaos-api-1,a|b' }, @{ CHAOS_API1_PORT = 'abc' })) {
    $failed = $false
    try { Read-Target $bad | Out-Null } catch { $failed = $true }
    if (-not $failed) { throw "형식 위반을 받아들였다: $($bad | ConvertTo-Json -Compress)" }
}
Pass 'rejects-unsafe-names'

# 4) Get-LeaderContainer 의 기본 후보는 대상 스택의 API 컨테이너다(함수 본문이 $Global:CHAOS.ApiContainers 를 쓴다).
$ast = [System.Management.Automation.Language.Parser]::ParseFile($lib, [ref]$null, [ref]$null)
$fn = $ast.Find({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Get-LeaderContainer' }, $true)
if (-not $fn) { throw 'Get-LeaderContainer 없음' }
$candidates = $fn.Body.ParamBlock.Parameters | Where-Object { $_.Name.VariablePath.UserPath -eq 'Candidates' }
if ($candidates.DefaultValue) { throw "Candidates 기본값을 문자열로 고정하지 않는다: $($candidates.DefaultValue.Extent.Text)" }
if (-not $fn.Body.Extent.Text.Contains('$Global:CHAOS.ApiContainers')) { throw '기본 후보가 대상 설정을 쓰지 않는다' }
Pass 'leader-candidates-follow-target'

# 5) load-acks.ps1 의 -Pg 기본값은 CHAOS_PSQL_CONTAINER, 없으면 chaos-pg 다.
$ast2 = [System.Management.Automation.Language.Parser]::ParseFile($loadAcks, [ref]$null, [ref]$null)
$pg = $ast2.ParamBlock.Parameters | Where-Object { $_.Name.VariablePath.UserPath -eq 'Pg' }
$text = $pg.DefaultValue.Extent.Text
if (-not ($text.Contains('CHAOS_PSQL_CONTAINER') -and $text.Contains("'chaos-pg'"))) { throw "load-acks -Pg 기본값: $text" }
$childDefault = & pwsh -NoProfile -Command "Remove-Item Env:CHAOS_PSQL_CONTAINER -ErrorAction SilentlyContinue; $text"
Assert-Equal 'chaos-pg' $childDefault 'load-acks -Pg 기본값(환경 변수 없음)'
$childPatroni = & pwsh -NoProfile -Command "`$env:CHAOS_PSQL_CONTAINER = 'patroni-toolbox'; $text"
Assert-Equal 'patroni-toolbox' $childPatroni 'load-acks -Pg(환경 변수)'
Pass 'load-acks-target'

# 6) Invoke-Psql 은 CHAOS_PSQL_TIMEOUT_SEC 가 없으면 종전 명령(시간 제한 없음) 그대로다.
$fnPsql = $ast.Find({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-Psql' }, $true)
$body = $fnPsql.Body.Extent.Text
if (-not ($body.Contains("GetEnvironmentVariable('CHAOS_PSQL_TIMEOUT_SEC')") -and
        $body.Contains('& docker exec $Global:CHAOS.Pg psql -U $Global:CHAOS.PgUser -d $Global:CHAOS.PgDb -q -t -A -c $Sql'))) {
    throw 'Invoke-Psql 의 기본 경로가 종전 명령과 다르다'
}
Pass 'psql-timeout-is-opt-in'

Write-Host "lib-target: $passed 통과"
