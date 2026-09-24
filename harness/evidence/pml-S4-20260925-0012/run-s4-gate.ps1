# S4 관문 폐기용 스택 재주행(마켓분할_리더_S4_설계 7절, 구현 검토 라운드 1 수정 뒤 코드). compose 프로젝트 exchange-pml, 앱 이미지 exchange-chaos-app:pml 에서만 돈다.
# pml-S4-20260924-1950 의 실행기를 복사해 Prep 과 X7 의 참조 태그 확인만 바꿨다.
#   Prep  : 시작 전 기록. 지금 :pml 이 가리키는 수정 전 S4 이미지 id(참조 태그 s4ref), s3ref id, 작업 트리 커밋과 변경 해시,
#           보존 볼륨 생성 시각, chaos-* 컨테이너 부재. 그 뒤 up.ps1 -Obs 로 S4 이미지를 굽고(bootJar, docker build) 참조 태그
#           s4r1ref 를 붙인 뒤 스택을 한 번 띄웠다 지운다. images.json 에 세 id 를 남긴다
#   X1    : 소유자 로그인으로 V1~V53 적용 -> 런타임 로그인(app_role 소속) -> P2 전환 -> 봇 300 OPS 10분 -> 공통 판정과 S4 축.
#           -WithX6 이면 같은 스택에서 X6(harness/scripts/s4-intake-contention.ps1)을 이어 돈다
#   X6    : X1 과 같은 준비 뒤 10분 부하 없이 X6 변형만 다시 돈다(-X6Variants, -SubDir 기본 X6r)
#   Chaos : harness/scripts/adr0022-stage5-chaos.ps1 -Protocol 2 -PartitionAxes (X2 E4+합류, X3 E7, X4 E2·E5·E6, X5 COLD)
#   X7    : harness/scale/roundtrip_probe.py P2 600/s 칸. S3a·S4a·S3b·S4b 를 번갈아 돈다. 칸마다 :pml 을 그 칸의 이미지 id 로 붙인다.
#           S3 이미지는 S4 를 구울 때 사라져(containerd 이미지 저장소) S3 커밋으로 다시 구운 s3ref 를 쓰고, 두 이미지에 참조 태그
#           (s3ref·s4ref)가 있을 때만 :pml 을 옮긴다
#   X8    : E1·E3 을 새 스택에서 한 번씩(P2 전환 뒤 스크립트를 직접 부른다)
#   Final : 스택을 -p exchange-pml 로 지우고 :pml 을 S4 이미지로 되돌리고 보존 볼륨을 다시 확인한다
# 사용: pwsh -NoProfile -File run-s4-gate.ps1 -Phase <단계> [옵션]
# 보존 스택과 보존 볼륨을 건드리지 않도록 프로젝트를 네 곳(셸 변수 둘, lib.ps1, compose 해석값)에서 확인한 뒤 시작하고, 단계마다
# 앞뒤로 보존 볼륨 생성 시각을 volumes.txt 에 남겨 바뀌면 멈춘다. 해석된 compose 설정 전체는 비밀 값을 담으므로 남기지 않는다.
# 비밀 파일(harness/.env.chaos, harness/load/secrets/k6_keys.json)은 경로로만 넘긴다. 런타임 로그인 비밀번호는 실행마다 무작위로
# 만들어 셸 변수로만 넘기고 어디에도 적지 않는다. 태그를 잃은 이미지를 지우는 명령(docker image prune)은 쓰지 않는다.
param(
    [Parameter(Mandatory = $true)][ValidateSet('Prep', 'X1', 'X6', 'Chaos', 'X7', 'X8', 'Final')][string] $Phase,
    [string] $Image = 'exchange-chaos-app:pml',
    [string] $Experiments = 'E4',
    [string] $SubDir = '',
    [string] $Cells = 'S3a,S4a,S3b,S4b',
    [int] $Rate = 600,
    [int] $X1Minutes = 10,
    [int] $X1BotOps = 300,
    [switch] $WithX6,
    [string] $X6Variants = 'a,b,c,d',
    [string] $X8Experiments = 'E1,E3'
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
$imagesPath = Join-Path $here 'images.json'

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

function Invoke-Pwsh {
    param([string] $Script, [string[]] $ArgList, [string] $LogPath)
    Write-Log ('RUN pwsh -NoProfile -File {0} {1}' -f $Script, ($ArgList -join ' '))
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & pwsh -NoProfile -File (Join-Path $repo $Script) @ArgList *> $LogPath; $rc = $LASTEXITCODE } finally { $ErrorActionPreference = $eap }
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
    $res = [ordered]@{ rc = $rc; rows = $rows.Count; gateRows = $gate.Count; notPass = $notPass
        ok = ($rc -eq 0 -and $gate.Count -gt 0 -and $notPass.Count -eq 0) }
    Write-Log ("SUMMARY {0} rc={1} rows={2} gate={3} notPass=[{4}]" -f $Name, $rc, $rows.Count, $gate.Count, ($notPass -join ','))
    return $res
}

# 권위 run(POST /harness/v1/invariants/run).
function Invoke-Authoritative {
    param([string] $Dir, [string] $RunId)
    $port = Get-LiveNodePort
    try {
        $r = Invoke-InvariantRun -Port $port -RunId $RunId -Window 'post'
        Save-Json (Join-Path $Dir "authoritative-$RunId.json") $r
        $bad = @($r.checks | Where-Object { $_.severity -in 'CRITICAL', 'HIGH' -and -not $_.ok } | ForEach-Object { '{0}={1}' -f $_.checkName, $_.violatedRows })
        $res = [ordered]@{ port = $port; verdict = $r.verdict; checks = @($r.checks).Count; notOk = $bad
            ok = ($bad.Count -eq 0 -and @($r.checks).Count -gt 0) }
    } catch {
        $res = [ordered]@{ port = $port; error = $_.Exception.Message; ok = $false }
    }
    Write-Log ("AUTHORITATIVE {0} ok={1} notOk=[{2}] {3}" -f $RunId, $res.ok, ($res.notOk -join ','), $res.error)
    return $res
}

# 노드 actuator 에서 이름이 맞는 계열의 합(라벨 무관)과 라벨 거른 값을 읽는다. 누적 카운터라 창의 값은 전후 차다.
$nodeMetricNames = @(
    'exchange_settlement_insufficient_funds_total', 'exchange_settlement_lock_plan_miss_total',
    'exchange_settlement_transient_retries_total', 'exchange_settlement_transient_exhausted_total',
    'exchange_order_reserve_rejected_total', 'exchange_order_append_transient_total',
    'exchange_order_intake_contention_total', 'exchange_partition_demotions_total',
    'exchange_leader_lease_acquire_lock_timeouts_total', 'exchange_partition_state', 'exchange_admission_rejected_total',
    'http_server_requests_seconds_count')
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
                    elseif ($name -eq 'exchange_order_intake_contention_total' -and $labels -match 'cause="([^"]+)".*response="([^"]+)"') { $key = "$name{$($Matches[1])|$($Matches[2])}" }
                    elseif ($name -eq 'exchange_partition_demotions_total' -and $labels -match 'reason="([A-Z_]+)"') { $key = "$name{reason=$($Matches[1])}" }
                    elseif ($name -eq 'exchange_admission_rejected_total' -and $labels -match 'scope="([a-z]+)"') { $key = "$name{scope=$($Matches[1])}" }
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

function Get-Partitions {
    $res = [ordered]@{}
    foreach ($p in 18080, 28080) {
        try { $res["$p"] = Invoke-RestMethod "http://localhost:$p/actuator/partitions" -TimeoutSec 10 } catch { $res["$p"] = [ordered]@{ error = $_.Exception.Message } }
    }
    return $res
}

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
            partitionDemotion = @($lines | Select-String -SimpleMatch '파티션 강등').Count
            leadershipLost = @($lines | Select-String -SimpleMatch '리더십 상실').Count
            leadershipReleased = @($lines | Select-String -SimpleMatch '리더십 반납').Count
            promotions = @($lines | Select-String -SimpleMatch '리더 승격').Count
            followerBoot = @($lines | Select-String -SimpleMatch '팔로워로 기동').Count
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
        botLogins = @($rows | Where-Object { $_.client_addr -eq $ips['chaos-bots'] } | ForEach-Object { $_.usename } | Sort-Object -Unique)
        maxTotal = ($rows | Measure-Object -Property n -Sum).Sum }
}

function Get-ContainerFacts {
    $out = [ordered]@{}
    foreach ($c in 'chaos-api-1', 'chaos-api-2', 'chaos-bots') {
        $envs = @(& docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' $c 2>$null)
        $out[$c] = [ordered]@{ imageId = (& docker inspect -f '{{.Image}}' $c 2>$null | Out-String).Trim()
            project = (& docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' $c 2>$null | Out-String).Trim()
            datasourceUser = "$($envs | Where-Object { $_ -like 'SPRING_DATASOURCE_USERNAME=*' } | Select-Object -First 1)"
            profiles = "$($envs | Where-Object { $_ -like 'SPRING_PROFILES_ACTIVE=*' } | Select-Object -First 1)" }
    }
    return $out
}

function Get-FlywayState {
    $raw = Invoke-Psql ("SELECT json_build_object('rows', count(*), 'max_version', max(version::int) FILTER (WHERE version IS NOT NULL), " +
        "'failed', count(*) FILTER (WHERE NOT success)) FROM flyway_schema_history")
    return ($raw | ConvertFrom-Json)
}

function Get-Alerts {
    try {
        $r = Invoke-RestMethod 'http://localhost:59090/api/v1/alerts' -TimeoutSec 10
        return @($r.data.alerts | ForEach-Object { [ordered]@{ name = $_.labels.alertname; state = $_.state; activeAt = $_.activeAt } })
    } catch { return @([ordered]@{ error = $_.Exception.Message }) }
}

# 파티션 상태의 요약. P2 는 matching-engine 하나가 한 노드만 ACCEPTING 이고 다른 노드는 FOLLOWER 여야 한다.
function Test-OneAccepting {
    param($Parts)
    $states = @()
    foreach ($p in '18080', '28080') {
        $rows = @($Parts[$p].partitions)
        foreach ($r in $rows) { $states += "$p/$($r.resource)=$($r.state)" }
    }
    $accepting = @($states | Where-Object { $_ -like '*=ACCEPTING' }).Count
    $follower = @($states | Where-Object { $_ -like '*=FOLLOWER' }).Count
    return [ordered]@{ states = $states; ok = ($accepting -eq 1 -and $follower -eq 1) }
}

function Invoke-X1 {
    $dir = Join-Path $here 'X1'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $env:CHAOS_SPRING_PROFILES = 'dev,chaos-monkey,prod-timeouts'
    Remove-Item Env:CHAOS_EXTRA_COMPOSE -ErrorAction SilentlyContinue
    $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
    Write-Log "down -v (exchange-pml) rc=$code"
    Assert-NoForeignChaos
    $stackFrom = [DateTimeOffset]::UtcNow
    $code = Invoke-ComposeQuiet '--profile obs up -d --wait'
    Write-Log "up --profile obs (owner login, Flyway by api-1, profiles=$env:CHAOS_SPRING_PROFILES) rc=$code"
    if ($code -ne 0) { throw "compose up 실패 rc=$code" }
    $pre = [ordered]@{ flyway = Get-FlywayState; protocol = Invoke-Psql 'SELECT protocol FROM leader_protocol'; containers = Get-ContainerFacts }
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
    Start-Sleep -Seconds 10
    $axesFrom = [DateTimeOffset]::UtcNow
    $prePartRc = Invoke-Py @('harness/scale/s4_partition_axes.py', 'snapshot', '--out', (Join-Path $dir 'partition_pre.json')) (Join-Path $dir 'partition_pre.log')
    $n0 = Get-NodeMetrics
    $pg0 = Get-PgCounters
    $since = Get-DbNow
    Invoke-Psql "INSERT INTO sim_commands (action, payload, requested_by) VALUES ('target_ops', '$X1BotOps', 'run-s4-gate')" | Out-Null
    Write-Log "bots target_ops=$X1BotOps, run ${X1Minutes}min from $since"
    $samples = @()
    $connMax = 0
    $end = (Get-Date).AddMinutes($X1Minutes)
    while ((Get-Date) -lt $end) {
        $s = [ordered]@{ at = (Get-Date).ToUniversalTime().ToString('o') }
        $l = Get-Lease; $s['lease_epoch'] = $l.epoch; $s['lease_alive'] = $l.alive
        try { $c = Get-ConnectionLogins; $s['connections'] = $c.maxTotal; if ($c.maxTotal -gt $connMax) { $connMax = $c.maxTotal } } catch { $s['connections'] = $null }
        $samples += $s
        Start-Sleep -Seconds 15
    }
    $n1 = Get-NodeMetrics
    $pg1 = Get-PgCounters
    $until = Get-DbNow
    $leaseEnd = Get-Lease
    $conns = Get-ConnectionLogins
    $facts = Get-ContainerFacts
    $parts = Get-Partitions
    $postPartRc = Invoke-Py @('harness/scale/s4_partition_axes.py', 'snapshot', '--out', (Join-Path $dir 'partition_post.json')) (Join-Path $dir 'partition_post.log')
    # X1 은 주행 전체가 장애 창 밖이다(설계 7-2). LB 의 no live upstreams·502 0 을 판정에 넣는다.
    $axesRc = Invoke-Py @('harness/scale/s4_partition_axes.py', 'judge', '--window-from', $axesFrom.ToString('o'), '--lb-gate',
        '--pre', (Join-Path $dir 'partition_pre.json'), '--post', (Join-Path $dir 'partition_post.json'),
        '--out', (Join-Path $dir 'partition_axes.json')) (Join-Path $dir 'partition_axes.log')
    $axesS4 = Get-Content (Join-Path $dir 'partition_axes.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
    $alertsDuring = Get-Alerts
    if ($WithX6) {
        # 같은 스택에서 X6 을 잇는다(설계 7-2 X6). 봇은 X6 스크립트가 20 OPS 로 다시 띄운다.
        $x6Rc = Invoke-Pwsh 'harness\scripts\s4-intake-contention.ps1' @('-OutDir', (Join-Path $here 'X6'), '-Variants', $X6Variants) (Join-Path $here 'X6-console.log')
        Write-Log "X6 rc=$x6Rc"
    }
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
        "'snapshot_markets_epoch', (SELECT count(DISTINCT market_id) FROM snapshots WHERE fencing_epoch = $($lease2.epoch)), " +
        "'trades_since', (SELECT count(*) FROM trades WHERE created_at > '$since'::timestamp AT TIME ZONE 'UTC'))") | ConvertFrom-Json)
    $logs = Get-LogCounts
    $window = Get-MetricDelta $n0 $n1
    $total = Get-MetricDelta $n0 $n2
    $sumOrders = { param($d, $pat) $t = 0.0; foreach ($p in '18080', '28080') { foreach ($k in $d[$p].Keys) { if ($k -like $pat) { $t += $d[$p][$k] } } }; $t }
    $epochs = @($samples | ForEach-Object { $_.lease_epoch } | Sort-Object -Unique)
    $apiLogs = @('chaos-api-1', 'chaos-api-2')
    $one = Test-OneAccepting $parts
    $axes = [ordered]@{
        'invariants_summary' = $sum.ok
        'invariants_authoritative' = $auth.ok
        'settlement_insufficient_funds_0' = ((& $sumOrders $total 'exchange_settlement_insufficient_funds_total') -eq 0 -and $n2['18080'].ok)
        'writer_fatal_demotion_logs_0' = (@($apiLogs | Where-Object { $logs[$_].writerFailFast + $logs[$_].terminalDemotion + $logs[$_].partitionDemotion + $logs[$_].leadershipLost -gt 0 }).Count -eq 0)
        'reservations_in_window_gt_0' = ([long]$counts.reservations_in_window -gt 0)
        'unsettled_reservations_after_drain_0' = ($drained -and [long]$state.unsettled_reservations -eq 0)
        'leader_500_0' = ((& $sumOrders $window 'orders_post_500') -eq 0)
        'append_40P01_0' = ((& $sumOrders $total 'exchange_order_append_transient_total{sqlstate=40P01}') -eq 0)
        'pg_deadlocks_0' = ([long]$pg1.deadlocks - [long]$pg0.deadlocks -eq 0)
        'lock_plan_miss_0' = ((& $sumOrders $total 'exchange_settlement_lock_plan_miss_total') -eq 0)
        'leader_epoch_unchanged' = ($epochs.Count -eq 1 -and [long]$epochs[0] -eq [long]$lease2.epoch -and [long]$leaseEnd.epoch -eq [long]$lease2.epoch)
        'logins_app_role' = ($conns.apiLogins.Count -eq 1 -and $conns.apiLogins[0] -eq 'pml_app' -and $conns.botLogins.Count -eq 1 -and $conns.botLogins[0] -eq 'pml_bot')
        'no_permission_function_errors' = (@($logs.Keys | Where-Object { $logs[$_].permissionDenied + $logs[$_].sqlstate42501 + $logs[$_].undefinedFunction -gt 0 }).Count -eq 0)
        'snapshots_3_markets_new_epoch' = ([long]$counts.snapshot_markets_epoch -ge 3)
        'protocol_transition' = ($proto.ok -and -not $proto.already)
        's4_one_accepting_one_follower' = $one.ok
        's4_contention_cap' = [bool]$axesS4['axes']['contention_cap_outside_window']
        's4_partition_alerts_0' = [bool]$axesS4['axes']['partition_alerts_outside_window_0']
        's4_leader_demotions_0' = [bool]$axesS4['axes']['leader_node_demotions_0']
        's4_lb_no_live_upstreams_502_0' = [bool]$axesS4['axes']['lb_no_live_upstreams_and_502_0']
        's4_demotions_both_nodes_0' = ((& $sumOrders $window 'exchange_partition_demotions_total*') -eq 0)
    }
    $verdict = $(if (@($axes.Values | Where-Object { -not $_ }).Count -eq 0) { 'PASS' } else { 'FAIL' })
    $result = [ordered]@{ phase = 'X1'; verdict = $verdict; axes = $axes; pre = $pre; roles = $roles; p1Leader = $p1Leader
        protocol = [ordered]@{ ok = $proto.ok; retired = $proto.call.rows; newLeaderEpoch = $proto.new_leader_epoch
            expectedLeaderEpoch = $proto.expected_leader_epoch; drainSeconds = $proto.drain.seconds }
        lease = [ordered]@{ afterTransition = $lease2; end = $leaseEnd; sampledEpochs = $epochs }
        window = [ordered]@{ from = $since; to = $until; minutes = $X1Minutes; botOps = $X1BotOps }
        counts = $counts; connections = $conns; connectionMax = $connMax; containers = $facts; logs = $logs
        partitions = [ordered]@{ oneAccepting = $one; acquireLockTimeouts = (& $sumOrders $total 'exchange_leader_lease_acquire_lock_timeouts_total') }
        contention = [ordered]@{ window = $axesS4['contention'] }
        pg = [ordered]@{ before = $pg0; after = $pg1; deadlocksDelta = [long]$pg1.deadlocks - [long]$pg0.deadlocks }
        metricsWindow = $window; metricsTotal = $total
        drain = [ordered]@{ botsStopRc = $code; drained = $drained; state = $state }
        authoritative = $auth; summary = $sum; alertsDuringRun = $alertsDuring; alertsAfterDrain = Get-Alerts
        s4AxesRc = [ordered]@{ pre = $prePartRc; post = $postPartRc; judge = $axesRc; stack = $stackFrom.ToString('o') } }
    Save-Json (Join-Path $dir 'samples.json') $samples
    Save-Json (Join-Path $dir 'result.json') $result
    Write-Log ("X1 verdict={0} fails=[{1}]" -f $verdict, (($axes.Keys | Where-Object { -not $axes[$_] }) -join ','))
    $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
    Write-Log "down -v (exchange-pml) rc=$code"
}

# X6 만 다시 돈다(첫 주행의 변형 판정 기준을 고친 뒤의 재주행). X1 과 같은 준비(소유자 로그인 스택, 런타임 로그인, 앱 역할
# 로그인 재기동, P2 전환)를 하고 10분 부하 없이 X6 스크립트를 부른 다음, 봇을 멈추고 드레인해 공통 불변식을 본다.
function Invoke-X6Only {
    $sub = $(if ($SubDir) { $SubDir } else { 'X6r' })
    $dir = Join-Path $here $sub
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $env:CHAOS_SPRING_PROFILES = 'dev,chaos-monkey,prod-timeouts'
    Remove-Item Env:CHAOS_EXTRA_COMPOSE -ErrorAction SilentlyContinue
    $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
    Write-Log "down -v (exchange-pml) rc=$code before $sub"
    Assert-NoForeignChaos
    $code = Invoke-ComposeQuiet '--profile obs up -d --wait'
    Write-Log "up --profile obs rc=$code ($sub)"
    if ($code -ne 0) { throw "compose up 실패 rc=$code" }
    $roles = Initialize-RuntimeRoles -Dir $dir
    $e0 = [long](Get-Lease).epoch
    $rc = Invoke-ComposeWith @($appOverlay) 'up -d --no-deps --force-recreate chaos-api-1 chaos-api-2 chaos-bots'
    if ($rc -ne 0) { throw "앱 역할 로그인 재기동 실패(rc=$rc)" }
    if (-not (Wait-NewLeader -AfterEpoch $e0 -TimeoutSec 240)) { throw '앱 역할 로그인으로 리더가 서지 않았다' }
    Start-Sleep -Seconds 20
    $prc = Invoke-Py @('harness/scale/protocol_latch.py', 'transition', '--target', '2', '--out', (Join-Path $dir 'protocol.json')) (Join-Path $dir 'protocol.log')
    if ($prc -ne 0) { throw "P2 전환 실패(rc=$prc)" }
    Start-Sleep -Seconds 10
    $lease0 = Get-Lease
    $x6Rc = Invoke-Pwsh 'harness\scripts\s4-intake-contention.ps1' @('-OutDir', $dir, '-Variants', $X6Variants) (Join-Path $here "$sub-console.log")
    Write-Log "X6 rc=$x6Rc ($sub)"
    $lease1 = Get-Lease
    $code = Invoke-DockerQuiet 'stop chaos-bots'
    $drained = Wait-DrainZero -Sql $Global:CHAOS_DRAIN_SQL -TimeoutSec 120 -RequiredStreak 2
    Start-Sleep -Seconds 16
    $auth = Invoke-Authoritative -Dir $dir -RunId "$sub-post"
    $sum = Invoke-Summary -Dir $dir -Name "summary-$sub"
    $res = [ordered]@{ phase = 'X6'; sub = $sub; variants = $X6Variants; x6Rc = $x6Rc; roles = $roles
        lease = [ordered]@{ before = $lease0; after = $lease1 }; drained = $drained; authoritative = $auth; summary = $sum
        partitions = Get-Partitions; logs = Get-LogCounts; containers = Get-ContainerFacts }
    Save-Json (Join-Path $dir 'result.json') $res
    Write-Log ("X6 {0} rc={1} drained={2} authoritative={3} summary={4}" -f $sub, $x6Rc, $drained, $auth.ok, $sum.ok)
    $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
    Write-Log "down -v (exchange-pml) rc=$code after $sub"
}

function Invoke-Chaos {
    $sub = $(if ($SubDir) { $SubDir } else { 'chaos-' + ($Experiments -replace ',', '') })
    $dir = Join-Path $here $sub
    $env:CHAOS_SPRING_PROFILES = 'dev,chaos-monkey,prod-timeouts'
    Remove-Item Env:CHAOS_EXTRA_COMPOSE -ErrorAction SilentlyContinue
    $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
    Write-Log "down -v (exchange-pml) rc=$code before $sub"
    Assert-NoForeignChaos
    $localLogs = Join-Path ([System.IO.Path]::GetTempPath()) "pml-s4-logs\$sub"
    Write-Log "RUN pwsh -NoProfile -File harness/scripts/adr0022-stage5-chaos.ps1 -Image $Image -Experiments $Experiments -Protocol 2 -PartitionAxes -EvidenceDir $sub -LocalLogDir $localLogs"
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        & pwsh -NoProfile -File (Join-Path $repo 'harness\scripts\adr0022-stage5-chaos.ps1') -Image $Image -Experiments $Experiments `
            -Protocol 2 -PartitionAxes -EvidenceDir $dir -LocalLogDir $localLogs *> (Join-Path $here "$sub-console.log")
        $rc = $LASTEXITCODE
    } finally { $ErrorActionPreference = $eap }
    Write-Log "stage5 rc=$rc ($sub)"
}

# :pml 을 칸의 이미지 id 로 붙인다(설계 7-0). 태그만 옮기고 이미지를 지우지 않는다.
function Set-PmlTag {
    param([string] $ImageId)
    & docker tag $ImageId $Image
    $now = (& docker image inspect $Image --format '{{.Id}}' | Out-String).Trim()
    Write-Log "TAG $Image -> $now"
    if ($now -ne $ImageId) { throw ":pml 태그 이동 실패($now != $ImageId)" }
}

# 호스트 부하 표본(설계 7-2 X7 잡음 규칙). 사용자 PC 에서 다른 프로그램이 함께 돈다. 전체 CPU 사용률 다섯 표본과, 도커·앱·부하 도구가
# 아닌 프로세스 가운데 5초 동안 CPU 를 논리 코어 5% 넘게 쓴 것의 수만 남긴다. 프로세스 이름은 증거에 적지 않는다.
function Get-HostLoad {
    $known = '^(com\.docker\..*|Docker Desktop|dockerd|vmmem.*|vmwp|wslhost|wslservice|java|javaw|python|pwsh|powershell|k6|node|conhost|git|bash|claude.*|Code)$'
    $before = @{}
    foreach ($p in Get-Process) { try { $before[$p.Id] = $p.TotalProcessorTime.TotalSeconds } catch { } }
    $cpu = @()
    try { $cpu = @((Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 5).CounterSamples | ForEach-Object { [math]::Round($_.CookedValue, 1) }) } catch { $cpu = @() }
    $cores = [Environment]::ProcessorCount
    $busyOther = 0
    foreach ($p in Get-Process) {
        if ($p.ProcessName -match $known -or -not $before.ContainsKey($p.Id)) { continue }
        try { $used = $p.TotalProcessorTime.TotalSeconds - $before[$p.Id] } catch { continue }
        if ($used / 5.0 / $cores -gt 0.05) { $busyOther++ }
    }
    return [ordered]@{ at = (Get-Date).ToUniversalTime().ToString('o'); cpuTotalPct = $cpu; logicalCores = $cores; otherBusyProcesses = $busyOther }
}

function Invoke-X7 {
    $dir = Join-Path $here 'X7'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $images = Get-Content $imagesPath -Raw -Encoding utf8 | ConvertFrom-Json
    Remove-Item Env:CHAOS_SPRING_PROFILES -ErrorAction SilentlyContinue
    # containerd 이미지 저장소에서는 이름을 모두 잃은 이미지가 사라진다. :pml 을 옮기기 전에 두 이미지에 참조 태그가 붙어 있는지 본다.
    foreach ($ref in @(@('exchange-chaos-app:s4r1ref', $images.imageId), @('exchange-chaos-app:s3ref', $images.s3RefImageId))) {
        if (-not $ref[1]) { continue }
        $refId = (& docker image inspect $ref[0] --format '{{.Id}}' 2>$null | Out-String).Trim()
        if ($refId -ne $ref[1]) { throw "참조 태그 $($ref[0]) 가 $($ref[1]) 를 가리키지 않는다($refId)" }
    }
    $env:CHAOS_EXTRA_COMPOSE = $histOverlay
    $env:SCALE_LOAD_KEYS = '16'
    Remove-Item Env:SCALE_MARKETS -ErrorAction SilentlyContinue
    try {
        foreach ($cell in ($Cells.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })) {
            # S3 이미지는 S4 를 구울 때 사라져(containerd 이미지 저장소) S3 커밋으로 다시 구운 참조 이미지(s3RefImageId)를 쓴다.
            $s3Id = $(if ($images.s3RefImageId) { $images.s3RefImageId } else { $images.s3ImageId })
            $imageId = $(if ($cell -like 'S3*') { $s3Id } elseif ($cell -like 'S4*') { $images.imageId } else { throw "칸 이름은 S3*·S4* 다: $cell" })
            $out = Join-Path $dir "$cell-$Rate.json"
            if (Test-Path $out) { Write-Log "SKIP $cell-$Rate (이미 있다)"; continue }
            $code = Invoke-ComposeQuiet '--profile obs --profile admin --profile load down -v --remove-orphans'
            Assert-NoForeignChaos
            Set-PmlTag $imageId
            $hostBefore = Get-HostLoad
            $lbFrom = [DateTimeOffset]::UtcNow
            $argList = @('harness/scale/roundtrip_probe.py', '--label', $cell, '--rate', "$Rate", '--out', $out, '--protocol-before-traffic', '2')
            Write-Log "PROBE $cell rate=$Rate image=$imageId keys=$env:SCALE_LOAD_KEYS hostCpu=[$($hostBefore.cpuTotalPct -join ',')] otherBusy=$($hostBefore.otherBusyProcesses)"
            $prc = Invoke-Py $argList (Join-Path $dir "probe-$cell-$Rate.log")
            $hostAfter = Get-HostLoad
            # nginx 의 5xx 는 LB 로그에서 센다(리더·팔로워 5xx 는 노드 계수기의 상태별 값이다).
            $lbLines = @(& cmd /c "docker logs --since $($lbFrom.ToUnixTimeSeconds()) chaos-lb 2>&1")
            $lb = [ordered]@{ lines = $lbLines.Count
                status502 = @($lbLines | Select-String -SimpleMatch '" 502 ').Count
                status503 = @($lbLines | Select-String -SimpleMatch '" 503 ').Count
                status504 = @($lbLines | Select-String -SimpleMatch '" 504 ').Count
                noLiveUpstreams = @($lbLines | Select-String -SimpleMatch 'no live upstreams').Count }
            $cellRes = [ordered]@{ cell = $cell; rate = $Rate; imageId = $imageId; probeRc = $prc; file = (Split-Path $out -Leaf)
                host = [ordered]@{ before = $hostBefore; after = $hostAfter }; lb = $lb
                containers = Get-ContainerFacts; nodeMetrics = Get-NodeMetrics; logs = Get-LogCounts }
            $code = Invoke-DockerQuiet 'stop chaos-bots'
            $cellRes['drained'] = Wait-DrainZero -Sql $Global:CHAOS_DRAIN_SQL -TimeoutSec 120 -RequiredStreak 2
            $cellRes['summary'] = Invoke-Summary -Dir $dir -Name "summary-$cell-$Rate"
            Save-Json (Join-Path $dir "cell-$cell-$Rate.json") $cellRes
        }
    } finally {
        $code = Invoke-ComposeQuiet '--profile obs --profile admin --profile load down -v --remove-orphans'
        Write-Log "down -v (exchange-pml) rc=$code after X7"
        Set-PmlTag $images.imageId
        Remove-Item Env:CHAOS_EXTRA_COMPOSE -ErrorAction SilentlyContinue
    }
}

function Invoke-X8 {
    foreach ($exp in ($X8Experiments.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })) {
        $x8Sub = $(if ($SubDir) { $SubDir } else { 'X8' })
        $dir = Join-Path $here "$x8Sub\$exp"
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $env:CHAOS_SPRING_PROFILES = 'dev,chaos-monkey,prod-timeouts'
        Remove-Item Env:CHAOS_EXTRA_COMPOSE -ErrorAction SilentlyContinue
        $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
        Assert-NoForeignChaos
        $code = Invoke-ComposeQuiet '--profile obs up -d --wait'
        Write-Log "up --profile obs rc=$code ($exp)"
        if ($code -ne 0) { throw "compose up 실패 rc=$code" }
        $prc = Invoke-Py @('harness/scale/protocol_latch.py', 'transition', '--target', '2', '--out', (Join-Path $dir 'protocol.json')) (Join-Path $dir 'protocol.log')
        if ($prc -ne 0) { throw "P2 전환 실패(rc=$prc)" }
        Start-Sleep -Seconds 10
        $n0 = Get-NodeMetrics
        $script = $(switch ($exp) { 'E1' { 'harness\scripts\e1-pg-latency.ps1' } 'E3' { 'harness\scripts\e3-redis-outage.ps1' } default { throw "X8 실험은 E1·E3 이다: $exp" } })
        $erc = Invoke-Pwsh $script @() (Join-Path $dir 'console.log')
        $n1 = Get-NodeMetrics
        $runId = $null
        $hit = Select-String -Path (Join-Path $dir 'console.log') -Pattern 'runId=((?:E\d)-\d{8}-\d{6})' | Select-Object -First 1
        if ($hit) { $runId = $hit.Matches[0].Groups[1].Value }
        $verdict = $null
        if ($runId -and (Test-Path (Join-Path $env:CHAOS_EVIDENCE_DIR "$runId.json"))) {
            Copy-Item (Join-Path $env:CHAOS_EVIDENCE_DIR "$runId.json") (Join-Path $dir 'verdict.json') -Force
            $verdict = Get-Content (Join-Path $dir 'verdict.json') -Raw -Encoding utf8 | ConvertFrom-Json
        }
        $delta = Get-MetricDelta $n0 $n1
        $sumOrders = { param($d, $pat) $t = 0.0; foreach ($p in '18080', '28080') { foreach ($k in $d[$p].Keys) { if ($k -like $pat) { $t += $d[$p][$k] } } }; $t }
        $res = [ordered]@{ experiment = $exp; rc = $erc; runId = $runId; verdict = $(if ($verdict) { $verdict.verdict } else { $null })
            demotions = (& $sumOrders $delta 'exchange_partition_demotions_total*')
            acquireLockTimeouts = (& $sumOrders $delta 'exchange_leader_lease_acquire_lock_timeouts_total')
            contention = [ordered]@{ '18080' = @($delta['18080'].Keys | Where-Object { $_ -like 'exchange_order_intake_contention_total*' } | ForEach-Object { "$_=$($delta['18080'][$_])" })
                '28080' = @($delta['28080'].Keys | Where-Object { $_ -like 'exchange_order_intake_contention_total*' } | ForEach-Object { "$_=$($delta['28080'][$_])" }) }
            partitions = Get-Partitions; logs = Get-LogCounts }
        # 컨테이너 로그 원문은 저장소 밖에 두고 해시만 남긴다(2026-09-25 재주행에서 더함. 첫 X8 E1 FAIL 의 원인 로그가 스택과 함께 사라졌다).
        $logDir = Join-Path ([System.IO.Path]::GetTempPath()) "pml-s4-logs\$x8Sub-$exp"
        New-Item -ItemType Directory -Force -Path $logDir | Out-Null
        $hashes = [ordered]@{}
        foreach ($c in 'chaos-api-1', 'chaos-api-2', 'chaos-bots', 'chaos-lb') {
            $lp = Join-Path $logDir "$c.log"
            & cmd /c "docker logs $c > `"$lp`" 2>&1"
            $hashes[$c] = [ordered]@{ path = $lp; sha256 = (Get-FileHash $lp -Algorithm SHA256).Hash.ToLower(); bytes = (Get-Item $lp).Length }
        }
        $res['containerLogs'] = $hashes
        Save-Json (Join-Path $dir 'result.json') $res
        Write-Log ("X8 {0} rc={1} verdict={2} demotions={3} acquireLockTimeouts={4}" -f $exp, $erc, $res.verdict, $res.demotions, $res.acquireLockTimeouts)
        $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
        Write-Log "down -v (exchange-pml) rc=$code after X8 $exp"
    }
}

Write-Log "PHASE $Phase project=$resolved repo=$repo image=$Image"
Test-PreservedVolumes 'before'
try {
    switch ($Phase) {
        'Prep' {
            Assert-NoForeignChaos
            # 지금 :pml 은 수정 전 S4 이미지(참조 태그 s4ref)다. 기록만 하고, X7 의 S3 칸은 참조 태그 s3ref 로 돈다.
            $s3 = (& docker image inspect $Image --format '{{.Id}}|{{.Created}}' | Out-String).Trim().Split('|')
            Write-Log "previous :pml (pre-review S4) before build: $($s3[0]) created $($s3[1])"
            $s3ref = (& docker image inspect exchange-chaos-app:s3ref --format '{{.Id}}' | Out-String).Trim()
            $s4ref = (& docker image inspect exchange-chaos-app:s4ref --format '{{.Id}}' | Out-String).Trim()
            if ($s4ref -ne $s3[0]) { throw "s4ref($s4ref) 가 지금 :pml($($s3[0])) 과 달라 앞 이미지를 잃을 수 있다" }
            Write-Log "s3ref=$s3ref s4ref=$s4ref"
            Save-Text (Join-Path $here 'docker-ps-before.txt') ((& docker ps -a --format '{{.Names}} {{.Image}} {{.Status}} {{.Label "com.docker.compose.project"}}' | Out-String))
            $env:CHAOS_SPRING_PROFILES = 'dev,chaos-monkey,prod-timeouts'
            $urc = Invoke-Pwsh 'harness\scripts\up.ps1' @('-Obs') (Join-Path $here 'prep-up.log')
            if ($urc -ne 0) { throw "up.ps1 실패(rc=$urc)" }
            $img = (& docker image inspect $Image --format '{{.Id}}|{{.Created}}' | Out-String).Trim().Split('|')
            $head = (& git -C $repo rev-parse HEAD | Out-String).Trim()
            $status = (& git -C $repo status --porcelain | Out-String)
            $diffHash = ((& git -C $repo diff HEAD | Out-String) | ForEach-Object { [System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::HashData([System.Text.Encoding]::UTF8.GetBytes($_))).Replace('-', '').ToLower() })
            $jar = Join-Path $repo 'harness\app.jar'
            $flyway = Get-FlywayState
            & docker tag $img[0] exchange-chaos-app:s4r1ref
            Write-Log "TAG exchange-chaos-app:s4r1ref -> $($img[0])"
            $images = [ordered]@{ tag = $Image; imageId = $img[0]; created = $img[1]; refTag = 'exchange-chaos-app:s4r1ref'
                previousS4ImageId = $s3[0]; previousS4Created = $s3[1]; previousS4RefTag = 'exchange-chaos-app:s4ref'
                s3RefImageId = $s3ref; s3RefTag = 'exchange-chaos-app:s3ref'
                worktree = $repo; head = $head; trackedDiffSha256 = $diffHash; changedPaths = @($status -split "`r?`n" | Where-Object { $_ }).Count
                jarSha256 = (Get-FileHash $jar -Algorithm SHA256).Hash.ToLower(); jarWritten = (Get-Item $jar).LastWriteTime.ToString('o')
                flyway = $flyway
                note = 'S4 는 이 단계에서 커밋하지 않는다. 이미지는 HEAD 위 작업 트리 변경(구현 검토 라운드 1 수정 포함)을 담는다. X7 의 S3 칸은 s3ref(S3 커밋 60be79e0 을 다시 구운 이미지)다' }
            Save-Json $imagesPath $images
            Write-Log "images.json s4=$($img[0]) previousS4=$($s3[0]) s3ref=$s3ref head=$head diff=$diffHash flyway_max=$($flyway.max_version)"
            $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
            Write-Log "down -v (exchange-pml) rc=$code after build"
        }
        'X1' { Invoke-X1 }
        'X6' { Invoke-X6Only }
        'Chaos' { Invoke-Chaos }
        'X7' { Invoke-X7 }
        'X8' { Invoke-X8 }
        'Final' {
            $code = Invoke-ComposeQuiet '--profile obs --profile admin --profile load down -v --remove-orphans'
            Write-Log "final down -v (exchange-pml) rc=$code"
            if (Test-Path $imagesPath) { Set-PmlTag ((Get-Content $imagesPath -Raw -Encoding utf8 | ConvertFrom-Json).imageId) }
            Save-Text (Join-Path $here 'docker-ps-after.txt') ((& docker ps -a --format '{{.Names}} {{.Image}} {{.Status}} {{.Label "com.docker.compose.project"}}' | Out-String))
            Save-Text (Join-Path $here 'volume-ls-end.txt') ((& docker volume ls --format '{{.Name}}' | Where-Object { $_ -like 'exchange-pml*' } | Out-String))
        }
    }
} finally {
    Test-PreservedVolumes 'after'
}
