# 로컬 MSP 관제 시연 스택을 한 번에 올린다. 재부팅 뒤에도 이 명령 하나로 같은 상태가 된다.
#
#   pwsh -NoProfile -File C:\dev\exchange\harness\scripts\demo-obs-up.ps1                 DB 모드 자동 판별(아래)
#   pwsh -NoProfile -File C:\dev\exchange\harness\scripts\demo-obs-up.ps1 -DbMode async   종전 비동기 DB 로 올린다(되돌리기)
#   pwsh -NoProfile -File C:\dev\exchange\harness\scripts\demo-obs-up.ps1 -CheckOnly      확인 표만 본다
#
# ASCII 경로(C:\dev\exchange, 저장소 폴더의 심볼릭 링크)로 부른다. 기존 컨테이너가 그 경로의 바인드 마운트로 만들어져
# 있어 다른 경로로 부르면 compose 가 경로 차이만으로 컨테이너를 다시 만든다.
#
# DB 모드(harness/scripts/demo-common.ps1). patroni 는 Patroni 엄격 동기 두 노드(chaos-db-1·2, ADR 0036, 2026-09-27 부터 시연
# 기본값), async 는 종전 chaos-pg + 비동기 대기 DB(ADR 0024)다. 자동 판별은 Patroni 노드 컨테이너가 있으면 patroni 다.
# 처음 Patroni 로 옮기는 일은 이 스크립트가 아니라 demo-patroni-migrate.ps1 이 한다(데이터 이전이 있다).
#
# compose 파일은 기본 chaos 파일에 시연 오버레이 셋(관측 demo-obs, 알림 demo-report, 시연 봇 demo-bots)을 겹치고 patroni 모드면
# demo-patroni 를 맨 뒤에 겹친다. 목록은 demo-common.ps1 한 곳에 있다. 빠진 오버레이의 서비스는 기본값으로 다시 만들어진다.
#
# 하는 일(모두 반복 실행해도 같은 결과가 되게 짰다).
#   1. harness/demo/obs.local/obs.env 가 없으면 복제·감시 계정 비밀번호를 무작위로 만든다. 값을 출력하지 않는다. 보고 서비스
#      토큰이 없으면 demo-reporting-local.py init 으로 만든다(Alertmanager·관리 평면이 그 파일을 마운트한다).
#   2. 로컬 MSP 화면 7개를 원본에서 다시 만들고(harness/grafana/build-local-msp.py), 기본 Prometheus 설정의 잡이 시연 설정과
#      DB 수집 파일에 모두 있는지, 증명 대시보드가 오버레이 마운트에서 빠지지 않았는지 확인한다. 조회 노드 수집 대상 파일
#      (harness/demo/prometheus-sd.local/reader.json)과, patroni 모드면 수집기 확장 질의 파일을 만든다.
#   3. DB 계층을 올린다.
#      patroni: etcd 세 표 → DB 두 노드 → 리더와 동기 Standby 확인 → HAProxy 네 개·도구 → 감시 계정 절차(provision-monitor.sql).
#      async:   chaos-pg 준비(pg_stat_statements 사전 로드가 없으면 ALTER SYSTEM 과 재시작 한 번, 확장, failstop_monitor, 복제 계정 repl,
#               복제 pg_hba 한 줄, 복제 슬롯 db_standby).
#   4. 앱 노드 설정이 compose 와 다르면(설정 해시) 팔로워(chaos-api-2)부터 하나씩 다시 만든다. 리더(chaos-api-1)를 다시 만들면
#      리더가 chaos-api-2 로 넘어가는데, 기본 compose 는 chaos-api-1 의 readiness 를 건강 판정에 쓰므로 chaos-api-2 를 재시작해
#      리더를 chaos-api-1 로 돌려놓는다. 멈춰 있는 chaos-api-3 은 설정이 다르면 만들기만 다시 한다(켜지 않는다).
#   5. 봇 기준가를 지금 시세로 맞추고(docker-compose.demo-bots.yml 머리 주석) 나머지 전부를 올린다.
#   6. 관리 평면(chaos-admin)과 관문(chaos-admin-gw)을 --no-deps 로 올린다(-SkipAdmin 이면 건너뛴다).
#   7. Prometheus 대상과 핵심 지표, DB 클러스터와 거래 상태를 표로 보인다.
#
# 호스트 프로세스(보고 서비스 9088, 웹 개발 서버 3001, 조정기)는 이 스크립트가 띄우지 않는다. 끝에 명령을 적는다.
#
# 관측 오버레이 되돌리기(async 모드에서). 오버레이 없이 기본 파일만으로 관측 서비스를 다시 만들면 종전 Prometheus·Grafana·exporter 로
# 돌아간다.
#   docker compose -p exchange-chaos -f harness/docker-compose.chaos.yml --env-file harness/.env.chaos --profile obs \
#     up -d --no-deps chaos-prometheus chaos-grafana chaos-pg-exporter
#   docker stop chaos-pg-standby-exporter chaos-db-backup chaos-node-exporter chaos-pg-standby
# DB 쪽 준비는 볼륨 안에 남는다. 지우려면 대기 DB 를 멈춘 뒤 primary 에서 pg_drop_replication_slot('db_standby'),
# ALTER SYSTEM RESET shared_preload_libraries(재시작 필요)를 실행하고 pg_hba.conf 의 해당 줄을 뺀다.
# Patroni 에서 종전 비동기 DB 로 되돌리는 절차는 demo-patroni-migrate.ps1 머리 주석에 있다.
param(
    # 확인 표만 다시 보고 싶을 때. 아무것도 바꾸지 않는다.
    [switch] $CheckOnly,
    [ValidateSet('auto', 'patroni', 'async')] [string] $DbMode = 'auto',
    # 관리 평면과 관문을 올리지 않는다.
    [switch] $SkipAdmin
)

. (Join-Path $PSScriptRoot 'demo-common.ps1')

$harness = $Global:Demo.Harness
$repo = $Global:Demo.Repo
$mode = if ($DbMode -eq 'auto') { Get-DemoDbMode } else { $DbMode }

function Show-Summary {
    Write-DemoStep "수집 확인(DB 모드 $mode)"
    $rows = @(
        @{ n = 'MSP 대상 up(정상/등록)'; q = 'sum(up{environment="local-lab",role=~"app|reader|data|standby|probe|host"}) / count(up{environment="local-lab",role=~"app|reader|data|standby|probe|host"})' },
        @{ n = '웹 입구·정적 자산·LB 검사'; q = 'min(probe_success{job="blackbox-http",environment="local-lab"})' },
        @{ n = 'Spring 요청 수용(최소)'; q = 'min(exchange_node_serviceable{job="exchange-api",environment="local-lab"})' },
        @{ n = 'DB 역할(in_recovery 합=대기 수)'; q = 'sum(pg_wal_position_in_recovery{job="postgres",environment="local-lab"})' },
        @{ n = '복제 재생 지연 바이트'; q = 'max(pg_replication_client_sent_replay_bytes{job="postgres",environment="local-lab"})' },
        @{ n = 'SQL 통계 수집(최소)'; q = 'min(pg_ops_sql_collection_available{job="postgres",environment="local-lab"})' },
        @{ n = 'HTTP 버킷 계열 수'; q = 'count(http_server_requests_seconds_bucket{job="exchange-api",environment="local-lab"})' },
        @{ n = '백업 경과(초)'; q = 'time() - max(failstop_backup_last_success_timestamp_seconds{job="linux-host"})' },
        @{ n = '디스크 사용률(/var/lib, %)'; q = 'max(100 * (1 - node_filesystem_avail_bytes{job="linux-host",mountpoint="/var/lib"} / node_filesystem_size_bytes{job="linux-host",mountpoint="/var/lib"}))' },
        @{ n = '가용성 규칙 msp_probe_success'; q = 'exchange:msp_probe_success:ratio5m' },
        @{ n = 'P95 규칙 msp_http_p95(초)'; q = 'exchange:msp_http_p95:seconds5m' }
    )
    if ($mode -eq 'patroni') {
        $rows += @(
            @{ n = 'Patroni Primary 수'; q = 'sum(patroni_primary)' },
            @{ n = 'Patroni 동기 Standby 수'; q = 'sum(patroni_sync_standby)' },
            @{ n = 'etcd 응답 멤버'; q = 'count(up{job="etcd"} == 1)' },
            @{ n = 'HAProxy 쓰기 백엔드(최소)'; q = 'min(haproxy_backend_active_servers{proxy="pg_write"})' },
            @{ n = '거래 중단 노드 수'; q = 'sum(exchange_write_availability_state{state!="ACTIVE"})' }
        )
    }
    $table = foreach ($row in $rows) {
        $result = Get-DemoPromValue $row.q
        $value = if ($result.Count -gt 0) { '{0:N3}' -f [double]$result[0].value[1] } else { '없음' }
        [pscustomobject]@{ 항목 = $row.n; 값 = $value }
    }
    $table | Format-Table -AutoSize | Out-Host
    $down = Get-DemoPromValue 'up{environment="local-lab"} == 0'
    foreach ($d in $down) { Write-Host "  down: $($d.metric.job) $($d.metric.instance)" -ForegroundColor Yellow }
    if ($mode -eq 'patroni') {
        Write-Host 'Patroni:'
        Show-DemoCluster
        foreach ($entry in $Global:Demo.ApiPorts.GetEnumerator()) {
            $s = Get-DemoTradingState $entry.Value
            Write-Host ("  {0} 거래 상태 {1}" -f $entry.Key, $(if ($s) { "$($s.trading_state) $($s.reason)" } else { '응답 없음' }))
        }
    }
    Write-Host "Grafana: $($Global:Demo.GrafanaUrl)/dashboards  (폴더 'MSP 운영', 변수 customer=exchange, environment=local-lab)"
    if ($mode -eq 'patroni') { Write-Host "         DB 자동 전환 보드: $($Global:Demo.GrafanaUrl)/d/db-ha-patroni" }
}

if ($CheckOnly) { Show-Summary; return }

Write-DemoStep "0/7 사전 확인(DB 모드 $mode)"
& docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker 가 응답하지 않는다. Docker Desktop 을 먼저 켠다' }
if (-not (Test-Path -LiteralPath $Global:Demo.EnvChaos)) { throw "$($Global:Demo.EnvChaos) 가 없다(harness/.env.chaos.example 을 복사해 채운다)" }
if ($mode -eq 'patroni' -and -not (Test-Path -LiteralPath $Global:Demo.EnvPatroni)) {
    throw "$($Global:Demo.EnvPatroni) 가 없다(harness/patroni/.env.patroni.example 을 복사한다)"
}

Write-DemoStep '1/7 로컬 비밀번호 파일과 보고 토큰'
$obsEnv = $Global:Demo.EnvObs
if (-not (Test-Path -LiteralPath $obsEnv)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $obsEnv) | Out-Null
    $bytes = [byte[]]::new(24)
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes); $repl = [Convert]::ToHexString($bytes).ToLowerInvariant()
    $rng.GetBytes($bytes); $monitor = [Convert]::ToHexString($bytes).ToLowerInvariant()
    @('# 로컬 MSP 시연 전용 비밀번호. demo-obs-up.ps1 이 만든다. 커밋하지 않는다(*.local).',
      "DEMO_REPL_PASSWORD=$repl", "DEMO_MONITOR_PASSWORD=$monitor") |
        Set-Content -LiteralPath $obsEnv -Encoding utf8NoBOM
    Write-Host "  새로 만들었다: $obsEnv (값은 출력하지 않는다)"
} else {
    Write-Host "  있음: $obsEnv"
}
$secrets = Read-DemoObsEnv
if (-not (Test-Path -LiteralPath $Global:Demo.ReporterToken)) {
    & python (Join-Path $harness 'scripts\demo-reporting-local.py') init
    if ($LASTEXITCODE -ne 0) { throw '보고 서비스 토큰 준비 실패(demo-reporting-local.py init)' }
}

Write-DemoStep '2/7 로컬 MSP 화면 생성, 설정 누락 확인, 수집 대상·질의 파일'
$mspDir = Join-Path $harness 'grafana\dashboards-msp'
function Get-MspHash { (Get-ChildItem -LiteralPath $mspDir -Filter '*.json' -ErrorAction SilentlyContinue | Get-FileHash | ForEach-Object Hash) -join ',' }
$mspBefore = Get-MspHash
& python (Join-Path $harness 'grafana\build-local-msp.py')
if ($LASTEXITCODE -ne 0) { throw '로컬 MSP 화면 생성 실패' }
# Docker Desktop 의 Windows 바인드 마운트는 파일 변경 통지가 Grafana 에 닿지 않아(2026-09-27 확인) 바뀐 화면을 다시 읽지 않는다.
# 화면이 바뀌었으면 5단계 뒤에 Grafana 를 재시작한다. Grafana DB 는 컨테이너 안에 있고 화면은 파일에서 다시 읽는다.
$mspChanged = $mspBefore -ne (Get-MspHash)
$jobPattern = '^\s*-\s*job_name:\s*(\S+)'
$baseJobs = Select-String -LiteralPath (Join-Path $harness 'prometheus\prometheus.yml') -Pattern $jobPattern |
    ForEach-Object { $_.Matches[0].Groups[1].Value }
$dbScrape = Join-Path $harness ('prometheus\demo-scrape\' + $(if ($mode -eq 'patroni') { 'db-patroni.yml' } else { 'db-async.yml' }))
$demoJobs = Select-String -LiteralPath @((Join-Path $harness 'prometheus\prometheus.demo.yml'), $dbScrape) -Pattern $jobPattern |
    ForEach-Object { $_.Matches[0].Groups[1].Value }
$missingJobs = @($baseJobs | Where-Object { $_ -notin $demoJobs })
if ($missingJobs.Count -gt 0) { throw "prometheus.demo.yml 과 $(Split-Path $dbScrape -Leaf) 에 기본 설정의 잡이 없다: $($missingJobs -join ', ')" }
$overlayText = Get-Content -LiteralPath (Join-Path $harness 'docker-compose.demo-obs.yml') -Raw -Encoding utf8
$proofFiles = Get-ChildItem -LiteralPath (Join-Path $harness 'grafana\dashboards') -Filter '*.json' |
    Where-Object { $_.Name -notmatch '^1\d-msp-' } | ForEach-Object { $_.Name }
$missingProof = @($proofFiles | Where-Object { $overlayText -notmatch [regex]::Escape("dashboards/$_") })
if ($missingProof.Count -gt 0) { throw "docker-compose.demo-obs.yml 의 Grafana 마운트에 증명 대시보드가 빠졌다: $($missingProof -join ', ')" }
Write-Host "  기본 잡 $($baseJobs.Count)개 포함, 증명 대시보드 $($proofFiles.Count)개 마운트 확인"
$readerListed = Initialize-DemoSd
Write-Host "  조회 노드 수집 대상 파일: $(if ($readerListed) { 'chaos-api-3' } else { '비어 있음' })"
$queriesChanged = $false
if ($mode -eq 'patroni') {
    $queriesChanged = Write-DemoPgQueries
    Write-Host "  수집기 확장 질의 파일$(if ($queriesChanged) { '을 새로 만들었다' } else { ' 그대로' })"
}

if ($mode -eq 'patroni') {
    Write-DemoStep '3/7 DB 계층(Patroni: etcd → DB 두 노드 → HAProxy·도구)'
    Invoke-DemoCompose patroni up -d --no-deps --wait $Global:Demo.EtcdServices
    Invoke-DemoCompose patroni up -d --no-deps chaos-db-1 chaos-db-2
    Wait-DemoUntil 'Patroni 리더와 동기 Standby' { Test-DemoClusterHealthy } 300
    Invoke-DemoCompose patroni up -d --no-deps $Global:Demo.HaproxyServices $Global:Demo.Toolbox
    Wait-DemoUntil '도구 컨테이너 → HAProxy → Primary 연결' { (Invoke-DemoSql 'SELECT 1') -eq '1' } 90
    $env:PG_MONITOR_PASSWORD = $secrets['DEMO_MONITOR_PASSWORD']
    try {
        # 학교 AWS 와 같은 감시 계정 절차(pg_monitor, 읽기 전용, 연결 4개)다. 반복해도 같다.
        Invoke-DemoSqlScript (Get-Content -LiteralPath (Join-Path $repo 'deploy\observability\provision-monitor.sql') -Raw) @('PG_MONITOR_PASSWORD')
    } finally { $env:PG_MONITOR_PASSWORD = $null }
    if ($null -eq (Invoke-DemoSql 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements')) { throw 'pg_stat_statements 확장 확인 실패' }
    Show-DemoCluster
} else {
    Write-DemoStep '3/7 DB 계층(비동기: chaos-pg 와 준비)'
    function Invoke-OldSql([string] $Sql) {
        $out = & docker exec chaos-pg psql -U exchange -d exchange -X -A -t -q -v ON_ERROR_STOP=1 -c $Sql
        if ($LASTEXITCODE -ne 0) { throw "SQL 실패: $Sql" }
        return (($out | Out-String).Trim())
    }
    function Invoke-OldSqlScript([string] $Script, [string[]] $EnvNames = @()) {
        $envArgs = @()
        foreach ($name in $EnvNames) { $envArgs += @('-e', $name) }
        $Script | & docker exec -i @envArgs chaos-pg psql -U exchange -d exchange -X -q -v ON_ERROR_STOP=1 -f - | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'SQL 스크립트 실패' }
    }
    Invoke-DemoCompose async up -d --wait chaos-pg
    $preload = Invoke-OldSql "SELECT setting FROM pg_settings WHERE name = 'shared_preload_libraries'"
    if ($preload -notmatch '(^|,)\s*pg_stat_statements\s*(,|$)') {
        $libraries = (@($preload -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) + 'pg_stat_statements') -join ','
        # ALTER SYSTEM 은 트랜잭션 블록 안에서 거부되므로 단독으로 보낸다.
        Invoke-OldSql "ALTER SYSTEM SET shared_preload_libraries = '$libraries'" | Out-Null
        Write-Host "  shared_preload_libraries 에 pg_stat_statements 를 넣었다. chaos-pg 를 한 번 재시작한다(볼륨 유지)" -ForegroundColor Yellow
        & docker restart chaos-pg | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'chaos-pg 재시작 실패' }
        Wait-DemoUntil 'chaos-pg healthy' { (Get-DemoHealth 'chaos-pg') -eq 'running|healthy' } 120
    }
    Invoke-OldSql 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements' | Out-Null
    if ((Invoke-OldSql "SELECT current_setting('pg_stat_statements.track_utility')") -ne 'off') {
        Invoke-OldSql 'ALTER SYSTEM SET pg_stat_statements.track_utility = off' | Out-Null
        Invoke-OldSql 'SELECT pg_reload_conf()' | Out-Null
    }
    $env:PG_MONITOR_PASSWORD = $secrets['DEMO_MONITOR_PASSWORD']
    $env:DEMO_REPL_PASSWORD = $secrets['DEMO_REPL_PASSWORD']
    try {
        Invoke-OldSqlScript (Get-Content -LiteralPath (Join-Path $repo 'deploy\observability\provision-monitor.sql') -Raw) @('PG_MONITOR_PASSWORD')
        Invoke-OldSqlScript @'
\getenv repl_password DEMO_REPL_PASSWORD
SELECT 'CREATE ROLE repl WITH LOGIN REPLICATION' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'repl')
\gexec
ALTER ROLE repl WITH LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'repl_password';
SELECT pg_create_physical_replication_slot('db_standby')
WHERE NOT EXISTS (SELECT 1 FROM pg_replication_slots WHERE slot_name = 'db_standby');
'@ @('DEMO_REPL_PASSWORD')
    } finally {
        $env:PG_MONITOR_PASSWORD = $null
        $env:DEMO_REPL_PASSWORD = $null
    }
    # 기본 이미지의 pg_hba 는 복제 접속을 루프백으로만 연다. 같은 도커 망(samenet)의 복제 계정만 비밀번호로 연다.
    $hbaLine = 'host replication repl samenet scram-sha-256'
    & docker exec chaos-pg sh -c 'grep -qxF "$0" "$PGDATA/pg_hba.conf" || printf "\n# local MSP demo standby replication (harness/scripts/demo-obs-up.ps1)\n%s\n" "$0" >> "$PGDATA/pg_hba.conf"' $hbaLine
    if ($LASTEXITCODE -ne 0) { throw 'pg_hba 항목 추가 실패' }
    Invoke-OldSql 'SELECT pg_reload_conf()' | Out-Null
    $hbaErrors = Invoke-OldSql 'SELECT count(*) FROM pg_hba_file_rules WHERE error IS NOT NULL'
    $hbaRule = Invoke-OldSql "SELECT count(*) FROM pg_hba_file_rules WHERE database = '{replication}' AND user_name = '{repl}' AND address = 'samenet'"
    if ($hbaErrors -ne '0' -or $hbaRule -ne '1') { throw "pg_hba 확인 실패(오류 $hbaErrors, 복제 항목 $hbaRule)" }
    Write-Host "  preload=$((Invoke-OldSql "SELECT current_setting('shared_preload_libraries')")), 슬롯=$((Invoke-OldSql "SELECT slot_name FROM pg_replication_slots WHERE slot_name = 'db_standby'")), 복제 pg_hba 1줄"
}

Write-DemoStep '4/7 앱 노드 설정(팔로워부터 하나씩)'
$composeArgs = Get-DemoComposeArgs $mode
function Test-ConfigCurrent([string] $Service, [string[]] $Extra = @()) {
    $want = ((& docker compose @composeArgs @Extra config --hash $Service) -replace '^\S+\s+', '').Trim()
    $have = (& docker inspect -f '{{index .Config.Labels "com.docker.compose.config-hash"}}' $Service 2>$null | Out-String).Trim()
    return ($want -and $want -eq $have)
}
$apiChanged = $false
foreach ($pair in @(@('chaos-api-2', 28080), @('chaos-api-1', 18080))) {
    $name, $port = $pair
    if ((Test-DemoRunning $name) -and (Test-ConfigCurrent $name)) { Write-Host "  $name 설정 그대로"; continue }
    Invoke-DemoCompose $mode up -d --no-deps $name
    Wait-DemoUntil "$name liveness 200" { (Get-DemoHttpCode "http://localhost:$port/actuator/health/liveness") -eq 200 } 240
    $apiChanged = $true
}
if ($apiChanged -or (Get-DemoHttpCode 'http://localhost:18080/actuator/health/readiness') -ne 200) {
    Start-Sleep -Seconds 15
    if ((Get-DemoHttpCode 'http://localhost:18080/actuator/health/readiness') -ne 200) {
        # 기본 compose 는 chaos-api-1 의 readiness 로 건강을 본다. 리더가 chaos-api-2 에 있으면 chaos-api-1 이 unhealthy 로 남는다.
        Write-Host '  리더가 chaos-api-2 에 있다. chaos-api-2 를 재시작해 리더를 chaos-api-1 로 돌린다' -ForegroundColor Yellow
        & docker restart chaos-api-2 | Out-Null
        Wait-DemoUntil 'chaos-api-1 readiness 200(리더)' { (Get-DemoHttpCode 'http://localhost:18080/actuator/health/readiness') -eq 200 } 120
        Wait-DemoUntil 'chaos-api-2 liveness 200' { (Get-DemoHttpCode 'http://localhost:28080/actuator/health/liveness') -eq 200 } 180
    }
}
# 조회 전용 노드. 조정기가 켜고 끈다. 멈춰 있고 설정이 다르면 만들기만 다시 한다. 떠 있으면(증설 중) 건드리지 않는다.
if (-not (Test-DemoRunning 'chaos-api-3') -and -not (Test-ConfigCurrent 'chaos-api-3' @('--profile', 'scale'))) {
    Invoke-DemoCompose $mode --profile scale up --no-deps --no-start chaos-api-3
    Write-Host '  chaos-api-3 을 지금 설정으로 다시 만들었다(켜지 않음)'
}

Write-DemoStep '5/7 봇 기준가와 나머지 서비스(오버레이 포함)'
Update-DemoBotPrices
# --wait 를 쓰지 않는다. compose v5 의 up --wait 는 한 번 돌고 끝나는 chaos-toxiproxy-init(종료코드 0)을 실패로 본다(2026-09-27 확인).
# 대신 핵심 서비스를 따로 기다린다.
Invoke-DemoCompose $mode up -d
Wait-DemoUntil 'chaos-api-1 readiness 200(리더)' { (Get-DemoHttpCode 'http://localhost:18080/actuator/health/readiness') -eq 200 } 240
Wait-DemoUntil 'chaos-api-2 liveness 200' { (Get-DemoHttpCode 'http://localhost:28080/actuator/health/liveness') -eq 200 } 240
Wait-DemoUntil '봇 실행 중' { Test-DemoRunning 'chaos-bots' } 60
if ($mode -eq 'patroni') { Wait-DemoUntil '두 앱 노드 거래 상태 ACTIVE' { Test-DemoTradingActive } 180 }
if ($mspChanged) {
    Write-Host '  로컬 MSP 화면이 바뀌어 Grafana 를 재시작한다'
    & docker restart chaos-grafana | Out-Null
}
if ($queriesChanged) {
    Write-Host '  수집기 확장 질의 파일이 바뀌어 수집기를 재시작한다'
    & docker restart $Global:Demo.ExporterServices | Out-Null
}
Wait-DemoUntil 'Grafana 응답' { (Get-DemoHttpCode "$($Global:Demo.GrafanaUrl)/api/health") -eq 200 } 120
Wait-DemoUntil 'Prometheus 응답' { (Get-DemoHttpCode "$($Global:Demo.PrometheusUrl)/-/ready") -eq 200 } 120
# 설정 파일(바인드 마운트)만 바뀐 경우를 위해 다시 읽힌다. 컨테이너를 다시 만들었으면 이미 읽었다.
Invoke-WebRequest -Method Post -Uri "$($Global:Demo.PrometheusUrl)/-/reload" -TimeoutSec 10 -UseBasicParsing | Out-Null

if (-not $SkipAdmin) {
    Write-DemoStep '6/7 관리 평면과 관문'
    Invoke-DemoAdminCompose up -d --no-deps --wait chaos-admin
    if (Test-Path -LiteralPath (Join-Path $harness 'demo\admin-gw.local')) {
        Invoke-DemoAdminCompose up -d --no-deps chaos-admin-gw
        # 관문(nginx)은 chaos-admin 주소를 기동 때 한 번 푼다. 관리 평면을 다시 만들었을 수 있어 다시 띄운다.
        & docker restart chaos-admin-gw | Out-Null
    } else {
        Write-Host '  harness/demo/admin-gw.local 이 없어 관문을 건너뛴다(harness/scripts/demo-admin-gw-init.sh 로 만든다)' -ForegroundColor Yellow
    }
}

Write-DemoStep '7/7 확인(수집이 한 바퀴 돌 때까지 40초 대기)'
Start-Sleep -Seconds 40
Show-Summary
Write-Host ''
Write-Host '호스트 프로세스(이 스크립트가 띄우지 않는다, C:\dev\exchange):'
Write-Host '  보고 서비스   python harness/scripts/demo-reporting-local.py serve'
Write-Host '  웹 개발 서버  harness\scripts\demo-web-local.cmd'
$pgContainer = if ($mode -eq 'patroni') { 'chaos-db-toolbox' } else { 'chaos-pg' }
Write-Host "  조정기       python harness/asg/capacity_reconciler.py --stack chaos --lb-conf harness/nginx/nginx.conf --pg-container $pgContainer --sd-file harness/demo/prometheus-sd.local/reader.json --log <경로>"
