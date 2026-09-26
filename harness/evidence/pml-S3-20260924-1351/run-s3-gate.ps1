# S3 관문 폐기용 스택 주행(마켓분할_리더_S3_설계 7절). compose 프로젝트 exchange-pml, 앱 이미지 exchange-chaos-app:pml 에서만 돈다.
#   Prep  : 시작 전 기록. 이미지 id·작업 트리 커밋과 변경 해시, 보존 볼륨 생성 시각, chaos-* 컨테이너 부재
#   X1    : 소유자 로그인으로 V1~V51 적용 -> 런타임 로그인(app_role 소속) -> V49 권한 상태 -> P2 전환 -> 봇 300 OPS 10분 -> 공통 판정
#   Chaos : harness/scripts/adr0022-stage5-chaos.ps1 -Protocol 2 를 prod-timeouts 프로필로 부른다(X2 E4, X3 E7, X4 E2·E5·E6)
#   X5    : harness/scale/roundtrip_probe.py 독립 칸. -Measure 600 은 12마켓 600/s, hot 은 KRW-BTC 과포화(기본 300/s)
#   X7    : 폐기용 postgres 컨테이너의 감사 문장 누적 행 비용(스택 무관, X7/s3-scale-probe-12m.sh)
#   Final : 스택을 -p exchange-pml 로 지우고 보존 볼륨을 다시 확인한다
# 사용: pwsh -NoProfile -File run-s3-gate.ps1 -Phase <단계> [옵션]
# 보존 스택과 보존 볼륨을 건드리지 않도록 프로젝트를 네 곳(셸 변수 둘, lib.ps1, compose 해석값)에서 확인한 뒤 시작하고, 단계마다
# 앞뒤로 보존 볼륨 생성 시각을 volumes.txt 에 남겨 바뀌면 멈춘다. 해석된 compose 설정 전체는 비밀 값을 담으므로 남기지 않는다.
# 비밀 파일(harness/.env.chaos, harness/load/secrets/k6_keys.json)은 경로로만 넘긴다. 런타임 로그인 비밀번호는 실행마다 무작위로
# 만들어 셸 변수로만 넘기고 어디에도 적지 않는다.
param(
    [Parameter(Mandatory = $true)][ValidateSet('Prep', 'X1', 'Chaos', 'X5', 'X7', 'Final')][string] $Phase,
    [string] $Image = 'exchange-chaos-app:pml',
    [string] $Experiments = 'E4',
    [string] $SubDir = '',
    [string] $Cells = 'P1a,P2a,P1b,P2b',
    [ValidateSet('600', 'hot')][string] $Measure = '600',
    [int] $Rate = 0,
    [int] $X1Minutes = 10,
    [int] $X1BotOps = 300,
    [long] $Rows = 1000000
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONIOENCODING = 'utf-8'
if ($Image -ne 'exchange-chaos-app:pml') { throw "이미지 태그는 exchange-chaos-app:pml 만 쓴다: $Image" }
$env:CHAOS_PROJECT = 'exchange-pml'
$env:COMPOSE_PROJECT_NAME = 'exchange-pml'
$env:CHAOS_APP_IMAGE = $Image
$here = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $here '..\..\..')).Path
$env:CHAOS_EVIDENCE_DIR = Join-Path $here 'chaos-runs'
. (Join-Path $repo 'harness\scripts\lib.ps1')
if ($Global:CHAOS.Project -ne 'exchange-pml') { throw "프로젝트 고정 실패: $($Global:CHAOS.Project)" }
if ($env:CHAOS_PROJECT -ne 'exchange-pml' -or $env:COMPOSE_PROJECT_NAME -ne 'exchange-pml') { throw '셸 변수 고정 실패' }
$resolved = (Invoke-Compose config --format json | ConvertFrom-Json).name
if ($resolved -ne 'exchange-pml') { throw "compose 프로젝트 해석이 exchange-pml 이 아니다: $resolved" }

$appOverlay = Join-Path $repo 'harness\evidence\pml-s2-gate-20260924-0440\override-app-login.yml'
$histOverlay = Join-Path $repo 'harness\evidence\pml-s2-gate-20260924-0440\override-append-histogram.yml'
$log = Join-Path $here 'commands.log'
$expectedVolumes = [ordered]@{ 'exchange-chaos_chaos-pgdata' = '2026-09-13T11:42:01Z'; 'failstop-keep-chaos-pgdata-20260924' = '2026-09-23T15:47:36Z' }

function Write-Log {
    param([string] $Text)
    $line = '{0} [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'), $Phase, $Text
    Add-Content -Path $log -Value $line -Encoding utf8
    Write-Host $line
}
function Save-Text { param([string] $Path, [string] $Text) [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false)) }
function Save-Json { param([string] $Path, $Obj) Save-Text $Path ($Obj | ConvertTo-Json -Depth 14) }

# 보존 볼륨 두 개의 생성 시각. 바뀌었으면 멈춘다. 마운트하지 않고 inspect 만 한다.
function Test-PreservedVolumes {
    param([string] $Tag)
    $out = (& docker volume inspect @($expectedVolumes.Keys) --format '{{.Name}} {{.CreatedAt}}' 2>&1 | Out-String).Trim()
    Add-Content -Path (Join-Path $here 'volumes.txt') -Value ('{0} {1} {2}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'), $Phase, $Tag) -Encoding utf8
    Add-Content -Path (Join-Path $here 'volumes.txt') -Value $out -Encoding utf8
    $seen = @{}
    foreach ($line in ($out -split "`r?`n")) { $p = $line.Trim().Split(' '); if ($p.Count -eq 2) { $seen[$p[0]] = $p[1] } }
    foreach ($k in $expectedVolumes.Keys) {
        if ($seen[$k] -ne $expectedVolumes[$k]) { Write-Log "VOLUME CHANGED $k $($seen[$k])"; throw "보존 볼륨 확인 실패: $k" }
    }
    Write-Log "volumes $Tag unchanged"
}

# chaos-* 컨테이너가 다른 compose 프로젝트 것이면 멈춘다(보존 스택 컨테이너와 이름이 같다).
function Assert-NoForeignChaos {
    $rows = @(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}|{{.Label "com.docker.compose.project"}}')
    $foreign = @($rows | Where-Object { $_ -and $_.Split('|')[1] -ne 'exchange-pml' })
    if ($foreign.Count -gt 0) { throw ('다른 프로젝트의 chaos- 컨테이너가 있다: {0}' -f ($foreign -join ', ')) }
}

function Invoke-ComposeWith {
    param([string[]] $Overlays, [string] $ComposeArgs)
    $files = "-f `"$($Global:CHAOS.ComposeFile)`""
    foreach ($o in $Overlays) { $files += " -f `"$o`"" }
    $cmd = "docker compose -p $($Global:CHAOS.Project) $files --env-file `"$($Global:CHAOS.EnvFile)`" $ComposeArgs"
    Write-Log "COMPOSE $($Global:CHAOS.Project) overlays=[$(($Overlays | ForEach-Object { Split-Path $_ -Leaf }) -join ',')] $ComposeArgs image=$env:CHAOS_APP_IMAGE"
    & cmd /c "$cmd >nul 2>nul"
    $rc = $LASTEXITCODE
    Write-Log "COMPOSE rc=$rc"
    return $rc
}

function Invoke-Py {
    param([string[]] $ArgList, [string] $LogPath)
    Write-Log ('RUN python {0}' -f ($ArgList -join ' '))
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    Push-Location $repo
    try { & python @ArgList *> $LogPath; $rc = $LASTEXITCODE } finally { Pop-Location; $ErrorActionPreference = $eap }
    Write-Log "RC $rc"
    return $rc
}

function Get-DbNow { return (Invoke-Psql "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.MS')") }

function Get-Lease {
    $raw = Invoke-Psql "SELECT json_build_object('holder', holder, 'epoch', epoch, 'alive', lease_until > now()) FROM leader_lease WHERE resource='matching-engine'"
    if (-not $raw) { return $null }
    return ($raw | ConvertFrom-Json)
}

function Wait-NewLeader {
    param([long] $AfterEpoch, [int] $TimeoutSec = 240)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try { $l = Get-Lease; if ($l -and $l.alive -and [long]$l.epoch -gt $AfterEpoch) { return $l } } catch { }
        Start-Sleep -Seconds 2
    }
    return $null
}

function Initialize-RuntimeRoles {
    param([string] $Dir)
    foreach ($k in 'PML_APP_PASSWORD', 'PML_BOT_PASSWORD', 'PML_READ_PASSWORD') {
        Set-Item -Path "Env:$k" -Value ([guid]::NewGuid().ToString('N'))
    }
    $env:PML_APP_USER = 'pml_app'; $env:PML_BOT_USER = 'pml_bot'; $env:PML_READ_USER = 'pml_read'
    $env:DB_USER = $env:PML_APP_USER; $env:DB_PASSWORD = $env:PML_APP_PASSWORD
    $env:BOT_DB_USER = $env:PML_BOT_USER; $env:BOT_DB_PASSWORD = $env:PML_BOT_PASSWORD
    $env:READ_DB_USER = $env:PML_READ_USER; $env:READ_DB_PASSWORD = $env:PML_READ_PASSWORD
    $sql = [System.IO.File]::ReadAllText((Join-Path $repo 'deploy\provision-runtime-roles.sql'))
    Write-Log 'RUN provision-runtime-roles.sql (docker exec -i chaos-pg psql -U exchange, 로그인 pml_app·pml_bot·pml_read)'
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $out = ($sql | & docker exec -i -e DB_USER -e DB_PASSWORD -e BOT_DB_USER -e BOT_DB_PASSWORD -e READ_DB_USER -e READ_DB_PASSWORD `
            $Global:CHAOS.Pg psql -U $Global:CHAOS.PgUser -d $Global:CHAOS.PgDb -X -q 2>&1 | Out-String)
        $rc = $LASTEXITCODE
    } finally { $ErrorActionPreference = $eap }
    Save-Text (Join-Path $Dir 'provision.log') $out
    Write-Log "provision rc=$rc"
    if ($rc -ne 0) { throw "런타임 로그인 준비 실패(rc=$rc)" }
    $roles = Invoke-Psql ("SELECT json_agg(json_build_object('role', r.rolname, 'super', r.rolsuper, 'login', r.rolcanlogin, " +
        "'member_of', (SELECT array_agg(g.rolname ORDER BY g.rolname) FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid WHERE m.member = r.oid)) ORDER BY r.rolname) " +
        "FROM pg_roles r WHERE r.rolname IN ('pml_app','pml_bot','pml_read')")
    Save-Text (Join-Path $Dir 'runtime_roles.json') $roles
    return ($roles | ConvertFrom-Json)
}

# summary.sql 을 소유자 로그인으로 통째로 돌린다. CRITICAL·HIGH 행이 모두 PASS 여야 한다(공통 판정).
function Invoke-Summary {
    param([string] $Dir, [string] $Name)
    $sqlText = [System.IO.File]::ReadAllText((Join-Path $repo 'harness\sql\summary.sql'))
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $out = ($sqlText | & docker exec -i $Global:CHAOS.Pg psql -U $Global:CHAOS.PgUser -d $Global:CHAOS.PgDb -X -q -t -A -F '|' -v ON_ERROR_STOP=1 2>&1 | Out-String)
        $rc = $LASTEXITCODE
    } finally { $ErrorActionPreference = $eap }
    Save-Text (Join-Path $Dir "$Name.txt") $out
    $rows = @()
    foreach ($line in ($out -split "`r?`n")) {
        $f = $line.Split('|')
        if ($f.Count -eq 6 -and $f[0] -match '^C\d') {
            $rows += [ordered]@{ code = $f[0]; check = $f[1]; severity = $f[2]; scope = $f[3]
                violatedRows = $(if ($f[4] -eq '') { $null } else { [long]$f[4] }); verdict = $f[5] }
        }
    }
    $gate = @($rows | Where-Object { $_.severity -in 'CRITICAL', 'HIGH' })
    $notPass = @($gate | Where-Object { $_.verdict -ne 'PASS' } | ForEach-Object { '{0}={1}({2})' -f $_.code, $_.violatedRows, $_.verdict })
    $warn = @($rows | Where-Object { $_.severity -eq 'WARN' } | ForEach-Object { '{0}={1}' -f $_.code, $_.verdict })
    $res = [ordered]@{ rc = $rc; rows = $rows.Count; gateRows = $gate.Count; notPass = $notPass; warn = $warn
        c29 = (@($rows | Where-Object { $_.code -eq 'C29' }) | Select-Object -First 1).verdict
        c31 = (@($rows | Where-Object { $_.code -eq 'C31' }) | Select-Object -First 1).verdict
        ok = ($rc -eq 0 -and $gate.Count -gt 0 -and $notPass.Count -eq 0) }
    Write-Log ("SUMMARY {0} rc={1} rows={2} gate={3} notPass=[{4}] C29={5} C31={6}" -f $Name, $rc, $rows.Count, $gate.Count, ($notPass -join ','), $res.c29, $res.c31)
    return $res
}

# 권위 run(POST /harness/v1/invariants/run). 트랜잭션 첫 문장에 SET LOCAL jit = off 를 거는 판이다.
function Invoke-Authoritative {
    param([string] $Dir, [string] $RunId)
    $port = Get-LiveNodePort
    try {
        $r = Invoke-InvariantRun -Port $port -RunId $RunId -Window 'post'
        Save-Json (Join-Path $Dir "authoritative-$RunId.json") $r
        $bad = @($r.checks | Where-Object { $_.severity -in 'CRITICAL', 'HIGH' -and -not $_.ok } | ForEach-Object { '{0}={1}' -f $_.checkName, $_.violatedRows })
        $c31 = @($r.checks | Where-Object { $_.checkName -eq 'C31_RESERVATION_LINK' })
        $res = [ordered]@{ port = $port; verdict = $r.verdict; checks = @($r.checks).Count; notOk = $bad; c31Present = ($c31.Count -eq 1)
            ok = ($bad.Count -eq 0 -and $c31.Count -eq 1 -and @($r.checks).Count -gt 0) }
    } catch {
        $res = [ordered]@{ port = $port; error = $_.Exception.Message; ok = $false }
    }
    Write-Log ("AUTHORITATIVE {0} ok={1} notOk=[{2}] {3}" -f $RunId, $res.ok, ($res.notOk -join ','), $res.error)
    return $res
}

# 노드 actuator 에서 이름이 맞는 계열의 합(라벨 무관)과 라벨 거른 값을 읽는다. 누적 카운터라 창의 값은 전후 차다.
$nodeMetricNames = @(
    'exchange_settlement_insufficient_funds_total', 'exchange_settlement_lock_plan_miss_total', 'exchange_settlement_lock_plan_taken_total',
    'exchange_settlement_transient_retries_total', 'exchange_settlement_transient_exhausted_total',
    'exchange_order_reserve_rejected_total', 'exchange_order_append_transient_total', 'exchange_settlement_transient_retries_by_state_total',
    'exchange_reservation_unsettled_oldest_seconds', 'exchange_reservation_unsettled_count', 'exchange_reservation_backlog_query_failed_total',
    'exchange_leader_protocol_boot', 'exchange_order_inflight_enabled', 'http_server_requests_seconds_count')
function Get-NodeMetrics {
    $res = [ordered]@{}
    foreach ($p in 18080, 28080) {
        $m = [ordered]@{ ok = $false }
        try {
            $t = (Invoke-WebRequest "http://localhost:$p/actuator/prometheus" -TimeoutSec 10 -UseBasicParsing).Content
            $m.ok = $true
            foreach ($line in ($t -split "`n")) {
                if ($line -match '^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{([^}]*)\})?\s+(\S+)') {
                    $name = $Matches[1]; $labels = $Matches[3]; $v = [double]$Matches[4]
                    if ($nodeMetricNames -notcontains $name) { continue }
                    if ($name -eq 'http_server_requests_seconds_count') {
                        if ($labels -notmatch 'uri="/api/v1/orders"' -or $labels -notmatch 'method="POST"') { continue }
                        $st = $(if ($labels -match 'status="(\d+)"') { $Matches[1] } else { 'x' })
                        $key = "orders_post_$st"
                    } elseif ($labels -match 'sqlstate="([0-9A-Z]+)"') { $key = "$name{sqlstate=$($Matches[1])}" }
                    elseif ($labels -match 'source="([a-z]+)"') { $key = "$name{source=$($Matches[1])}" }
                    else { $key = $name }
                    $m[$key] = $(if ($m.Contains($key)) { $m[$key] + $v } else { $v })
                }
            }
        } catch { $m['error'] = $_.Exception.Message }
        $res["$p"] = $m
    }
    return $res
}

function Get-MetricDelta {
    param($Before, $After)
    $d = [ordered]@{}
    foreach ($p in '18080', '28080') {
        $a = $After[$p]; $b = $Before[$p]
        $x = [ordered]@{}
        if ($a.ok -and $b.ok) {
            foreach ($k in $a.Keys) { if ($k -in 'ok', 'error') { continue }; $x[$k] = $a[$k] - $(if ($b.Contains($k)) { $b[$k] } else { 0 }) }
        } else { $x['error'] = '긁기 실패' }
        $d[$p] = $x
    }
    return $d
}

function Get-PgCounters {
    $raw = Invoke-Psql "SELECT json_build_object('xact_commit', xact_commit, 'deadlocks', deadlocks, 'at', clock_timestamp()) FROM pg_stat_database WHERE datname = 'exchange'"
    return ($raw | ConvertFrom-Json)
}

# 잔고 행 대기 표본. 행 잠금을 기다리는 백엔드는 balances 의 tuple 잠금을 쥐거나 기다린다. ctid 로 계정을 찾아 사용자 행과
# 시스템(수수료 등) 행을 나눈다. balances 는 파티션 표가 아니다(V1).
$balanceWaitSql = @"
SELECT json_build_object(
  'ts', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
  'lock_waiters', (SELECT count(*) FROM pg_stat_activity WHERE datname = 'exchange' AND wait_event_type = 'Lock'),
  'by_event', (SELECT coalesce(json_object_agg(wait_event, n), '{}'::json)
                 FROM (SELECT wait_event, count(*) AS n FROM pg_stat_activity
                        WHERE datname = 'exchange' AND wait_event_type = 'Lock' GROUP BY 1) t),
  'balance_row_waiters', (SELECT coalesce(json_object_agg(k, n), '{}'::json) FROM (
        SELECT coalesce(ac.owner_type || ':' || ac.purpose, 'unknown') AS k, count(DISTINCT l.pid) AS n
          FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
          LEFT JOIN balances b ON b.ctid = format('(%s,%s)', l.page, l.tuple)::tid
          LEFT JOIN accounts ac ON ac.id = b.account_id
         WHERE l.locktype = 'tuple' AND l.relation = 'balances'::regclass AND a.wait_event_type = 'Lock'
         GROUP BY 1) t))
"@

function Get-LogCounts {
    param([string[]] $Containers = @('chaos-api-1', 'chaos-api-2', 'chaos-bots'))
    $res = [ordered]@{}
    foreach ($c in $Containers) {
        $lines = @(& cmd /c "docker logs $c 2>&1")
        $res[$c] = [ordered]@{
            lines = $lines.Count
            permissionDenied = @($lines | Select-String -SimpleMatch 'permission denied').Count
            sqlstate42501 = @($lines | Select-String -SimpleMatch '42501').Count
            undefinedFunction = @($lines | Select-String -Pattern 'function .* does not exist').Count
            writerFailFast = @($lines | Select-String -SimpleMatch 'fail-fast').Count
            terminalDemotion = @($lines | Select-String -SimpleMatch '종신 강등').Count
            leadershipLost = @($lines | Select-String -SimpleMatch '리더십 상실').Count
            leadershipReleased = @($lines | Select-String -SimpleMatch '리더십 반납').Count
            promotions = @($lines | Select-String -SimpleMatch '리더 승격').Count
            insufficientAtSettlement = @($lines | Select-String -SimpleMatch 'chk_balance_nonneg').Count
            deadlock = @($lines | Select-String -SimpleMatch '40P01').Count
            errorLines = @($lines | Select-String -Pattern ' ERROR ').Count }
    }
    return $res
}

function Get-ConnectionLogins {
    $ips = [ordered]@{}
    foreach ($c in 'chaos-toxiproxy', 'chaos-bots') {
        $nets = (& cmd /c "docker inspect -f `"{{json .NetworkSettings.Networks}}`" $c 2>nul" | Out-String).Trim()
        if (-not $nets) { continue }
        $n = ($nets | ConvertFrom-Json -AsHashtable)[$Global:CHAOS.Network]
        if ($n -and $n['IPAddress']) { $ips[$c] = $n['IPAddress'] }
    }
    $raw = Invoke-Psql ("SELECT coalesce(json_agg(json_build_object('usename', usename, 'client_addr', host(client_addr), 'n', n)), '[]'::json) FROM " +
        "(SELECT usename, client_addr, count(*) AS n FROM pg_stat_activity WHERE datname = 'exchange' AND backend_type = 'client backend' GROUP BY 1, 2) s")
    $rows = @($raw | ConvertFrom-Json)
    return [ordered]@{ ips = $ips; rows = $rows
        apiLogins = @($rows | Where-Object { $_.client_addr -eq $ips['chaos-toxiproxy'] } | ForEach-Object { $_.usename } | Sort-Object -Unique)
        botLogins = @($rows | Where-Object { $_.client_addr -eq $ips['chaos-bots'] } | ForEach-Object { $_.usename } | Sort-Object -Unique) }
}

function Get-ContainerFacts {
    $out = [ordered]@{}
    foreach ($c in 'chaos-api-1', 'chaos-api-2', 'chaos-bots') {
        $envs = @(& docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' $c 2>$null)
        $out[$c] = [ordered]@{ imageId = (& docker inspect -f '{{.Image}}' $c 2>$null | Out-String).Trim()
            project = (& docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' $c 2>$null | Out-String).Trim()
            datasourceUser = "$($envs | Where-Object { $_ -like 'SPRING_DATASOURCE_USERNAME=*' } | Select-Object -First 1)"
            flyway = "$($envs | Where-Object { $_ -like 'SPRING_FLYWAY_ENABLED=*' } | Select-Object -First 1)"
            profiles = "$($envs | Where-Object { $_ -like 'SPRING_PROFILES_ACTIVE=*' } | Select-Object -First 1)" }
    }
    return $out
}

function Get-FlywayState {
    $raw = Invoke-Psql ("SELECT json_build_object('rows', count(*), 'max_version', max(version::int) FILTER (WHERE version IS NOT NULL), " +
        "'failed', count(*) FILTER (WHERE NOT success)) FROM flyway_schema_history")
    return ($raw | ConvertFrom-Json)
}

function Get-V49State {
    $raw = Invoke-Psql ("SELECT json_build_object('lease_update', has_table_privilege('app_role', 'leader_lease', 'UPDATE'), " +
        "'journal_insert', has_table_privilege('app_role', 'command_journal', 'INSERT'), " +
        "'reservations_select', has_table_privilege('app_role', 'order_reservations', 'SELECT'), " +
        "'reservations_insert', has_table_privilege('app_role', 'order_reservations', 'INSERT'), " +
        "'set_protocol_execute', has_function_privilege('app_role', 'set_leader_protocol(smallint, boolean)', 'EXECUTE'))")
    return ($raw | ConvertFrom-Json)
}

function Get-Alerts {
    try {
        $r = Invoke-RestMethod 'http://localhost:59090/api/v1/alerts' -TimeoutSec 10
        return @($r.data.alerts | ForEach-Object { [ordered]@{ name = $_.labels.alertname; state = $_.state; activeAt = $_.activeAt } })
    } catch { return @([ordered]@{ error = $_.Exception.Message }) }
}

function Invoke-X1 {
    $dir = Join-Path $here 'X1'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $env:CHAOS_SPRING_PROFILES = 'dev,chaos-monkey,prod-timeouts'
    Remove-Item Env:CHAOS_EXTRA_COMPOSE -ErrorAction SilentlyContinue
    $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
    Write-Log "down -v (exchange-pml) rc=$code"
    Assert-NoForeignChaos
    $code = Invoke-ComposeQuiet '--profile obs up -d --wait'
    Write-Log "up --profile obs (owner login, Flyway by api-1, profiles=$env:CHAOS_SPRING_PROFILES) rc=$code"
    if ($code -ne 0) { throw "compose up 실패 rc=$code" }
    $pre = [ordered]@{ flyway = Get-FlywayState; protocol = Invoke-Psql 'SELECT protocol FROM leader_protocol'; v49 = Get-V49State; containers = Get-ContainerFacts }
    Save-Json (Join-Path $dir 'pre.json') $pre
    $roles = Initialize-RuntimeRoles -Dir $dir
    $e0 = [long](Get-Lease).epoch
    $rc = Invoke-ComposeWith @($appOverlay) 'up -d --no-deps --force-recreate chaos-api-1 chaos-api-2 chaos-bots'
    if ($rc -ne 0) { throw "앱 역할 로그인 재기동 실패(rc=$rc)" }
    $p1Leader = Wait-NewLeader -AfterEpoch $e0 -TimeoutSec 240
    if (-not $p1Leader) { throw '앱 역할 로그인으로 리더가 서지 않았다' }
    Write-Log "P1 app-login leader epoch=$($p1Leader.epoch)"
    Start-Sleep -Seconds 20
    $prc = Invoke-Py @('harness/scale/protocol_latch.py', 'transition', '--target', '2', '--out', (Join-Path $dir 'protocol.json')) (Join-Path $dir 'protocol.log')
    if ($prc -ne 0) { throw "P2 전환 실패(rc=$prc)" }
    $proto = Get-Content (Join-Path $dir 'protocol.json') -Raw -Encoding utf8 | ConvertFrom-Json
    $lease2 = Get-Lease
    Write-Log "P2 leader epoch=$($lease2.epoch) (expected $($proto.expected_leader_epoch))"
    $n0 = Get-NodeMetrics
    $pg0 = Get-PgCounters
    $since = Get-DbNow
    Invoke-Psql "INSERT INTO sim_commands (action, payload, requested_by) VALUES ('target_ops', '$X1BotOps', 'run-s3-gate')" | Out-Null
    Write-Log "bots target_ops=$X1BotOps, run ${X1Minutes}min from $since"
    $samples = @()
    $end = (Get-Date).AddMinutes($X1Minutes)
    while ((Get-Date) -lt $end) {
        $s = [ordered]@{ at = (Get-Date).ToUniversalTime().ToString('o') }
        $nm = Get-NodeMetrics
        foreach ($p in '18080', '28080') {
            $s["gauge_$p"] = [ordered]@{ count = $nm[$p]['exchange_reservation_unsettled_count']; oldest = $nm[$p]['exchange_reservation_unsettled_oldest_seconds'] }
        }
        try { $s['balance'] = (Invoke-Psql $balanceWaitSql | ConvertFrom-Json) } catch { $s['balance'] = $null }
        $l = Get-Lease; $s['lease_epoch'] = $l.epoch; $s['lease_alive'] = $l.alive
        $samples += $s
        Start-Sleep -Seconds 15
    }
    $n1 = Get-NodeMetrics
    $pg1 = Get-PgCounters
    $until = Get-DbNow
    $leaseEnd = Get-Lease
    $conns = Get-ConnectionLogins
    $facts = Get-ContainerFacts
    $alertsDuring = Get-Alerts
    $code = Invoke-DockerQuiet 'stop chaos-bots'
    $drained = Wait-DrainZero -Sql $Global:CHAOS_DRAIN_SQL -TimeoutSec 120 -RequiredStreak 2
    $stRc = Invoke-Py @('harness/scale/protocol_latch.py', 'state', '--out', (Join-Path $dir 'state-after-drain.json')) (Join-Path $dir 'state-after-drain.log')
    $state = Get-Content (Join-Path $dir 'state-after-drain.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Start-Sleep -Seconds 16
    $n2 = Get-NodeMetrics
    $auth = Invoke-Authoritative -Dir $dir -RunId 'X1-post'
    $sum = Invoke-Summary -Dir $dir -Name 'summary-X1'
    $counts = (Invoke-Psql ("SELECT json_build_object(" +
        "'p2_new', (SELECT count(*) FROM command_journal c JOIN lease_acquisitions a ON a.epoch = c.fencing_token AND a.protocol = 2 WHERE c.command_type = 'NEW'), " +
        "'reservations', (SELECT count(*) FROM order_reservations), " +
        "'reservations_in_window', (SELECT count(*) FROM order_reservations WHERE created_at > '$since'::timestamp AT TIME ZONE 'UTC'), " +
        "'reserve_txn_total', (SELECT count(*) FROM ledger_transactions WHERE business_event_type = 'RESERVE'), " +
        "'release_txn_total', (SELECT count(*) FROM ledger_transactions WHERE business_event_type = 'RELEASE'), " +
        "'snapshot_markets_epoch', (SELECT count(DISTINCT market_id) FROM snapshots WHERE fencing_epoch = $($lease2.epoch)), " +
        "'cmd_epoch', (SELECT count(*) FROM command_journal WHERE fencing_token = $($lease2.epoch)), " +
        "'trades_since', (SELECT count(*) FROM trades WHERE created_at > '$since'::timestamp AT TIME ZONE 'UTC'), " +
        "'orders_since', (SELECT count(*) FROM orders WHERE created_at > '$since'::timestamp AT TIME ZONE 'UTC'))") | ConvertFrom-Json)
    $v49 = Get-V49State
    $logs = Get-LogCounts
    $timingRc = Invoke-Py @((Join-Path $here 'stmt_timing.py'), '--container', 'chaos-pg', '--user', 'exchange', '--db', 'exchange',
        '--sql-dir', (Join-Path $here 'sql'), '--out', (Join-Path $dir 'timings.json'), '--plans', (Join-Path $dir 'plans')) (Join-Path $dir 'timings.log')
    $window = Get-MetricDelta $n0 $n1
    $total = Get-MetricDelta $n0 $n2
    $leaderPort = $(if ($leaseEnd.holder -like '*api-2*' -or $leaseEnd.holder -like '*28080*') { '28080' } else { '18080' })
    $sumOrders = { param($d, $pat) $t = 0.0; foreach ($p in '18080', '28080') { foreach ($k in $d[$p].Keys) { if ($k -like $pat) { $t += $d[$p][$k] } } }; $t }
    $posts = & $sumOrders $window 'orders_post_*'
    $b400 = & $sumOrders $window 'orders_post_400'
    $gaugeMax = ($samples | ForEach-Object { @($_['gauge_18080'].oldest, $_['gauge_28080'].oldest) } | Where-Object { $_ -ne $null } | Measure-Object -Maximum).Maximum
    $gaugeCountMax = ($samples | ForEach-Object { @($_['gauge_18080'].count, $_['gauge_28080'].count) } | Where-Object { $_ -ne $null } | Measure-Object -Maximum).Maximum
    $epochs = @($samples | ForEach-Object { $_.lease_epoch } | Sort-Object -Unique)
    $apiLogs = @('chaos-api-1', 'chaos-api-2')
    $axes = [ordered]@{
        'invariants_summary' = $sum.ok
        'invariants_authoritative' = $auth.ok
        'settlement_insufficient_funds_0' = ((& $sumOrders $total 'exchange_settlement_insufficient_funds_total') -eq 0 -and $n2['18080'].ok)
        'writer_fatal_demotion_logs_0' = (@($apiLogs | Where-Object { $logs[$_].writerFailFast + $logs[$_].terminalDemotion + $logs[$_].leadershipLost -gt 0 }).Count -eq 0)
        'reservations_in_window_gt_0' = ([long]$counts.reservations_in_window -gt 0)
        'unsettled_reservations_after_drain_0' = ($drained -and [long]$state.unsettled_reservations -eq 0)
        'leader_500_0' = ((& $sumOrders $window 'orders_post_500') -eq 0)
        'append_40P01_0' = ((& $sumOrders $total 'exchange_order_append_transient_total{sqlstate=40P01}') -eq 0)
        'pg_deadlocks_0' = ([long]$pg1.deadlocks - [long]$pg0.deadlocks -eq 0)
        'lock_plan_miss_0' = ((& $sumOrders $total 'exchange_settlement_lock_plan_miss_total') -eq 0)
        'p2_new_eq_reservations_ge_1000' = ([long]$counts.p2_new -eq [long]$counts.reservations -and [long]$counts.reservations -ge 1000)
        'leader_epoch_unchanged' = ($epochs.Count -eq 1 -and [long]$epochs[0] -eq [long]$lease2.epoch -and [long]$leaseEnd.epoch -eq [long]$lease2.epoch)
        'logins_app_role' = ($conns.apiLogins.Count -eq 1 -and $conns.apiLogins[0] -eq 'pml_app' -and $conns.botLogins.Count -eq 1 -and $conns.botLogins[0] -eq 'pml_bot')
        'no_permission_function_errors' = (@($logs.Keys | Where-Object { $logs[$_].permissionDenied + $logs[$_].sqlstate42501 + $logs[$_].undefinedFunction -gt 0 }).Count -eq 0)
        'v49_privileges_revoked' = (-not $v49.lease_update -and -not $v49.journal_insert)
        'snapshots_3_markets_new_epoch' = ([long]$counts.snapshot_markets_epoch -ge 3)
        'protocol_transition' = ($proto.ok -and -not $proto.already)
    }
    $verdict = $(if (@($axes.Values | Where-Object { -not $_ }).Count -eq 0) { 'PASS' } else { 'FAIL' })
    $result = [ordered]@{ phase = 'X1'; verdict = $verdict; axes = $axes; pre = $pre; roles = $roles; p1Leader = $p1Leader
        protocol = [ordered]@{ ok = $proto.ok; retired = $proto.call.rows; durationMs = $proto.call.duration_ms; newLeaderEpoch = $proto.new_leader_epoch
            expectedLeaderEpoch = $proto.expected_leader_epoch; drainSeconds = $proto.drain.seconds; checks = $proto.checks; history = $proto.history }
        lease = [ordered]@{ afterTransition = $lease2; end = $leaseEnd; sampledEpochs = $epochs }
        window = [ordered]@{ from = $since; to = $until; minutes = $X1Minutes; botOps = $X1BotOps }
        counts = $counts; v49 = $v49; connections = $conns; containers = $facts; logs = $logs
        pg = [ordered]@{ before = $pg0; after = $pg1; deadlocksDelta = [long]$pg1.deadlocks - [long]$pg0.deadlocks }
        metricsWindow = $window; metricsTotal = $total; leaderPortGuess = $leaderPort
        bots = [ordered]@{ ordersPost = $posts; status400 = $b400; ratio400 = $(if ($posts -gt 0) { $b400 / $posts } else { $null }) }
        gauge = [ordered]@{ oldestMaxSeconds = $gaugeMax; countMax = $gaugeCountMax }
        drain = [ordered]@{ botsStopRc = $code; drained = $drained; state = $state }
        authoritative = $auth; summary = $sum; timingsRc = $timingRc; alertsDuringRun = $alertsDuring; alertsAfterDrain = Get-Alerts }
    Save-Json (Join-Path $dir 'samples.json') $samples
    Save-Json (Join-Path $dir 'result.json') $result
    Write-Log ("X1 verdict={0} fails=[{1}]" -f $verdict, (($axes.Keys | Where-Object { -not $axes[$_] }) -join ','))
    $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
    Write-Log "down -v (exchange-pml) rc=$code"
}

function Invoke-Chaos {
    $sub = $(if ($SubDir) { $SubDir } else { 'chaos-' + ($Experiments -replace ',', '') })
    $dir = Join-Path $here $sub
    $env:CHAOS_SPRING_PROFILES = 'dev,chaos-monkey,prod-timeouts'
    Remove-Item Env:CHAOS_EXTRA_COMPOSE -ErrorAction SilentlyContinue
    $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
    Write-Log "down -v (exchange-pml) rc=$code before $sub"
    Assert-NoForeignChaos
    $localLogs = Join-Path ([System.IO.Path]::GetTempPath()) "pml-s3-logs\$sub"
    Write-Log "RUN pwsh -NoProfile -File harness/scripts/adr0022-stage5-chaos.ps1 -Image $Image -Experiments $Experiments -Protocol 2 -EvidenceDir $sub -LocalLogDir $localLogs"
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        & pwsh -NoProfile -File (Join-Path $repo 'harness\scripts\adr0022-stage5-chaos.ps1') -Image $Image -Experiments $Experiments `
            -Protocol 2 -EvidenceDir $dir -LocalLogDir $localLogs *> (Join-Path $here "$sub-console.log")
        $rc = $LASTEXITCODE
    } finally { $ErrorActionPreference = $eap }
    Write-Log "stage5 rc=$rc ($sub)"
}

function Invoke-X5 {
    $dir = Join-Path $here 'X5'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Remove-Item Env:CHAOS_SPRING_PROFILES -ErrorAction SilentlyContinue
    $env:CHAOS_EXTRA_COMPOSE = $histOverlay
    $env:SCALE_LOAD_KEYS = '16'
    if ($Measure -eq 'hot') { $env:SCALE_MARKETS = 'KRW-BTC'; $r = $(if ($Rate -gt 0) { $Rate } else { 300 }); $suffix = "hot$r" }
    else { Remove-Item Env:SCALE_MARKETS -ErrorAction SilentlyContinue; $r = $(if ($Rate -gt 0) { $Rate } else { 600 }); $suffix = "$r" }
    foreach ($cell in ($Cells.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })) {
        $mode = $(if ($cell -like 'P2*') { 2 } elseif ($cell -like 'P1*') { 1 } else { throw "칸 이름은 P1*·P2* 다: $cell" })
        $label = $(if ($Measure -eq 'hot') { "${cell}h" } else { $cell })
        $out = Join-Path $dir "$cell-$suffix.json"
        if (Test-Path $out) { Write-Log "SKIP $cell-$suffix (이미 있다)"; continue }
        $code = Invoke-ComposeQuiet '--profile obs --profile admin --profile load down -v --remove-orphans'
        Assert-NoForeignChaos
        $argList = @('harness/scale/roundtrip_probe.py', '--label', $label, '--rate', "$r", '--out', $out)
        if ($mode -eq 2) { $argList += @('--protocol-before-traffic', '2') }
        Write-Log "PROBE $cell measure=$Measure rate=$r mode=P$mode keys=$env:SCALE_LOAD_KEYS markets=$env:SCALE_MARKETS"
        $prc = Invoke-Py $argList (Join-Path $dir "probe-$cell-$suffix.log")
        $cellRes = [ordered]@{ cell = $cell; measure = $Measure; rate = $r; mode = $mode; probeRc = $prc; file = (Split-Path $out -Leaf) }
        if ($prc -eq 0 -and (Test-Path $out)) {
            $p = Get-Content $out -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
            $win = $p['접수(DB, 마켓별)']['창']
            $cellRes['window'] = $win
            $cellRes['prom'] = Get-PromWindow -FromDb $win['from'] -ToDb $win['to']
            $lbRaw = Join-Path ([System.IO.Path]::GetTempPath()) "pml-s3-lb-$cell-$suffix.log"
            & cmd /c "docker logs chaos-lb > `"$lbRaw`" 2>&1"
            & python (Join-Path $repo 'harness\evidence\pml-s2-gate-20260924-0440\lb_status.py') --log $lbRaw --probe $out --label $cell `
                --out (Join-Path $dir "lb_status_$cell-$suffix.json") | Out-Null
            Remove-Item $lbRaw -Force -ErrorAction SilentlyContinue
        }
        $cellRes['nodeMetrics'] = Get-NodeMetrics
        $cellRes['logs'] = Get-LogCounts
        $code = Invoke-DockerQuiet 'stop chaos-bots'
        $cellRes['drained'] = Wait-DrainZero -Sql $Global:CHAOS_DRAIN_SQL -TimeoutSec 120 -RequiredStreak 2
        $cellRes['summary'] = Invoke-Summary -Dir $dir -Name "summary-$cell-$suffix"
        $cellRes['flyway'] = Get-FlywayState
        Save-Json (Join-Path $dir "cell-$cell-$suffix.json") $cellRes
    }
    $code = Invoke-ComposeQuiet '--profile obs --profile admin --profile load down -v --remove-orphans'
    Write-Log "down -v (exchange-pml) rc=$code after X5 $Measure"
}

# Prometheus 에서 k6 창의 값을 읽는다. 창 끝은 probe 가 적은 DB 시각이다. 상태 코드는 노드(instance)별로 나눈다.
function Get-PromWindow {
    param([string] $FromDb, [string] $ToDb)
    $t0 = [DateTimeOffset]::Parse(($FromDb.Trim() + 'Z').Replace(' ', 'T').Replace('+00Z', 'Z'))
    $t1 = [DateTimeOffset]::Parse(($ToDb.Trim() + 'Z').Replace(' ', 'T').Replace('+00Z', 'Z'))
    $sec = [int][math]::Ceiling(($t1 - $t0).TotalSeconds)
    $at = $t1.ToUnixTimeSeconds()
    $qs = [ordered]@{
        appendMeanMs = "1000 * sum(increase(exchange_journal_append_seconds_sum[${sec}s])) / sum(increase(exchange_journal_append_seconds_count[${sec}s]))"
        appendP95Ms = "1000 * histogram_quantile(0.95, sum by (le) (increase(exchange_journal_append_seconds_bucket[${sec}s])))"
        appendP99Ms = "1000 * histogram_quantile(0.99, sum by (le) (increase(exchange_journal_append_seconds_bucket[${sec}s])))"
        ordersByInstanceStatus = "sum by (instance, status) (increase(http_server_requests_seconds_count{uri=`"/api/v1/orders`",method=`"POST`"}[${sec}s]))"
        http5xxByInstance = "sum by (instance) (increase(http_server_requests_seconds_count{status=~`"5..`"}[${sec}s]))"
        settlementRetries = "sum(increase(exchange_settlement_transient_retries_total[${sec}s]))"
        settlementRetriesByState = "sum by (sqlstate) (increase(exchange_settlement_transient_retries_by_state_total[${sec}s]))"
        append40P01 = "sum(increase(exchange_order_append_transient_total{sqlstate=`"40P01`"}[${sec}s]))"
        lockPlanMiss = "sum(increase(exchange_settlement_lock_plan_miss_total[${sec}s]))"
        insufficientFunds = "sum(increase(exchange_settlement_insufficient_funds_total[${sec}s]))"
        reserveRejectedBySource = "sum by (source) (increase(exchange_order_reserve_rejected_total[${sec}s]))"
        api1Cpu = "avg_over_time(container_cpu_percent{name=`"chaos-api-1`"}[${sec}s])"
        dbCpu = "avg_over_time(container_cpu_percent{name=`"chaos-pg`"}[${sec}s])"
    }
    $out = [ordered]@{ from = $FromDb; to = $ToDb; seconds = $sec; evalAt = $at }
    foreach ($k in $qs.Keys) {
        try {
            $r = Invoke-RestMethod -Uri ('http://localhost:59090/api/v1/query?query={0}&time={1}' -f [uri]::EscapeDataString($qs[$k]), $at) -TimeoutSec 20
            $v = @($r.data.result)
            if ($v.Count -eq 1 -and -not ($v[0].metric.PSObject.Properties.Name)) { $out[$k] = [double]$v[0].value[1] }
            elseif ($v.Count -eq 0) { $out[$k] = $null }
            else { $out[$k] = @($v | ForEach-Object { [ordered]@{ metric = $_.metric; value = [double]$_.value[1] } }) }
        } catch { $out[$k] = "error: $($_.Exception.Message)" }
    }
    $out['queries'] = $qs
    return $out
}

Write-Log "PHASE $Phase project=$resolved repo=$repo image=$Image"
Test-PreservedVolumes 'before'
try {
    switch ($Phase) {
        'Prep' {
            Assert-NoForeignChaos
            $img = (& docker image inspect $Image --format '{{.Id}}|{{.Created}}' | Out-String).Trim().Split('|')
            $head = (& git -C $repo rev-parse HEAD | Out-String).Trim()
            $status = (& git -C $repo status --porcelain | Out-String)
            $diffHash = ((& git -C $repo diff HEAD | Out-String) | ForEach-Object { [System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::HashData([System.Text.Encoding]::UTF8.GetBytes($_))).Replace('-', '').ToLower() })
            $jar = Join-Path $repo 'harness\app.jar'
            $images = [ordered]@{ tag = $Image; imageId = $img[0]; created = $img[1]; worktree = $repo; head = $head
                trackedDiffSha256 = $diffHash; changedPaths = @($status -split "`r?`n" | Where-Object { $_ }).Count
                jarSha256 = (Get-FileHash $jar -Algorithm SHA256).Hash.ToLower(); jarWritten = (Get-Item $jar).LastWriteTime.ToString('o')
                note = 'S3 는 이 단계에서 커밋하지 않는다. 이미지는 HEAD 위 작업 트리 변경(추적 파일 diff 해시와 새 파일)을 담는다' }
            Save-Json (Join-Path $here 'images.json') $images
            Save-Text (Join-Path $here 'docker-ps-before.txt') ((& docker ps -a --format '{{.Names}} {{.Image}} {{.Status}} {{.Label "com.docker.compose.project"}}' | Out-String))
            Write-Log "images.json image=$($img[0]) head=$head diff=$diffHash"
        }
        'X1' { Invoke-X1 }
        'Chaos' { Invoke-Chaos }
        'X5' { Invoke-X5 }
        'X7' {
            $dir = Join-Path $here ("X7\n{0}" -f $Rows)
            New-Item -ItemType Directory -Force -Path $dir | Out-Null
            $bash = 'C:\Program Files\Git\bin\bash.exe'
            Write-Log "RUN N=$Rows bash X7/s3-scale-probe-12m.sh X7/n$Rows"
            $env:N = "$Rows"
            $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
            $script = (Join-Path $here 'X7\s3-scale-probe-12m.sh').Replace([char]92, [char]47)
            try { & $bash $script $dir.Replace([char]92, [char]47) *> (Join-Path $dir 'probe.log'); $rc = $LASTEXITCODE }
            finally { $ErrorActionPreference = $eap; Remove-Item Env:N -ErrorAction SilentlyContinue }
            Write-Log "X7 N=$Rows rc=$rc"
        }
        'Final' {
            $code = Invoke-ComposeQuiet '--profile obs --profile admin --profile load down -v --remove-orphans'
            Write-Log "final down -v (exchange-pml) rc=$code"
            Save-Text (Join-Path $here 'docker-ps-after.txt') ((& docker ps -a --format '{{.Names}} {{.Image}} {{.Status}} {{.Label "com.docker.compose.project"}}' | Out-String))
        }
    }
} finally {
    Test-PreservedVolumes 'after'
}
