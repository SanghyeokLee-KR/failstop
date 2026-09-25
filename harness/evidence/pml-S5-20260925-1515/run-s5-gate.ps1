# S5 관문 폐기용 스택 주행(docs/reference/architecture/마켓분할_리더_S5_설계.md 7절). compose 프로젝트 exchange-pml, 앱 이미지
# exchange-chaos-app:pml 에서만 돈다. pml-S4b-20260925-0940 의 run-s4b-gate.ps1 을 복사해 설계 7-1 의 다섯 곳을 고쳤다.
#   Prep  : 지금 :pml 이 S4b 보정 이미지(참조 태그 s4b3ref)인지 보고, up.ps1 -Obs 로 S5 이미지를 구워 참조 태그 s5ref 를 붙인다.
#           images.json 에 새 이미지 id, HEAD, 작업 트리 diff 해시, JAR 해시, Flyway 최대 판(54)을 남긴다.
#   Chaos : harness/scripts/adr0022-stage5-chaos.ps1 -Protocol 2 -PartitionAxes. -SubDir 가 필요하다(Z0 은 E4 기본 LB, Z5 는
#           E4·E7·E2 파티션 LB). 컨테이너 로그는 %TEMP%\pml-s5-logs\<하위 폴더> 에 두고, 저장한 LB 로그에서 장애 창 안 5xx 와
#           가장 긴 요청 시간(rt, 파티션 형식일 때), s3_common.json 의 S5 계수기를 붙인다.
#   Z1    : 라우팅 분할(파티션 LB, P2, 봇 300 OPS, k6 order_flow.js 3마켓 MARKET_HINT=1 을 -K6Runs 번). LB 로그를 힌트·첫 시도
#           업스트림·상태 목록으로 나눠 판정한다(harness/scale/s5_route_probe.py lbsplit).
#   Z1d   : (설계 밖 보충) 봇을 멈춘 파티션 LB 스택에서 LB 로 여섯 요청을 보내 힌트 맵·기본 업스트림·조회 경로를 실제 요청으로
#           본다. s5_route_probe.py zroute
#   Z2    : 선검사 무부작용(봇 정지, 팔로워와 chaos-api-3 직접 호출). s5_route_probe.py z2
#   Z3    : 힌트·배치 거절(봇 정지, 리더 직접 호출). s5_route_probe.py z3
#   Z4    : 인계(봇 정지, 팔로워·chaos-api-3 의 일괄 취소, chaos-admin 폐쇄, 참여를 끈 뒤의 폐쇄). s5_route_probe.py z4
#   B1    : S4b B1 과 같은 DB 흔들림 기록 실험(Z6). 파티션 LB 에서 리더 pg 프록시에 reset_peer 500ms 를 60초 간격으로 세 번
#   WsRace: (설계 밖 대조) 봇 300 OPS 아래 ws_subscribe_race.js 로 짧은 orderbook 구독을 되풀이해 결번을 센다. -RaceRef 를 주면
#           :pml 을 그 참조 태그 이미지로 옮겨 돌고 끝나면 S5 이미지로 되돌린다
#   Final : 스택을 -p exchange-pml 로 지우고 :pml 이 S5 이미지인지 보고 보존 볼륨을 다시 확인한다
# 모든 단계는 -LbConf default|partition 을 받는다. partition 이면 이 프로세스의 환경 변수 CHAOS_LB_CONF 에 파티션 파일을 준다
# (파일에 쓰지 않는다. 셸 변수가 --env-file 보다 우선한다). 스택을 세운 단계는 직후 docker exec chaos-lb nginx -t 와 컨테이너
# 안 설정 파일의 sha256 을 저장소 파일과 대조해 lb.json 에 남긴다. 실험 앞뒤로 호스트 부하를 남긴다(설계 7-3).
# 사용: pwsh -NoProfile -File run-s5-gate.ps1 -Phase <단계> [-LbConf partition] [옵션]
# 보존 스택과 보존 볼륨을 건드리지 않도록 프로젝트를 네 곳(셸 변수 둘, lib.ps1, compose 해석값)에서 확인한 뒤 시작하고, 단계마다
# 앞뒤로 보존 볼륨 생성 시각을 volumes.txt 에 남겨 바뀌면 멈춘다. 해석된 compose 설정 전체는 비밀 값을 담으므로 남기지 않는다.
# 비밀 파일(harness/.env.chaos, harness/load/secrets/k6_keys.json)은 경로로만 넘긴다. k6 로그는 접근 키 식별자를 담아 저장소 밖
# (%TEMP%\pml-s5-logs)에 두고 해시와 임계 줄만 남긴다. 태그를 잃은 이미지를 지우는 명령(docker image prune)은 쓰지 않는다.
# 이 실행기는 Gradle 빌드·Testcontainers 시험과 함께 돌리지 않는다(설계 7-0).
param(
    [Parameter(Mandatory = $true)][ValidateSet('Prep', 'Chaos', 'Z1', 'Z1d', 'Z2', 'Z3', 'Z4', 'B1', 'WsRace', 'Final')][string] $Phase,
    [ValidateSet('default', 'partition')][string] $LbConf = 'default',
    [string] $Image = 'exchange-chaos-app:pml',
    [string] $Experiments = 'E4',
    [string] $SubDir = '',
    [int] $BotOps = 300,
    [int] $K6Runs = 4,
    [int] $WarmupSec = 60,
    [int] $B1Rounds = 3,
    [int] $B1ResetMs = 500,
    [int] $B1IntervalSec = 60,
    [int] $B1WarmupSec = 120,
    [string] $RaceRef = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONIOENCODING = 'utf-8'
if ($Image -ne 'exchange-chaos-app:pml') { throw "이미지 태그는 exchange-chaos-app:pml 만 쓴다: $Image" }
$env:CHAOS_PROJECT = 'exchange-pml'
$env:COMPOSE_PROJECT_NAME = 'exchange-pml'
$env:CHAOS_APP_IMAGE = $Image
if ($LbConf -eq 'partition') { $env:CHAOS_LB_CONF = './nginx/nginx.partition.conf' } else { Remove-Item Env:CHAOS_LB_CONF -ErrorAction SilentlyContinue }
$here = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $here '..\..\..')).Path
$env:CHAOS_EVIDENCE_DIR = Join-Path $here 'chaos-runs'
. (Join-Path $repo 'harness\scripts\lib.ps1')
if ($Global:CHAOS.Project -ne 'exchange-pml') { throw "프로젝트 고정 실패: $($Global:CHAOS.Project)" }
if ($env:CHAOS_PROJECT -ne 'exchange-pml' -or $env:COMPOSE_PROJECT_NAME -ne 'exchange-pml') { throw '셸 변수 고정 실패' }
$resolved = (Invoke-Compose config --format json | ConvertFrom-Json).name
if ($resolved -ne 'exchange-pml') { throw "compose 프로젝트 해석이 exchange-pml 이 아니다: $resolved" }

$log = Join-Path $here 'commands.log'
$expectedVolumes = [ordered]@{ 'exchange-chaos_chaos-pgdata' = '2026-09-13T11:42:01Z'; 'failstop-keep-chaos-pgdata-20260924' = '2026-09-23T15:47:36Z' }
$imagesPath = Join-Path $here 'images.json'
$logRoot = Join-Path ([System.IO.Path]::GetTempPath()) 'pml-s5-logs'
$lbFile = $(if ($LbConf -eq 'partition') { 'nginx.partition.conf' } else { 'nginx.conf' })

function Write-Log {
    param([string] $Text)
    $line = '{0} [{1}/{2}] {3}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'), $Phase, $LbConf, $Text
    Add-Content -Path $log -Value $line -Encoding utf8
    Write-Host $line
}
function Save-Text { param([string] $Path, [string] $Text) [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false)) }
function Save-Json { param([string] $Path, $Obj) Save-Text $Path ($Obj | ConvertTo-Json -Depth 16) }

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
    if ($foreign.Count -gt 0) { throw ('BLOCKED: 다른 프로젝트의 chaos- 컨테이너가 있다: {0}' -f ($foreign -join ', ')) }
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

# 노드 actuator 에서 이름이 맞는 계열을 읽는다. 누적 카운터라 창의 값은 전후 차다. S5 계수기 여섯은 라벨을 모두 키에 싣는다.
$nodeMetricNames = @(
    'exchange_settlement_insufficient_funds_total', 'exchange_settlement_lock_plan_miss_total',
    'exchange_settlement_transient_retries_total', 'exchange_settlement_transient_exhausted_total',
    'exchange_order_reserve_rejected_total', 'exchange_order_append_transient_total',
    'exchange_order_intake_contention_total', 'exchange_partition_demotions_total',
    'exchange_leader_lease_acquire_lock_timeouts_total', 'exchange_partition_state', 'exchange_admission_rejected_total',
    'http_server_requests_seconds_count',
    'exchange_order_intake_unavailable_total', 'exchange_order_intake_unresolved_total',
    'exchange_order_intake_commit_recovered_total', 'exchange_partition_rebuild_seconds_count')
$s5MetricNames = @('exchange_routing_precheck_total', 'exchange_routing_cancel_not_owner_total',
    'exchange_routing_hint_rejected_total', 'exchange_db_read_retry_total',
    'exchange_order_cancel_handoff_requests_total', 'exchange_order_cancel_handoff_worker_total')
function Get-NodeMetrics {
    param([int[]] $Ports = @(18080, 28080))
    $res = [ordered]@{}
    foreach ($p in $Ports) {
        $m = [ordered]@{ ok = $false }
        try {
            $t = (Invoke-WebRequest "http://localhost:$p/actuator/prometheus" -TimeoutSec 10 -UseBasicParsing).Content
            $m.ok = $true
            foreach ($line in ($t -split "`n")) {
                if ($line -match '^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(.*)\})?\s+(\S+)$') {
                    $name = $Matches[1]; $labels = $Matches[3]; $v = [double]$Matches[4]
                    if ($s5MetricNames -contains $name) {
                        $pairs = @([regex]::Matches("$labels", '(\w+)="([^"]*)"') | Where-Object { $_.Groups[1].Value -ne 'application' } |
                            ForEach-Object { '{0}={1}' -f $_.Groups[1].Value, $_.Groups[2].Value } | Sort-Object)
                        $key = "$name{$($pairs -join ',')}"
                        $m[$key] = $(if ($m.Contains($key)) { $m[$key] + $v } else { $v })
                        continue
                    }
                    if ($nodeMetricNames -notcontains $name) { continue }
                    if ($name -eq 'http_server_requests_seconds_count') {
                        $st = $(if ($labels -match 'status="(\d+)"') { $Matches[1] } else { 'x' })
                        $me = $(if ($labels -match 'method="([A-Z]+)"') { $Matches[1] } else { 'x' })
                        $ur = $(if ($labels -match 'uri="([^"]*)"') { $Matches[1] } else { 'x' })
                        if ($st -like '5*') {
                            $k5 = "http_${me}_${st}_$ur"
                            $m[$k5] = $(if ($m.Contains($k5)) { $m[$k5] + $v } else { $v })
                        }
                        if ($ur -ne '/api/v1/orders' -or $me -ne 'POST') { continue }
                        $key = "orders_post_$st"
                    }
                    elseif ($name -eq 'exchange_order_intake_unavailable_total' -and $labels -match 'cause="([^"]+)".*response="([^"]+)"') { $key = "$name{$($Matches[1])|$($Matches[2])}" }
                    elseif ($name -eq 'exchange_partition_rebuild_seconds_count' -and $labels -match 'outcome="([a-z]+)"') { $key = "$name{outcome=$($Matches[1])}" } elseif ($labels -match 'sqlstate="([0-9A-Z]+)"') { $key = "$name{sqlstate=$($Matches[1])}" }
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
    foreach ($p in $After.Keys) {
        $a = $After[$p]; $b = $Before[$p]
        $x = [ordered]@{}
        if ($a.ok -and $b -and $b.ok) {
            foreach ($k in $a.Keys) {
                if ($k -in 'ok', 'error') { continue }
                $dv = $a[$k] - $(if ($b.Contains($k)) { $b[$k] } else { 0 })
                if ($dv -ne 0) { $x[$k] = $dv }
            }
        } else { $x['error'] = '긁기 실패' }
        $d[$p] = $x
    }
    return $d
}

function Get-Sum {
    param($Delta, [string] $Pattern, [string[]] $Ports = @('18080', '28080'))
    $t = 0.0
    foreach ($p in $Ports) { if ($Delta[$p]) { foreach ($k in $Delta[$p].Keys) { if ($k -like $Pattern) { $t += $Delta[$p][$k] } } } }
    return $t
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

# P2 의 리더 컨테이너(게이트를 연 노드). 판별하지 못하면 $null 이다.
function Get-LeaderByGate {
    $parts = Get-Partitions
    $acc = @()
    foreach ($p in '18080', '28080') { if (@($parts[$p].partitions | Where-Object { $_.state -eq 'ACCEPTING' }).Count -gt 0) { $acc += $p } }
    if ($acc.Count -ne 1) { return $null }
    return $(if ($acc[0] -eq '18080') { 'chaos-api-1' } else { 'chaos-api-2' })
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
            promotions = @($lines | Select-String -SimpleMatch '리더 승격').Count
            insufficientAtSettlement = @($lines | Select-String -SimpleMatch 'chk_balance_nonneg').Count
            deadlock = @($lines | Select-String -SimpleMatch '40P01').Count
            errorLines = @($lines | Select-String -Pattern ' ERROR ').Count
            unhandledException = @($lines | Select-String -SimpleMatch '처리되지 않은 예외').Count
            authFailure500 = @($lines | Select-String -SimpleMatch '인증 처리 실패로 500').Count
            handoffLeaderRejection = @($lines | Select-String -SimpleMatch '인계 취소가 리더 자격 거부로 끝났다').Count
            handoffAppendOutage = @($lines | Select-String -SimpleMatch '인계 취소의 append 가 DB 연결 장애로 끝났다').Count
            partitionControl = @($lines | Select-String -SimpleMatch '파티션 조작').Count }
    }
    return $res
}

# 저장한 컨테이너 로그 원문에서 문구를 센다. 본문은 남기지 않는다.
$phrases = [ordered]@{ replayNoVerdict = '재생이 판정 없이 끝났다'; rebuildFailed = '파티션 재구성 실패'
    unhandledException = '처리되지 않은 예외'; authFailure500 = '인증 처리 실패로 500'; clientGone = '클라이언트 연결이 끊겨'
    outageResponse = 'DB 연결 장애·리더 자격 거부 응답'; settlementExhausted = '재시도 제한 안에서 흡수하지 못했다'
    loopFatal = '단일 라이터 루프 정지'; fencingKept = 'fencing 거부를 받았으나'; partitionDemotion = '파티션 강등'
    handoffLeaderRejection = '인계 취소가 리더 자격 거부로 끝났다' }
function Get-SavedLogCounts {
    param([string] $Path)
    if (-not $Path -or -not (Test-Path $Path)) { return [ordered]@{ ok = $false } }
    $lines = [System.IO.File]::ReadAllLines($Path, [System.Text.Encoding]::UTF8)
    $res = [ordered]@{ ok = $true; lines = $lines.Count }
    foreach ($k in $phrases.Keys) { $res[$k] = @($lines | Where-Object { $_.Contains($phrases[$k]) }).Count }
    return $res
}

# LB 접근 로그 줄에서 창 안 5xx 와 no live upstreams 를 센다. 시각은 nginx $time_local(초 단위, UTC)이다. 파티션 형식이면
# 가장 긴 요청 시간(rt)과 두 번 시도한 줄 수도 센다.
function Get-LbWindowCounts {
    param([string[]] $Lines, [DateTimeOffset] $From, [DateTimeOffset] $To)
    $res = [ordered]@{ access = 0; partitionFormat = 0; status500 = 0; status502 = 0; status503 = 0; status504 = 0; noLiveUpstreams = 0
        twoAttempts = 0; maxRt = 0.0; first5xx = $null; last5xx = $null }
    $fromS = $From.ToUnixTimeSeconds(); $toS = $To.ToUnixTimeSeconds()
    foreach ($line in $Lines) {
        if ($line -match '\[(\d{2}/\w{3}/\d{4}:\d{2}:\d{2}:\d{2} [+-]\d{4})\] "') {
            $ts = [DateTimeOffset]::ParseExact(($Matches[1] -replace '([+-]\d{2})(\d{2})$', '$1:$2'), 'dd/MMM/yyyy:HH:mm:ss zzz',
                [System.Globalization.CultureInfo]::InvariantCulture)
            $t = $ts.ToUnixTimeSeconds()
            if ($t -lt $fromS -or $t -gt $toS) { continue }
            $res.access++
            if ($line -match ' ups="[^"]*" ust="([^"]*)" .* rt=([0-9.]+)') {
                $res.partitionFormat++
                if ($Matches[1].Contains(',')) { $res.twoAttempts++ }
                $rt = [double]$Matches[2]
                if ($rt -gt $res.maxRt) { $res.maxRt = $rt }
            }
            if ($line -match '" (50[0234]) ') {
                $res["status$($Matches[1])"]++
                if (-not $res.first5xx) { $res.first5xx = $ts.ToString('o') }
                $res.last5xx = $ts.ToString('o')
            }
        } elseif ($line -match '^(\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}) \[error\].*no live upstreams') {
            $ts = [DateTimeOffset]::ParseExact($Matches[1] + ' +00:00', 'yyyy/MM/dd HH:mm:ss zzz', [System.Globalization.CultureInfo]::InvariantCulture)
            $t = $ts.ToUnixTimeSeconds()
            if ($t -ge $fromS -and $t -le $toS) { $res.noLiveUpstreams++ }
        }
    }
    return $res
}

# 세 원천 orders_500_0(S4b 설계 4-7). 판정 파일은 그 폴더의 orders500.json 이다.
function Invoke-Orders500 {
    param([string] $Dir, [DateTimeOffset] $From)
    $out = Join-Path $Dir 'orders500.json'
    $rc = Invoke-Py @('harness/scale/s3_common_axes.py', 'orders500', '--window-from', $From.ToString('o'), '--out', $out) (Join-Path $Dir 'orders500.log')
    $res = $null
    if (Test-Path $out) { $res = Get-Content $out -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable }
    return [ordered]@{ rc = $rc; ok = [bool]($res -and $res['ok']); readable = $(if ($res) { $res['readable'] } else { $null })
        zero = $(if ($res) { $res['zero'] } else { $null }); file = 'orders500.json' }
}

function Get-RecoveryRuns {
    $raw = Invoke-Psql "SELECT json_build_object('success', count(*) FILTER (WHERE matched), 'mismatch', count(*) FILTER (WHERE matched IS DISTINCT FROM true)) FROM recovery_runs"
    return ($raw | ConvertFrom-Json)
}

function Get-ContainerFacts {
    $out = [ordered]@{}
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        foreach ($c in 'chaos-api-1', 'chaos-api-2', 'chaos-api-3', 'chaos-bots', 'chaos-admin') {
            $id = (& docker inspect -f '{{.Image}}' $c 2>$null | Out-String).Trim()
            if (-not $id) { continue }
            $envs = @(& docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' $c 2>$null)
            $out[$c] = [ordered]@{ imageId = $id
                project = (& docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' $c 2>$null | Out-String).Trim()
                profiles = "$($envs | Where-Object { $_ -like 'SPRING_PROFILES_ACTIVE=*' } | Select-Object -First 1)"
                engineEnabled = "$($envs | Where-Object { $_ -like 'EXCHANGE_ENGINE_ENABLED=*' } | Select-Object -First 1)" }
        }
    } finally { $ErrorActionPreference = $eap }
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

# 호스트 부하 표본(S4b 설계 7-3). 전체 CPU 사용률 다섯 표본과, 도커·앱·부하 도구가 아닌 프로세스 가운데 5초 동안 CPU 를 논리
# 코어 5% 넘게 쓴 것의 수만 남긴다. 프로세스 이름은 증거에 적지 않는다.
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
    $others = @(& docker ps --format '{{.Names}}|{{.Label "com.docker.compose.project"}}' | Where-Object { $_ -and $_.Split('|')[1] -ne 'exchange-pml' }).Count
    return [ordered]@{ at = (Get-Date).ToUniversalTime().ToString('o'); cpuTotalPct = $cpu; logicalCores = $cores; otherBusyProcesses = $busyOther
        otherContainers = $others }
}

# LB 설정 확인(설계 7-1 의 2). nginx -t 와 컨테이너 안 설정 파일의 sha256 을 저장소 파일과 대조한다.
function Get-LbFacts {
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $t = (& cmd /c "docker exec chaos-lb nginx -t 2>&1" | Out-String).Trim()
        $rc = $LASTEXITCODE
        $inside = ((& cmd /c "docker exec chaos-lb sha256sum /etc/nginx/nginx.conf 2>nul" | Out-String).Trim() -split '\s+')[0]
    } finally { $ErrorActionPreference = $eap }
    $repoSha = (Get-FileHash (Join-Path $repo "harness\nginx\$lbFile") -Algorithm SHA256).Hash.ToLower()
    $res = [ordered]@{ lbConf = $LbConf; file = "harness/nginx/$lbFile"; nginxT = $t; nginxTRc = $rc; sha256Inside = $inside; sha256Repo = $repoSha
        same = ($inside -eq $repoSha); envChaosLbConf = "$env:CHAOS_LB_CONF" }
    Write-Log ("LB conf={0} nginx-t rc={1} sameSha={2}" -f $lbFile, $rc, $res.same)
    return $res
}

# 새 스택. -p exchange-pml 로 지우고 세운 뒤 LB 설정을 확인하고 P2 로 전환한다.
function Start-Stack {
    param([string] $Dir, [switch] $Scale)
    $env:CHAOS_SPRING_PROFILES = 'dev,chaos-monkey,prod-timeouts'
    Remove-Item Env:CHAOS_EXTRA_COMPOSE -ErrorAction SilentlyContinue
    $code = Invoke-ComposeQuiet '--profile obs --profile scale down -v --remove-orphans'
    Write-Log "down -v (exchange-pml) rc=$code"
    Assert-NoForeignChaos
    $profiles = '--profile obs' + $(if ($Scale) { ' --profile scale' } else { '' })
    $code = Invoke-ComposeQuiet "$profiles up -d --wait"
    Write-Log "up $profiles rc=$code (profiles=$env:CHAOS_SPRING_PROFILES lb=$lbFile)"
    if ($code -ne 0) { throw "compose up 실패 rc=$code" }
    $lb = Get-LbFacts
    Save-Json (Join-Path $Dir 'lb.json') $lb
    if ($lb.nginxTRc -ne 0 -or -not $lb.same) { throw 'LB 설정 확인 실패(nginx -t 또는 설정 해시)' }
    $prc = Invoke-Py @('harness/scale/protocol_latch.py', 'transition', '--target', '2', '--out', (Join-Path $Dir 'protocol.json')) (Join-Path $Dir 'protocol.log')
    if ($prc -ne 0) { throw "P2 전환 실패(rc=$prc)" }
    Start-Sleep -Seconds 10
    return $lb
}

# 봇을 멈추고 드레인한 뒤 공통 판정의 사후 축을 모은다.
function Invoke-CommonTail {
    param([string] $Dir, [string] $Name, [DateTimeOffset] $StackFrom)
    $orders500 = Invoke-Orders500 -Dir $Dir -From $StackFrom
    $code = Invoke-DockerQuiet 'stop chaos-bots'
    $drained = Wait-DrainZero -Sql $Global:CHAOS_DRAIN_SQL -TimeoutSec 120 -RequiredStreak 2
    $stRc = Invoke-Py @('harness/scale/protocol_latch.py', 'state', '--out', (Join-Path $Dir 'state-after-drain.json')) (Join-Path $Dir 'state-after-drain.log')
    $state = Get-Content (Join-Path $Dir 'state-after-drain.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Start-Sleep -Seconds 16
    $auth = Invoke-Authoritative -Dir $Dir -RunId "$Name-post"
    $sum = Invoke-Summary -Dir $Dir -Name "summary-$Name"
    $logs = Get-LogCounts
    $recoveryRuns = Get-RecoveryRuns
    $apiLogs = @('chaos-api-1', 'chaos-api-2')
    $axes = [ordered]@{
        'invariants_summary' = $sum.ok
        'invariants_authoritative' = $auth.ok
        'orders_500_0' = $orders500.ok
        'drained_and_unsettled_0' = ($drained -and [long]$state.unsettled_reservations -eq 0)
        'recovery_runs_mismatch_0' = ([long]$recoveryRuns.mismatch -eq 0)
        'api_logs_no_unhandled_or_auth_500' = (@($apiLogs | Where-Object { $logs[$_].unhandledException + $logs[$_].authFailure500 -gt 0 }).Count -eq 0)
        'no_permission_function_errors' = (@($logs.Keys | Where-Object { $logs[$_].permissionDenied + $logs[$_].sqlstate42501 + $logs[$_].undefinedFunction -gt 0 }).Count -eq 0)
    }
    return [ordered]@{ axes = $axes; orders500 = $orders500; drained = $drained; state = $state; authoritative = $auth; summary = $sum
        logs = $logs; recoveryRuns = $recoveryRuns; botsStopRc = $code }
}

# 컨테이너 로그 원문은 저장소 밖에 두고 해시만 남긴다.
function Save-ContainerLogs {
    param([string] $Sub, [string[]] $Containers = @('chaos-api-1', 'chaos-api-2', 'chaos-bots', 'chaos-lb'))
    $dir = Join-Path $logRoot $Sub
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $hashes = [ordered]@{}
    foreach ($c in $Containers) {
        $exists = (& cmd /c "docker inspect -f {{.Id}} $c 2>nul" | Out-String).Trim()
        if (-not $exists) { continue }
        $lp = Join-Path $dir "$c.log"
        & cmd /c "docker logs $c > `"$lp`" 2>&1"
        $hashes[$c] = [ordered]@{ path = $lp; sha256 = (Get-FileHash $lp -Algorithm SHA256).Hash.ToLower(); bytes = (Get-Item $lp).Length }
    }
    return $hashes
}

function New-PhaseDir {
    param([string] $Default)
    $dir = Join-Path $here $(if ($SubDir) { $SubDir } else { $Default })
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    if (Test-Path (Join-Path $dir 'result.json')) { throw "이미 $dir 에 결과가 있다. 덮지 않는다" }
    return $dir
}

function Get-S5Axes {
    param($Delta, [string] $Leader, [bool] $LeaseSame, [switch] $AllowHintRejects)
    $leaderPort = $(if ($Leader -eq 'chaos-api-1') { '18080' } else { '28080' })
    $axes = [ordered]@{}
    if (-not $AllowHintRejects) { $axes['hint_rejected_0_both'] = ((Get-Sum $Delta 'exchange_routing_hint_rejected_total*') -eq 0) }
    if ($LeaseSame) { $axes['leader_precheck_not_owner_0'] = ((Get-Sum $Delta 'exchange_routing_precheck_total{*result=not_owner*' @($leaderPort)) -eq 0) }
    return $axes
}

function Invoke-Z1 {
    $dir = New-PhaseDir 'Z1'
    $hostBefore = Get-HostLoad
    $stackFrom = [DateTimeOffset]::UtcNow
    $lb = Start-Stack -Dir $dir
    $leader = Get-LeaderByGate
    if (-not $leader) { throw '리더 판별 실패' }
    $follower = $(if ($leader -eq 'chaos-api-1') { 'chaos-api-2' } else { 'chaos-api-1' })
    $krc = Invoke-Py @('harness/scale/s5_route_probe.py', 'keys', '--markets', 'KRW-BTC,KRW-ETH,KRW-XRP', '--count', '4', '--out', (Join-Path $dir 'keys.json')) (Join-Path $dir 'keys.log')
    if ($krc -ne 0) { throw "부하 계정 발급 실패(rc=$krc)" }
    $prePartRc = Invoke-Py @('harness/scale/s4_partition_axes.py', 'snapshot', '--out', (Join-Path $dir 'partition_pre.json')) (Join-Path $dir 'partition_pre.log')
    $axesFrom = [DateTimeOffset]::UtcNow
    Invoke-Psql "INSERT INTO sim_commands (action, payload, requested_by) VALUES ('target_ops', '$BotOps', 'run-s5-gate')" | Out-Null
    Write-Log "Z1 leader=$leader bots target_ops=$BotOps warmup ${WarmupSec}s"
    Start-Sleep -Seconds $WarmupSec
    $leaf = Split-Path $dir -Leaf
    $lease0 = Get-Lease
    # 계수 긁기 앞뒤 시각. LB 로그 시각은 초 단위라 경계 초의 줄은 긁기 앞인지 뒤인지 모른다. 판정은 경계 초를 뺀 안쪽 창과 넣은
    # 바깥 창 사이에 계수 증가가 드는지 본다(첫 주행 Z1 의 경계 한 줄 차이 뒤 고친 판정).
    $a0 = [DateTimeOffset]::UtcNow
    $n0 = Get-NodeMetrics
    $b0 = [DateTimeOffset]::UtcNow
    $pg0 = Get-PgCounters
    $since = Get-DbNow
    $t0 = [DateTimeOffset]::UtcNow
    $k6Dir = Join-Path $logRoot "$leaf-k6"
    New-Item -ItemType Directory -Force -Path $k6Dir | Out-Null
    $runs = @()
    $samples = @()
    for ($i = 1; $i -le $K6Runs; $i++) {
        $rid = 'z1r{0}{1}' -f $i, (Get-Date -Format 'HHmmss')
        $kl = Join-Path $k6Dir "$rid.log"
        $k6Args = @('run', '--rm', '--network', 'exchange-pml_chaos-net', '-v', "$(Join-Path $repo 'harness\load'):/load:ro",
            '-e', 'BASE_URL=http://chaos-lb:8080', '-e', 'K6_KEYS=/load/secrets/k6_keys.json', '-e', 'MARKETS=KRW-BTC,KRW-ETH,KRW-XRP',
            '-e', 'MARKET_HINT=1', '-e', 'PROFILE=smoke', '-e', 'STEADY_RATE=5', '-e', 'SPIKE_RATE=10', '-e', 'READ_RATE=5',
            '-e', 'WS_VUS=10', '-e', "RUN_ID=$rid", '-e', 'API_HOST=api.exchange.local',
            'grafana/k6:1.4.0', 'run', '--quiet', '--no-color', '/load/order_flow.js')
        Write-Log "RUN docker run k6 order_flow.js RUN_ID=$rid (PROFILE=smoke STEADY 5 SPIKE 10 WS_VUS 10, 3 markets, MARKET_HINT=1)"
        $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        try { & docker @k6Args *> $kl; $rc = $LASTEXITCODE } finally { $ErrorActionPreference = $eap }
        $text = [System.IO.File]::ReadAllLines($kl, [System.Text.Encoding]::UTF8)
        # 요약의 THRESHOLDS 절만 남긴다(지표 이름 줄과 통과·실패 표시 줄). ACK 줄은 접근 키 식별자를 담아 남기지 않는다.
        # k6 의 통과·실패 기호는 저장소 문체 규칙(기호 금지) 때문에 [통과]·[실패]로 바꿔 남긴다.
        $thr = @()
        $inThr = $false
        foreach ($ln in $text) {
            if ($ln -match 'THRESHOLDS') { $inThr = $true; continue }
            if ($inThr -and $ln.Contains([string][char]0x2588)) { break }
            if ($inThr -and $ln.Trim() -and $ln -notmatch 'ACK\|') { $thr += $ln.Trim().Replace([string][char]0x2713, '[통과]').Replace([string][char]0x2717, '[실패]') }
        }
        $runs += [ordered]@{ runId = $rid; rc = $rc; log = $kl; sha256 = (Get-FileHash $kl -Algorithm SHA256).Hash.ToLower(); thresholdLines = $thr
            failedThresholds = @($thr | Where-Object { $_.StartsWith('[실패]') }) }
        $l = Get-Lease
        $samples += [ordered]@{ at = [DateTimeOffset]::UtcNow.ToString('o'); epoch = $l.epoch; holder = $l.holder; alive = $l.alive }
        Write-Log "k6 $rid rc=$rc failedThresholds=$(@($thr | Where-Object { $_.StartsWith('[실패]') }).Count)"
    }
    $t1 = [DateTimeOffset]::UtcNow
    Start-Sleep -Seconds 3
    $c1 = [DateTimeOffset]::UtcNow
    $n1 = Get-NodeMetrics
    $d1 = [DateTimeOffset]::UtcNow
    $pg1 = Get-PgCounters
    $until = Get-DbNow
    $lease1 = Get-Lease
    $lbLog = Join-Path $logRoot "$leaf-chaos-lb.log"
    & cmd /c "docker logs chaos-lb > `"$lbLog`" 2>&1"
    $sRc = Invoke-Py @('harness/scale/s5_route_probe.py', 'lbsplit', '--log', $lbLog, '--from', $t0.AddSeconds(-1).ToString('o'),
        '--to', $t1.AddSeconds(3).ToString('o'), '--leader', $leader, '--out', (Join-Path $dir 'lbsplit.json')) (Join-Path $dir 'lbsplit.log')
    $split = Get-Content (Join-Path $dir 'lbsplit.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
    # 안쪽 창: 긁기 뒤 초부터 다음 긁기 앞 초까지(그 줄의 팔로워 시도는 반드시 두 긁기 사이다). 바깥 창: 첫 긁기 시작 초부터 둘째
    # 긁기 끝 다음 초까지(두 긁기 사이의 팔로워 시도는 반드시 이 안에 적힌다. 요청 시간은 1초보다 짧다).
    $sec = { param($t) [DateTimeOffset]::FromUnixTimeSeconds($t.ToUnixTimeSeconds()) }
    $innerFrom = (& $sec $b0).AddSeconds(2); $innerTo = (& $sec $c1).AddSeconds(-1)
    $outerFrom = (& $sec $a0); $outerTo = (& $sec $d1).AddSeconds(1)
    $iRc = Invoke-Py @('harness/scale/s5_route_probe.py', 'lbsplit', '--log', $lbLog, '--from', $innerFrom.ToString('o'),
        '--to', $innerTo.ToString('o'), '--leader', $leader, '--out', (Join-Path $dir 'lbsplit-inner.json')) (Join-Path $dir 'lbsplit-inner.log')
    $oRc = Invoke-Py @('harness/scale/s5_route_probe.py', 'lbsplit', '--log', $lbLog, '--from', $outerFrom.ToString('o'),
        '--to', $outerTo.ToString('o'), '--leader', $leader, '--out', (Join-Path $dir 'lbsplit-outer.json')) (Join-Path $dir 'lbsplit-outer.log')
    $inner = [long](Get-Content (Join-Path $dir 'lbsplit-inner.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable)['total']['order_write_first_follower_503']
    $outer = [long](Get-Content (Join-Path $dir 'lbsplit-outer.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable)['total']['order_write_first_follower_503']
    $postPartRc = Invoke-Py @('harness/scale/s4_partition_axes.py', 'snapshot', '--out', (Join-Path $dir 'partition_post.json')) (Join-Path $dir 'partition_post.log')
    $axesRc = Invoke-Py @('harness/scale/s4_partition_axes.py', 'judge', '--window-from', $axesFrom.ToString('o'), '--lb-gate',
        '--pre', (Join-Path $dir 'partition_pre.json'), '--post', (Join-Path $dir 'partition_post.json'),
        '--out', (Join-Path $dir 'partition_axes.json')) (Join-Path $dir 'partition_axes.log')
    $axesS4 = Get-Content (Join-Path $dir 'partition_axes.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
    $trades = [long](Invoke-Psql "SELECT count(*) FROM trades WHERE created_at > '$since'::timestamp AT TIME ZONE 'UTC'")
    $tail = Invoke-CommonTail -Dir $dir -Name $leaf -StackFrom $stackFrom
    $window = Get-MetricDelta $n0 $n1
    $fPort = $(if ($follower -eq 'chaos-api-1') { '18080' } else { '28080' })
    $followerNotOwner = Get-Sum $window 'exchange_routing_precheck_total{*result=not_owner*' @($fPort)
    $leaderPart = $(if ($split['partitions']['exchange_p0']['primary'] -eq $leader) { 'exchange_p0' } else { 'exchange_p1' })
    $followerPart = $(if ($leaderPart -eq 'exchange_p0') { 'exchange_p1' } else { 'exchange_p0' })
    $lp = $split['partitions'][$leaderPart]; $fp = $split['partitions'][$followerPart]
    $epochs = @($samples | ForEach-Object { $_.epoch }) + @($lease0.epoch, $lease1.epoch) | Sort-Object -Unique
    $leaseSame = ($epochs.Count -eq 1 -and $lease0.holder -eq $lease1.holder -and $lease1.alive)
    $axes = [ordered]@{
        'lb_nginx_t_ok_and_sha_same' = ($lb.nginxTRc -eq 0 -and $lb.same)
        'leader_primary_partition_first_attempt_all_leader' = ($lp['lines'] -gt 0 -and $lp['first_follower'] -eq 0 -and $lp['first_unknown'] -eq 0)
        'follower_primary_partition_503_then_leader' = ($fp['lines'] -gt 0 -and $fp['first_follower_503'] -gt 0 -and $fp['bad_shape'] -eq 0 -and $fp['first_unknown'] -eq 0)
        'follower_not_owner_within_lb_bracket' = ($inner -le $followerNotOwner -and $followerNotOwner -le $outer -and $inner -gt 0)
        'cancel_not_owner_0_both' = ((Get-Sum $window 'exchange_routing_cancel_not_owner_total*') -eq 0)
        'lb_502_0_and_no_live_upstreams_0' = ([long]$split['total']['status_502'] -eq 0 -and [long]$split['total']['no_live_upstreams'] -eq 0)
        'k6_all_runs_thresholds_pass' = (@($runs | Where-Object { $_.rc -ne 0 }).Count -eq 0)
        'trades_in_window_gt_0' = ($trades -gt 0)
        'lease_unchanged' = $leaseSame
        's4_contention_cap' = [bool]$axesS4['axes']['contention_cap_outside_window']
        's4_partition_alerts_0' = [bool]$axesS4['axes']['partition_alerts_outside_window_0']
        's4_leader_demotions_0' = [bool]$axesS4['axes']['leader_node_demotions_0']
        's4_lb_no_live_upstreams_502_0' = [bool]$axesS4['axes']['lb_no_live_upstreams_and_502_0']
        'settlement_insufficient_funds_0' = ((Get-Sum $window 'exchange_settlement_insufficient_funds_total') -eq 0)
        'lock_plan_miss_0' = ((Get-Sum $window 'exchange_settlement_lock_plan_miss_total') -eq 0)
        'append_40P01_0' = ((Get-Sum $window 'exchange_order_append_transient_total{sqlstate=40P01}') -eq 0)
        'pg_deadlocks_0' = ([long]$pg1.deadlocks - [long]$pg0.deadlocks -eq 0)
    }
    $s5 = Get-S5Axes $window $leader $leaseSame
    foreach ($k in $s5.Keys) { $axes[$k] = $s5[$k] }
    foreach ($k in $tail.axes.Keys) { $axes[$k] = $tail.axes[$k] }
    $verdict = $(if (@($axes.Values | Where-Object { -not $_ }).Count -eq 0) { 'PASS' } else { 'FAIL' })
    $result = [ordered]@{ phase = $leaf; verdict = $verdict; axes = $axes; leader = $leader; follower = $follower; lb = $lb
        window = [ordered]@{ from = $since; to = $until; t0 = $t0.ToString('o'); t1 = $t1.ToString('o'); botOps = $BotOps; k6Runs = $K6Runs }
        record = [ordered]@{ followerPrimaryPartition = $followerPart; twoAttemptRatio = $(if ($fp['lines'] -gt 0) { [math]::Round($fp['two_attempts'] / $fp['lines'], 4) } else { $null })
            followerNotOwner = $followerNotOwner; lbFirstFollower503 = $split['total']['order_write_first_follower_503']; trades = $trades
            bracket = [ordered]@{ inner = $inner; outer = $outer; ambiguousBoundaryLines = $outer - $inner
                scrapes = [ordered]@{ a0 = $a0.ToString('o'); b0 = $b0.ToString('o'); c1 = $c1.ToString('o'); d1 = $d1.ToString('o') }
                innerWindow = [ordered]@{ from = $innerFrom.ToString('o'); to = $innerTo.ToString('o') }; outerWindow = [ordered]@{ from = $outerFrom.ToString('o'); to = $outerTo.ToString('o') } } }
        k6 = $runs; lbsplit = $split; lease = [ordered]@{ before = $lease0; after = $lease1; samples = $samples }
        metricsWindow = $window; pg = [ordered]@{ before = $pg0; after = $pg1 }; partitionAxes = $axesS4['axes']; tail = $tail
        partitions = Get-Partitions; containers = Get-ContainerFacts; stack = $stackFrom.ToString('o')
        containerLogs = Save-ContainerLogs $leaf; lbLog = [ordered]@{ path = $lbLog; sha256 = (Get-FileHash $lbLog -Algorithm SHA256).Hash.ToLower() }
        host = [ordered]@{ before = $hostBefore; after = Get-HostLoad } }
    Save-Json (Join-Path $dir 'result.json') $result
    Write-Log ("{2} verdict={0} fails=[{1}]" -f $verdict, (($axes.Keys | Where-Object { -not $axes[$_] }) -join ','), $leaf)
    $code = Invoke-ComposeQuiet '--profile obs --profile scale down -v --remove-orphans'
    Write-Log "down -v (exchange-pml) rc=$code after $leaf"
}

# Z2·Z3·Z4 의 공통 틀. 봇을 멈춘 스택에서 도구를 부르고 공통 판정을 붙인다.
function Invoke-ProbePhase {
    param([string] $Name, [string] $Command, [switch] $Scale, [switch] $Admin)
    $dir = New-PhaseDir $Name
    $hostBefore = Get-HostLoad
    $stackFrom = [DateTimeOffset]::UtcNow
    $lb = Start-Stack -Dir $dir -Scale:$Scale
    $adminRc = $null
    if ($Admin) {
        # 관리 평면(설계 7-0). 저장소 루트 compose.yaml 의 chaos-admin 을 같은 환경에서 --no-deps 로만 띄운다. 같은 줄에 down -v 를 쓰지 않는다.
        $root = Join-Path $repo 'compose.yaml'
        # 변수 이름을 $Name(단계 이름)과 겹치지 않게 둔다. PowerShell 변수는 대소문자를 가리지 않는다(첫 Z4 주행에서 덮어썼다).
        $composeName = (& docker compose -p exchange-pml -f $root --env-file $Global:CHAOS.EnvFile config --format json 2>$null | ConvertFrom-Json).name
        if ($composeName -ne 'exchange-pml') { throw "관리 평면 compose 해석이 exchange-pml 이 아니다: $composeName" }
        Write-Log "RUN docker compose -p exchange-pml -f compose.yaml --env-file harness/.env.chaos --profile admin up -d --no-deps --wait chaos-admin"
        & cmd /c "docker compose -p exchange-pml -f `"$root`" --env-file `"$($Global:CHAOS.EnvFile)`" --profile admin up -d --no-deps --wait chaos-admin >nul 2>nul"
        $adminRc = $LASTEXITCODE
        Write-Log "chaos-admin up rc=$adminRc"
        if ($adminRc -ne 0) { throw "chaos-admin 기동 실패(rc=$adminRc)" }
    }
    $code = Invoke-DockerQuiet 'stop chaos-bots'
    $drained0 = Wait-DrainZero -Sql $Global:CHAOS_DRAIN_SQL -TimeoutSec 120 -RequiredStreak 2
    Write-Log "$Name bots stopped rc=$code drained=$drained0"
    $leader = Get-LeaderByGate
    $lease0 = Get-Lease
    $ports = @(18080, 28080) + $(if ($Scale) { @(38080) } else { @() })
    $n0 = Get-NodeMetrics -Ports $ports
    $prc = Invoke-Py @('harness/scale/s5_route_probe.py', $Command, '--out', (Join-Path $dir "$Command.json")) (Join-Path $dir "$Command.log")
    $probe = Get-Content (Join-Path $dir "$Command.json") -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
    $n1 = Get-NodeMetrics -Ports $ports
    $lease1 = Get-Lease
    $tail = Invoke-CommonTail -Dir $dir -Name $Name -StackFrom $stackFrom
    $window = Get-MetricDelta $n0 $n1
    $leaseSame = ($lease0 -and $lease1 -and $lease0.holder -eq $lease1.holder -and [long]$lease0.epoch -eq [long]$lease1.epoch -and $lease1.alive)
    $axes = [ordered]@{ 'lb_nginx_t_ok_and_sha_same' = ($lb.nginxTRc -eq 0 -and $lb.same); 'probe_ok' = ($prc -eq 0 -and [bool]$probe['ok']) }
    # Z3·Z1d 는 일부러 거절을 만든다. Z4 는 (d)에서 리더를 바꾸므로 리더 not_owner 축은 도구가 (a)~(c) 구간으로 본다.
    $s5 = Get-S5Axes $window $leader $leaseSame -AllowHintRejects:($Name -in 'Z3', 'Z1d')
    foreach ($k in $s5.Keys) { $axes[$k] = $s5[$k] }
    foreach ($k in $tail.axes.Keys) { $axes[$k] = $tail.axes[$k] }
    $verdict = $(if (@($axes.Values | Where-Object { -not $_ }).Count -eq 0) { 'PASS' } else { 'FAIL' })
    $result = [ordered]@{ phase = $Name; verdict = $verdict; axes = $axes; leader = $leader; lb = $lb; adminRc = $adminRc
        probe = [ordered]@{ rc = $prc; file = "$Command.json"; ok = $probe['ok']; failedAxes = @($probe['axes'].Keys | Where-Object { -not $probe['axes'][$_] }) }
        lease = [ordered]@{ before = $lease0; after = $lease1; same = $leaseSame }; metricsWindow = $window; tail = $tail
        partitions = Get-Partitions; containers = Get-ContainerFacts; stack = $stackFrom.ToString('o')
        containerLogs = Save-ContainerLogs $Name @('chaos-api-1', 'chaos-api-2', 'chaos-api-3', 'chaos-admin', 'chaos-bots', 'chaos-lb')
        host = [ordered]@{ before = $hostBefore; after = Get-HostLoad } }
    Save-Json (Join-Path $dir 'result.json') $result
    Write-Log ("{0} verdict={1} probe_rc={2} fails=[{3}]" -f $Name, $verdict, $prc, (($axes.Keys | Where-Object { -not $axes[$_] }) -join ','))
    $code = Invoke-ComposeQuiet '--profile obs --profile scale down -v --remove-orphans'
    Write-Log "down -v (exchange-pml) rc=$code after $Name"
}

function Invoke-Chaos {
    if (-not $SubDir) { throw 'Chaos 단계는 -SubDir 가 필요하다' }
    $sub = $SubDir
    $dir = Join-Path $here $sub
    if (Test-Path (Join-Path $dir 'summary.json')) { throw "이미 $sub 증거가 있다. 덮지 않는다" }
    $hostBefore = Get-HostLoad
    $env:CHAOS_SPRING_PROFILES = 'dev,chaos-monkey,prod-timeouts'
    Remove-Item Env:CHAOS_EXTRA_COMPOSE -ErrorAction SilentlyContinue
    $code = Invoke-ComposeQuiet '--profile obs --profile scale down -v --remove-orphans'
    Write-Log "down -v (exchange-pml) rc=$code before $sub"
    Assert-NoForeignChaos
    $localLogs = Join-Path $logRoot $sub
    Write-Log "RUN pwsh -NoProfile -File harness/scripts/adr0022-stage5-chaos.ps1 -Image $Image -Experiments $Experiments -Protocol 2 -PartitionAxes -EvidenceDir $sub -LocalLogDir $localLogs (CHAOS_LB_CONF=$env:CHAOS_LB_CONF)"
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        & pwsh -NoProfile -File (Join-Path $repo 'harness\scripts\adr0022-stage5-chaos.ps1') -Image $Image -Experiments $Experiments `
            -Protocol 2 -PartitionAxes -EvidenceDir $dir -LocalLogDir $localLogs *> (Join-Path $here "$sub-console.log")
        $rc = $LASTEXITCODE
    } finally { $ErrorActionPreference = $eap }
    Write-Log "stage5 rc=$rc ($sub)"
    $hostAfter = Get-HostLoad
    $records = [ordered]@{ sub = $sub; lbConf = $LbConf; rc = $rc; host = [ordered]@{ before = $hostBefore; after = $hostAfter }; experiments = @() }
    $summaryPath = Join-Path $dir 'summary.json'
    if (Test-Path $summaryPath) {
        $summary = Get-Content $summaryPath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
        foreach ($row in @($summary['experiments'])) {
            $exp = $row['experiment']
            $rec = [ordered]@{ experiment = $exp; verdict = $row['분리 판정']; runId = $row['runId'] }
            $logsJson = Join-Path $dir "$exp\container_logs.json"
            $paths = @{}
            if (Test-Path $logsJson) { foreach ($l in @(Get-Content $logsJson -Raw -Encoding utf8 | ConvertFrom-Json)) { $paths[$l.container] = $l.path } }
            $rec['logs'] = [ordered]@{}
            foreach ($c in 'chaos-api-1', 'chaos-api-2') { $rec['logs'][$c] = Get-SavedLogCounts $paths[$c] }
            if ($paths['chaos-lb'] -and (Test-Path $paths['chaos-lb'])) {
                $lbLines = [System.IO.File]::ReadAllLines($paths['chaos-lb'], [System.Text.Encoding]::UTF8)
                # 파티션 파일이 실제로 실렸는지 접근 로그 형식으로 본다(ups= 필드).
                $rec['lbFormat'] = [ordered]@{ accessLines = @($lbLines | Where-Object { $_ -match '\] "[A-Z]+ ' }).Count
                    partitionFormatLines = @($lbLines | Where-Object { $_ -match ' ups="' }).Count }
                if ($row['window']) {
                    $from = [DateTimeOffset]::Parse($row['window']['from']); $to = [DateTimeOffset]::Parse($row['window']['to'])
                    $rec['lbFaultWindow'] = Get-LbWindowCounts $lbLines $from $to
                }
            }
            $commonPath = Join-Path $dir "$exp\s3_common.json"
            if (Test-Path $commonPath) {
                $common = Get-Content $commonPath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
                $rec['orders500'] = $common['orders_500']
                $rec['recoveryRuns'] = [ordered]@{ success = $common['state']['recovery_runs_success']; mismatch = $common['state']['recovery_runs_mismatch'] }
                $nodes = [ordered]@{}
                foreach ($n in 'chaos-api-1', 'chaos-api-2') {
                    $src = $common['nodes'][$n]
                    $nodes[$n] = [ordered]@{}
                    if ($src) { foreach ($k in $src.Keys) { if ($k -like 'exchange_order_intake_*' -or $k -like 'exchange_routing_*' -or $k -like 'exchange_db_read_retry*' -or $k -like 'exchange_order_cancel_handoff*' -or $k -eq 'ok') { $nodes[$n][$k] = $src[$k] } } }
                }
                $rec['nodeCounters'] = $nodes
            }
            $verdictPath = Join-Path $dir "$exp\verdict.json"
            if (Test-Path $verdictPath) {
                $v = Get-Content $verdictPath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
                $rec['rtoSeconds'] = $v['failover']['rtoSeconds']
                $rec['epochs'] = [ordered]@{ old = $v['failover']['oldEpoch']; new = $v['failover']['newEpoch'] }
            }
            $records.experiments += $rec
        }
    }
    Save-Json (Join-Path $here "$sub-s5.json") $records
    Write-Log ("CHAOS {0} {1}" -f $sub, (($records.experiments | ForEach-Object { '{0}={1} rto={2}' -f $_.experiment, $_.verdict, $_.rtoSeconds }) -join ' '))
}

# :pml 을 이미지 id 로 붙인다(설계 7-0). 태그만 옮기고 이미지를 지우지 않는다.
function Set-PmlTag {
    param([string] $ImageId)
    & docker tag $ImageId $Image
    $now = (& docker image inspect $Image --format '{{.Id}}' | Out-String).Trim()
    Write-Log "TAG $Image -> $now"
    if ($now -ne $ImageId) { throw ":pml 태그 이동 실패($now != $ImageId)" }
}

# B1 DB 흔들림(설계 7-2 Z6, 기록 실험). 리더가 DB 에 닿는 채로 쓰던 연결만 끊겼을 때를 S4b 값과 나란히 적는다.
function Invoke-B1 {
    $dir = New-PhaseDir 'Z6'
    $hostBefore = Get-HostLoad
    $stackFrom = [DateTimeOffset]::UtcNow
    $lb = Start-Stack -Dir $dir
    Invoke-Psql "INSERT INTO sim_commands (action, payload, requested_by) VALUES ('target_ops', '$BotOps', 'run-s5-gate')" | Out-Null
    Write-Log "B1 bots target_ops=$BotOps, warmup ${B1WarmupSec}s"
    Start-Sleep -Seconds $B1WarmupSec
    $rounds = @()
    for ($i = 1; $i -le $B1Rounds; $i++) {
        $l0 = Get-Lease
        $leader = Get-LeaderContainer
        $proxy = $(if ($leader -eq 'chaos-api-1') { 'pg-api1' } elseif ($leader -eq 'chaos-api-2') { 'pg-api2' } else { $null })
        if (-not $proxy) {
            $rounds += [ordered]@{ round = $i; status = 'N/A'; reason = '리더 판별 실패' }
            Write-Log "B1 round $i N/A(리더 판별 실패)"
            Start-Sleep -Seconds $B1IntervalSec
            continue
        }
        $n0 = Get-NodeMetrics
        $t0 = [DateTimeOffset]::UtcNow
        try {
            Add-Toxic -Proxy $proxy -Name 'b1-reset' -Type 'reset_peer' -Attributes @{ timeout = 0 }
            Start-Sleep -Milliseconds $B1ResetMs
        } finally {
            Remove-AllToxics -Proxy $proxy
        }
        $t1 = [DateTimeOffset]::UtcNow
        Write-Log "B1 round $i reset_peer $proxy ($leader) $($t0.ToString('o')) -> $($t1.ToString('o'))"
        Start-Sleep -Seconds $B1IntervalSec
        $n1 = Get-NodeMetrics
        $l1 = Get-Lease
        $lbLines = @(& cmd /c "docker logs --since $($t0.ToUnixTimeSeconds() - 1) chaos-lb 2>&1")
        $same = ($l0.holder -eq $l1.holder -and [long]$l0.epoch -eq [long]$l1.epoch -and $l1.alive)
        $delta = Get-MetricDelta $n0 $n1
        $s5 = Get-S5Axes $delta $leader $same
        $rounds += [ordered]@{ round = $i; status = $(if ($same) { 'measured' } else { 'N/A' })
            reason = $(if ($same) { $null } else { 'holder·epoch 가 바뀌어 리더 유지 전제가 서지 않는다' })
            leader = $leader; proxy = $proxy; inject = [ordered]@{ from = $t0.ToString('o'); to = $t1.ToString('o'); ms = $B1ResetMs }
            lease = [ordered]@{ before = $l0; after = $l1 }; metricsDelta = $delta; s5Axes = $s5
            lb = Get-LbWindowCounts $lbLines $t0 $t0.AddSeconds($B1IntervalSec) }
    }
    $tail = Invoke-CommonTail -Dir $dir -Name 'Z6' -StackFrom $stackFrom
    $epochs = @($rounds | Where-Object { $_.lease } | ForEach-Object { [long]$_.lease.before.epoch; [long]$_.lease.after.epoch } | Sort-Object -Unique)
    $axes = [ordered]@{ 'lb_nginx_t_ok_and_sha_same' = ($lb.nginxTRc -eq 0 -and $lb.same); 'leader_epoch_unchanged' = ($epochs.Count -eq 1) }
    $axes['s5_axes_in_measured_rounds'] = (@($rounds | Where-Object { $_.status -eq 'measured' -and @($_.s5Axes.Values | Where-Object { -not $_ }).Count -gt 0 }).Count -eq 0)
    foreach ($k in $tail.axes.Keys) { $axes[$k] = $tail.axes[$k] }
    $measured = @($rounds | Where-Object { $_.status -eq 'measured' }).Count
    $verdict = $(if (@($axes.Values | Where-Object { -not $_ }).Count -eq 0 -and $measured -eq $B1Rounds) { 'PASS' } elseif ($measured -lt $B1Rounds) { 'N/A' } else { 'FAIL' })
    $result = [ordered]@{ phase = 'Z6'; verdict = $verdict; axes = $axes; measuredRounds = $measured; rounds = $rounds; lb = $lb; tail = $tail
        partitions = Get-Partitions; containers = Get-ContainerFacts; stack = $stackFrom.ToString('o')
        containerLogs = Save-ContainerLogs 'Z6'; host = [ordered]@{ before = $hostBefore; after = Get-HostLoad } }
    Save-Json (Join-Path $dir 'result.json') $result
    Write-Log ("Z6 verdict={0} measured={1}/{2} fails=[{3}]" -f $verdict, $measured, $B1Rounds, (($axes.Keys | Where-Object { -not $axes[$_] }) -join ','))
    $code = Invoke-ComposeQuiet '--profile obs --profile scale down -v --remove-orphans'
    Write-Log "down -v (exchange-pml) rc=$code after Z6"
}

# 대조 실험 WsRace(설계 밖, 설계 12-4 기록). Z1r 의 k6 WS orderbook 결번이 S5 와 무관한지 가른다. 봇 300 OPS(KRW-BTC 에 몰림) 아래에서
# ws_subscribe_race.js 가 구독을 짧게 되풀이하며 스냅샷 뒤 첫 realtime 의 결번을 센다. -RaceRef 를 주면 :pml 을 그 참조 태그의 이미지로
# 옮겨 돌고, 끝나면(실패해도) S5 이미지로 되돌린다. 태그만 옮기고 이미지를 지우지 않는다.
function Invoke-WsRace {
    $dir = New-PhaseDir 'W'
    $leaf = Split-Path $dir -Leaf
    $images = Get-Content $imagesPath -Raw -Encoding utf8 | ConvertFrom-Json
    $s5 = (& docker image inspect $images.refTag --format '{{.Id}}' | Out-String).Trim()
    if ($s5 -ne $images.imageId) { throw "참조 태그 $($images.refTag) 가 S5 이미지를 가리키지 않는다($s5)" }
    $target = $images.imageId
    if ($RaceRef) {
        $target = (& docker image inspect $RaceRef --format '{{.Id}}' | Out-String).Trim()
        if (-not $target) { throw "참조 태그 $RaceRef 가 없다" }
    }
    $hostBefore = Get-HostLoad
    $stackFrom = [DateTimeOffset]::UtcNow
    try {
        $code = Invoke-ComposeQuiet '--profile obs --profile scale down -v --remove-orphans'
        Set-PmlTag $target
        $lb = Start-Stack -Dir $dir
        Invoke-Psql "INSERT INTO sim_commands (action, payload, requested_by) VALUES ('target_ops', '$BotOps', 'run-s5-gate')" | Out-Null
        Write-Log "$leaf image=$target raceRef=$RaceRef bots target_ops=$BotOps warmup ${WarmupSec}s"
        Start-Sleep -Seconds $WarmupSec
        $outDir = Join-Path $logRoot "$leaf-k6"
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
        $k6Args = @('run', '--rm', '--network', 'exchange-pml_chaos-net', '-v', "$(Join-Path $repo 'harness'):/h:ro", '-v', "${outDir}:/out",
            '-e', 'BASE_URL=http://chaos-lb:8080', '-e', 'MARKET=KRW-BTC', '-e', 'VUS=20', '-e', 'DURATION=120s', '-e', 'HOLD_MS=1500',
            'grafana/k6:1.4.0', 'run', '--quiet', '--no-color', "/h/evidence/$(Split-Path $here -Leaf)/ws_subscribe_race.js")
        Write-Log "RUN docker run k6 ws_subscribe_race.js (VUS 20, 120s, HOLD_MS 1500, KRW-BTC)"
        $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        try { & docker @k6Args *> (Join-Path $outDir 'k6.log'); $rc = $LASTEXITCODE } finally { $ErrorActionPreference = $eap }
        $sumPath = Join-Path $outDir 'ws_race_summary.json'
        $summary = $(if (Test-Path $sumPath) { Get-Content $sumPath -Raw -Encoding utf8 | ConvertFrom-Json } else { $null })
        Write-Log "$leaf k6 rc=$rc summary=$(if ($summary) { $summary | ConvertTo-Json -Compress } else { 'none' })"
        $containers = Get-ContainerFacts
        $tail = Invoke-CommonTail -Dir $dir -Name $leaf -StackFrom $stackFrom
        $result = [ordered]@{ phase = 'WsRace'; sub = $leaf; imageId = $target; raceRef = $RaceRef; k6Rc = $rc; summary = $summary; lb = $lb
            tailAxes = $tail.axes; tail = $tail; containers = $containers; stack = $stackFrom.ToString('o')
            k6Log = [ordered]@{ path = (Join-Path $outDir 'k6.log'); sha256 = (Get-FileHash (Join-Path $outDir 'k6.log') -Algorithm SHA256).Hash.ToLower() }
            host = [ordered]@{ before = $hostBefore; after = Get-HostLoad } }
        Save-Json (Join-Path $dir 'result.json') $result
    } finally {
        $code = Invoke-ComposeQuiet '--profile obs --profile scale down -v --remove-orphans'
        Write-Log "down -v (exchange-pml) rc=$code after $leaf"
        Set-PmlTag $images.imageId
    }
}

Write-Log "PHASE $Phase project=$resolved repo=$repo image=$Image lb=$lbFile"
Test-PreservedVolumes 'before'
try {
    switch ($Phase) {
        'Prep' {
            Assert-NoForeignChaos
            # 지금 :pml 은 S4b 보정 이미지(참조 태그 s4b3ref)다. 새 이미지에는 s5ref 를 붙인다(설계 7-0).
            $prevRefTag = 'exchange-chaos-app:s4b3ref'
            $newRefTag = 'exchange-chaos-app:s5ref'
            $prev = (& docker image inspect $Image --format '{{.Id}}|{{.Created}}' | Out-String).Trim().Split('|')
            $prevRef = (& docker image inspect $prevRefTag --format '{{.Id}}' | Out-String).Trim()
            if ($prevRef -ne $prev[0]) { throw "$prevRefTag($prevRef) 가 지금 :pml($($prev[0])) 과 달라 앞 이미지를 잃을 수 있다" }
            $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
            try { $taken = (& cmd /c "docker image inspect $newRefTag --format {{.Id}} 2>nul" | Out-String).Trim() } finally { $ErrorActionPreference = $eap }
            if ($taken) { throw "$newRefTag 가 이미 $taken 을 가리킨다. 덮어쓰지 않는다" }
            Write-Log "previous :pml $($prev[0]) created $($prev[1]); $prevRefTag=$prevRef newRef=$newRefTag"
            Save-Text (Join-Path $here 'docker-ps-before.txt') ((& docker ps -a --format '{{.Names}} {{.Image}} {{.Status}} {{.Label "com.docker.compose.project"}}' | Out-String))
            $env:CHAOS_SPRING_PROFILES = 'dev,chaos-monkey,prod-timeouts'
            $urc = Invoke-Pwsh 'harness\scripts\up.ps1' @('-Obs') (Join-Path $here 'prep-up.log')
            if ($urc -ne 0) { throw "up.ps1 실패(rc=$urc)" }
            $img = (& docker image inspect $Image --format '{{.Id}}|{{.Created}}' | Out-String).Trim().Split('|')
            if ($img[0] -eq $prev[0]) { throw '새 이미지가 구워지지 않았다(:pml 이 그대로다)' }
            $head = (& git -C $repo rev-parse HEAD | Out-String).Trim()
            $status = (& git -C $repo status --porcelain | Out-String)
            $diffHash = ((& git -C $repo diff HEAD | Out-String) | ForEach-Object { [System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::HashData([System.Text.Encoding]::UTF8.GetBytes($_))).Replace('-', '').ToLower() })
            $jar = Join-Path $repo 'harness\app.jar'
            $flyway = Get-FlywayState
            if ([long]$flyway.max_version -ne 54) { throw "Flyway 최대 판이 54 가 아니다: $($flyway.max_version)" }
            $lbDefault = Get-LbFacts
            & docker tag $img[0] $newRefTag
            Write-Log "TAG $newRefTag -> $($img[0])"
            $images = [ordered]@{ tag = $Image; imageId = $img[0]; created = $img[1]; refTag = $newRefTag
                previousImageId = $prev[0]; previousCreated = $prev[1]; previousRefTag = $prevRefTag
                worktree = $repo; head = $head; trackedDiffSha256 = $diffHash; changedPaths = @($status -split "`r?`n" | Where-Object { $_ }).Count
                jarSha256 = (Get-FileHash $jar -Algorithm SHA256).Hash.ToLower(); jarWritten = (Get-Item $jar).LastWriteTime.ToString('o')
                flyway = $flyway; lbAtPrep = $lbDefault
                note = 'S5 는 이 단계에서 커밋하지 않는다. 이미지는 HEAD 위 작업 트리 변경(S5 코드·시험·하네스·문서)을 담는다' }
            Save-Json $imagesPath $images
            Write-Log "images.json s5=$($img[0]) previous=$($prev[0]) head=$head diff=$diffHash flyway_max=$($flyway.max_version)"
            $code = Invoke-ComposeQuiet '--profile obs --profile scale down -v --remove-orphans'
            Write-Log "down -v (exchange-pml) rc=$code after build"
        }
        'Chaos' { Invoke-Chaos }
        'Z1' { Invoke-Z1 }
        'Z1d' { Invoke-ProbePhase -Name 'Z1d' -Command 'zroute' }
        'Z2' { Invoke-ProbePhase -Name 'Z2' -Command 'z2' -Scale }
        'Z3' { Invoke-ProbePhase -Name 'Z3' -Command 'z3' }
        'Z4' { Invoke-ProbePhase -Name 'Z4' -Command 'z4' -Scale -Admin }
        'B1' { Invoke-B1 }
        'WsRace' { Invoke-WsRace }
        'Final' {
            $code = Invoke-ComposeQuiet '--profile obs --profile admin --profile load --profile scale down -v --remove-orphans'
            Write-Log "final down -v (exchange-pml) rc=$code"
            if (Test-Path $imagesPath) {
                $id = (Get-Content $imagesPath -Raw -Encoding utf8 | ConvertFrom-Json).imageId
                $now = (& docker image inspect $Image --format '{{.Id}}' | Out-String).Trim()
                if ($now -ne $id) { Set-PmlTag $id } else { Write-Log ":pml 은 S5 이미지 그대로다 $now" }
            }
            Save-Text (Join-Path $here 'docker-ps-after.txt') ((& docker ps -a --format '{{.Names}} {{.Image}} {{.Status}} {{.Label "com.docker.compose.project"}}' | Out-String))
            Save-Text (Join-Path $here 'volume-ls-end.txt') ((& docker volume ls --format '{{.Name}}' | Where-Object { $_ -like 'exchange-pml*' } | Out-String))
        }
    }
} finally {
    Test-PreservedVolumes 'after'
}
