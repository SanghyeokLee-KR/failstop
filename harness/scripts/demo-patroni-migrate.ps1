# 시연 스택의 DB 계층을 종전 비동기 구성(chaos-pg + chaos-pg-standby, ADR 0024)에서 Patroni 엄격 동기 두 노드(ADR 0036)로
# 한 번 옮긴다. 데이터(회원·봇·잔고·마켓·주문·체결·저널)는 chaos-pg 의 논리 덤프를 그대로 옮긴다. 옛 컨테이너와 볼륨
# (exchange-chaos_chaos-pgdata)은 멈추기만 하고 지우거나 고치지 않는다.
#
#   pwsh -NoProfile -File C:\dev\exchange\harness\scripts\demo-patroni-migrate.ps1 -PrepareOnly   Patroni 계층만 세운다(데이터 없음)
#   pwsh -NoProfile -File C:\dev\exchange\harness\scripts\demo-patroni-migrate.ps1 -Rehearse      세운 뒤 살아 있는 chaos-pg 를 덤프해
#                                                                                                 연습 DB(exchange_rehearsal)에 복원만 한다
#   pwsh -NoProfile -File C:\dev\exchange\harness\scripts\demo-patroni-migrate.ps1                 전환(쓰기 정지 창이 생긴다)
#
# 전환 전에 docker-compose.demo-admin.yml 의 관리 평면 DB 주소가 chaos-haproxy-b 인지 확인한다(이 스크립트가 관리 평면을 그 파일로
# 다시 만든다). 조정기는 전환 뒤 --pg-container chaos-db-toolbox 로 다시 띄운다(끝에 명령이 나온다).
#
# 왜 물리 복사(pg_basebackup)가 아니라 논리 덤프인가. chaos-pg 는 postgres:17-alpine(musl)이고 정렬 규칙이 en_US.utf8(libc)이다.
# Patroni 이미지는 Debian(glibc)이라 같은 이름의 정렬 규칙이 다르게 정렬한다. 데이터 파일을 그대로 옮기면 문자열 색인의 순서가
# 어긋난다. 논리 덤프는 새 클러스터에서 색인을 다시 만든다. 새 클러스터는 Patroni 가 initdb(데이터 체크섬 켬)로 만든다.
#
# 전환 순서.
#   1. Patroni 계층(etcd 세 표 → chaos-db-1 부트스트랩 → chaos-db-2 복제 → HAProxy 네 개, 도구, 수집기)을 세우고 동기 Standby 를 확인한다.
#      앱 계정(exchange)과 수집기 계정(failstop_monitor)은 post_bootstrap.sh 가 종전과 같은 비밀번호로 만든다.
#   2. 봇(chaos-bots)과 관리 평면(chaos-admin)을 멈추고 미물질화 주문이 0 이 될 때까지 기다린다. 새 주문이 들어오지 않게 하려는 것이다.
#      앱 노드는 덤프가 끝날 때까지 조회를 계속 받는다. 그동안 리더가 쓰는 스냅샷·리스 갱신·불변식 기록은 덤프 뒤라 옮겨지지 않는다.
#      새 리더가 저널을 다시 읽어 같은 상태를 만들고, 리스는 만료로 보여 새 epoch 로 잡는다.
#   3. chaos-pg 를 덤프한다(도구 컨테이너의 pg_dump 17.11, 디렉터리 형식 병렬 2). 역할은 비밀번호 없이 옮긴다(repl 제외).
#      덤프 앞뒤로 주요 표 건수와 잔고 합을 읽는다. 둘이 같으면 덤프도 그 값이다.
#   4. chaos-pg·대기 DB·그 수집기·옛 백업 작업과 앱 두 노드를 멈춘다(지우지 않는다). 조정기는 chaos-pg 를 읽지 못해 아무것도 하지 않는다.
#   5. 새 Primary 에 역할과 덤프를 복원하고 ANALYZE 한 뒤 감시 계정 절차(deploy/observability/provision-monitor.sql)를 다시 돈다.
#      주요 표 건수와 잔고 합을 옛 DB 와 견준다. 4~5 에서 실패하면 옛 컨테이너를 다시 켜서 전환 전 상태로 돌린다.
#   6. 앱을 리더 안전 순서로 다시 만든다(chaos-api-1 → chaos-api-2). chaos-api-3 은 만들기만 하고 켜지 않는다. 봇(기준가를 지금 시세로
#      맞춘 뒤)·관리 평면·Prometheus·Grafana·백업 작업을 Patroni 설정으로 다시 만든다.
#
# 되돌리기(종전 비동기 DB 로). 전환 뒤 Patroni 에 쓴 데이터는 chaos-pg 로 돌아가지 않는다. chaos-pg 는 전환 직전 상태 그대로다.
#   1. 쓰기를 멈춘다:  docker stop chaos-bots chaos-admin chaos-api-2 chaos-api-1
#   2. Patroni 계층을 멈추고 컨테이너만 지운다(볼륨 exchange-chaos_chaos-db-*·chaos-etcd-* 는 남는다. 다시 전환하면 그대로 쓴다):
#        docker stop chaos-db-ackrec chaos-db-1-exporter chaos-db-2-exporter chaos-db-toolbox chaos-haproxy-1 chaos-haproxy-2 `
#          chaos-haproxy-3 chaos-haproxy-b chaos-db-2 chaos-db-1 chaos-etcd-1 chaos-etcd-2 chaos-etcd-3
#        docker rm chaos-db-1 chaos-db-2      (demo-common.ps1 은 이 두 컨테이너가 있으면 patroni 모드로 판별한다)
#   3. harness/docker-compose.demo-admin.yml 에서 'Patroni 전환' 표시가 있는 줄을 되돌린다(관리 평면 DB 주소를
#      jdbc:postgresql://chaos-pg:5432/exchange 로, 쓰기 가능 탐지 줄 삭제, MSP 호스트 db-1·db-2 를 db-primary·db-standby 로).
#   4. 오버레이 없이 올린다(chaos-pg·대기 DB·수집기가 다시 뜨고 앱이 토식 경로로 돌아간다):
#        pwsh -NoProfile -File C:\dev\exchange\harness\scripts\demo-obs-up.ps1 -DbMode async
#   5. 조정기를 --pg-container chaos-pg 로 다시 띄운다.
param(
    # Patroni 계층만 세우고 끝낸다. 데이터를 옮기지 않는다.
    [switch] $PrepareOnly,
    # Patroni 계층을 세운 뒤 살아 있는 chaos-pg 를 덤프해 연습 DB 에 복원하고 시간만 잰다. 서비스는 멈추지 않는다.
    [switch] $Rehearse,
    # 복원 병렬 수. 새 Primary 는 2코어 상한이다.
    [int] $RestoreJobs = 3
)

. (Join-Path $PSScriptRoot 'demo-common.ps1')

$work = '/tmp/migrate'
$timing = [ordered]@{}
function Measure-DemoStep([string] $Name, [scriptblock] $Body) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    & $Body
    $sw.Stop()
    $timing[$Name] = [Math]::Round($sw.Elapsed.TotalSeconds, 1)
}

# 도구 컨테이너 안의 bash 명령. 파이프 중간 실패도 실패로 본다(pipefail). PGPASSWORD 는 도구 컨테이너의 환경(앱 계정)이고
# 옛 DB 와 새 DB 의 앱 계정 비밀번호가 같다.
function Invoke-ToolboxShell([string] $Command, [int] $TimeoutSec = 1800) {
    & docker exec $Global:Demo.Toolbox timeout $TimeoutSec bash -c "set -o pipefail; $Command"
    if ($LASTEXITCODE -ne 0) { throw "도구 컨테이너 명령 실패(종료코드 $LASTEXITCODE): $Command" }
}

# 옛 DB(chaos-pg) 직접 조회. 로컬 소켓이라 비밀번호가 없다.
function Invoke-OldSql([string] $Sql) {
    $out = & docker exec chaos-pg psql -U exchange -d exchange -X -A -t -q -v ON_ERROR_STOP=1 -c $Sql 2>$null
    if ($LASTEXITCODE -ne 0) { throw "chaos-pg 조회 실패: $Sql" }
    return (($out | Out-String).Trim())
}

# 옛 DB 와 새 DB 를 견줄 사실. 봇·관리 평면이 멈춘 뒤라 거래 표는 움직이지 않는다.
$factsSql = "SELECT json_build_object('users', (SELECT count(*) FROM users), 'accounts', (SELECT count(*) FROM accounts), " +
    "'markets', (SELECT count(*) FROM markets), 'orders', (SELECT count(*) FROM orders), 'trades', (SELECT count(*) FROM trades), " +
    "'command_journal', (SELECT count(*) FROM command_journal), 'max_cmd_seq', (SELECT max(seq) FROM command_journal), " +
    "'balances_sum', (SELECT coalesce(sum(balance), 0)::text FROM balances), 'balances_abs_sum', (SELECT coalesce(sum(abs(balance)), 0)::text FROM balances), " +
    "'flyway', (SELECT count(*) FROM flyway_schema_history))"

function Assert-Prerequisites {
    Write-DemoStep '0 사전 확인'
    & docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker 가 응답하지 않는다' }
    foreach ($f in @($Global:Demo.EnvChaos, $Global:Demo.EnvObs, $Global:Demo.EnvPatroni, $Global:Demo.ReporterToken)) {
        if (-not (Test-Path -LiteralPath $f)) { throw "$f 가 없다" }
    }
    & docker image inspect exchange-patroni-db:local --format '{{.Id}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'exchange-patroni-db:local 이미지가 없다. docker compose -p exchange-patroni -f harness/patroni/docker-compose.patroni.yml --env-file harness/patroni/.env.patroni build 로 만든다' }
    Write-Host '  env 파일 셋, 보고 토큰, DB 이미지 있음'
}

function Start-PatroniLayer {
    Write-DemoStep '1 Patroni 계층'
    if (Write-DemoPgQueries) { Write-Host '  수집기 확장 질의 파일을 만들었다(harness/demo/patroni.local/pg-queries.yaml)' }
    Initialize-DemoSd | Out-Null
    Invoke-DemoCompose patroni up -d --no-deps --wait $Global:Demo.EtcdServices
    Write-Host '  etcd 세 표 healthy'
    # 부트스트랩 리더를 db-1 로 정한다. 두 노드를 함께 띄우면 누가 먼저 초기화 키를 잡을지 모른다.
    Invoke-DemoCompose patroni up -d --no-deps chaos-db-1
    Wait-DemoUntil 'Patroni 리더 있음' { (Get-DemoLeader).Leader } 240
    Invoke-DemoCompose patroni up -d --no-deps chaos-db-2
    Wait-DemoUntil '동기 Standby 확인(엄격 동기)' { Test-DemoClusterHealthy } 300
    Invoke-DemoCompose patroni up -d --no-deps $Global:Demo.HaproxyServices $Global:Demo.Toolbox $Global:Demo.ExporterServices
    Wait-DemoUntil '도구 컨테이너 → HAProxy → Primary 연결' { (Invoke-DemoSql 'SELECT 1') -eq '1' } 60
    Show-DemoCluster
    $roles = Invoke-DemoSql "SELECT string_agg(rolname, ',' ORDER BY rolname) FROM pg_roles WHERE rolname IN ('exchange','failstop_monitor')"
    $dbs = Invoke-DemoSql "SELECT string_agg(datname, ',' ORDER BY datname) FROM pg_database WHERE datname IN ('exchange','infra_probe')"
    Write-Host "  계정 $roles, DB $dbs"
}

function Test-AlreadyMigrated {
    $v = Invoke-DemoSql "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'"
    return ($v -eq '1')
}

# 역할. 비밀번호를 옮기지 않는다(--no-role-passwords). 로그인 역할 둘(exchange, failstop_monitor)은 post_bootstrap.sh 가 같은
# 비밀번호로 이미 만들었고, 나머지(app_role, harness_role)는 로그인하지 않는다. 옛 대기 DB 의 복제 계정 repl 은 옮기지 않는다
# (Patroni 의 복제 계정은 replicator 다). 이미 있는 역할의 CREATE ROLE 오류만 허용한다.
# 멤버십 GRANT 의 'GRANTED BY exchange' 는 뗀다. 옛 DB 에서 exchange 는 부트스트랩 superuser 라 누구의 이름으로도 줄 수 있었지만, 새
# 클러스터의 부트스트랩 superuser 는 postgres 라 PostgreSQL 16 부터 exchange 이름으로 주는 것을 거절한다(2026-09-27 연습에서 확인).
function Copy-Roles {
    Invoke-ToolboxShell ("rm -rf $work && mkdir -p $work && " +
        "pg_dumpall -h chaos-pg -p 5432 -U exchange --roles-only --no-role-passwords " +
        "| grep -Ev '^(CREATE|ALTER) ROLE (repl|postgres)( |;)' " +
        "| sed -E 's/^(GRANT .*) GRANTED BY [A-Za-z0-9_\`"]+;$/\1;/' > $work/roles.sql")
    $log = & docker exec $Global:Demo.Toolbox sh -c "psql -d postgres -X -q -f $work/roles.sql 2>&1" | Out-String
    $errors = @($log -split "`n" | Where-Object { $_ -match 'ERROR' })
    # 다시 실행하면 앞선 실행이 만든 역할도 이미 있다. 속성(ALTER ROLE)은 매번 다시 맞춘다.
    $unexpected = @($errors | Where-Object { $_ -notmatch 'role "[a-z_]+" already exists' })
    if ($unexpected.Count -gt 0) { throw "역할 복원 오류: $($unexpected -join ' | ')" }
    Write-Host "  역할 복원(이미 있는 역할 $($errors.Count)건은 건너뜀)"
}

function Invoke-Dump {
    Invoke-ToolboxShell ("rm -rf $work/exchange.dir && pg_dump -h chaos-pg -p 5432 -U exchange -d exchange " +
        "-Fd -j 2 -Z 1 -f $work/exchange.dir")
    $size = & docker exec $Global:Demo.Toolbox du -sh "$work/exchange.dir"
    Write-Host "  덤프 $(($size | Out-String).Trim())"
}

# 복원. 오류가 하나라도 있으면 멈춘다(--exit-on-error 는 병렬 복원에서 원인을 흐리므로 쓰지 않고 출력으로 가른다).
function Invoke-Restore([string] $TargetDb) {
    $log = & docker exec $Global:Demo.Toolbox sh -c ("pg_restore -d $TargetDb -j $RestoreJobs $work/exchange.dir 2>&1; echo rc=`$?") | Out-String
    $lines = @($log -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $rc = $lines | Where-Object { $_ -match '^rc=' } | Select-Object -Last 1
    $errors = @($lines | Where-Object { $_ -match 'error' })
    if ($rc -ne 'rc=0' -or $errors.Count -gt 0) {
        $lines | Select-Object -First 40 | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
        throw "pg_restore 실패($rc, 오류 줄 $($errors.Count))"
    }
    Invoke-ToolboxShell "vacuumdb -d $TargetDb --analyze-only -j 2 -q"
    Write-Host '  복원과 ANALYZE 끝'
}

function ConvertTo-Facts([string] $Json) { return ($Json | ConvertFrom-Json | ConvertTo-Json -Compress) }

# 4~5 단계에서 실패했을 때 옛 구성으로 돌린다. 옛 컨테이너는 멈추기만 했으므로 그대로 켜면 전환 전 상태다.
function Restore-OldStack {
    Write-Host '전환을 멈추고 옛 구성을 다시 켠다' -ForegroundColor Yellow
    & docker start chaos-pg | Out-Null
    Wait-DemoUntil 'chaos-pg healthy' { (Get-DemoHealth 'chaos-pg') -eq 'running|healthy' } 120
    & docker start chaos-pg-standby chaos-pg-exporter chaos-pg-standby-exporter chaos-db-backup chaos-api-1 | Out-Null
    Wait-DemoUntil 'chaos-api-1 readiness 200(리더)' { (Get-DemoHttpCode 'http://localhost:18080/actuator/health/readiness') -eq 200 } 240
    & docker start chaos-api-2 chaos-bots chaos-admin | Out-Null
    # 반쯤 복원한 새 DB 를 비워 둔다. 남기면 다음 실행이 '이미 전환됨'으로 보고 건너뛴다.
    Invoke-DemoSql 'DROP DATABASE IF EXISTS exchange WITH (FORCE)' 'postgres' 300 | Out-Null
    Invoke-DemoSql 'CREATE DATABASE exchange OWNER exchange' 'postgres' 60 | Out-Null
    Write-Host '옛 구성으로 돌렸다. Patroni 계층은 떠 있고 exchange DB 는 비웠다. 원인을 고친 뒤 다시 실행한다.' -ForegroundColor Yellow
}

Assert-Prerequisites
Start-PatroniLayer

if ($PrepareOnly) {
    Write-Host ''
    Write-Host 'Patroni 계층만 세웠다(-PrepareOnly). 앱은 아직 chaos-pg 를 본다.'
    return
}

if ($Rehearse) {
    Write-DemoStep '연습: 살아 있는 chaos-pg → exchange_rehearsal(서비스는 그대로)'
    if (-not (Test-DemoRunning 'chaos-pg')) { throw 'chaos-pg 가 떠 있지 않다' }
    Invoke-DemoSql 'DROP DATABASE IF EXISTS exchange_rehearsal' 'postgres' 300 | Out-Null
    if ($null -eq (Invoke-DemoSql 'CREATE DATABASE exchange_rehearsal OWNER exchange' 'postgres' 60)) { throw '연습 DB 생성 실패' }
    Measure-DemoStep 'roles' { Copy-Roles }
    Measure-DemoStep 'dump' { Invoke-Dump }
    Measure-DemoStep 'restore_analyze' { Invoke-Restore 'exchange_rehearsal' }
    Write-Host "  옛 DB 사실(덤프 뒤): $(Invoke-OldSql $factsSql)"
    Write-Host "  연습 DB 사실: $(Invoke-DemoSql $factsSql 'exchange_rehearsal' 120)"
    Invoke-DemoSql 'DROP DATABASE exchange_rehearsal' 'postgres' 300 | Out-Null
    Invoke-ToolboxShell "rm -rf $work"
    Write-Host ''
    $timing.GetEnumerator() | ForEach-Object { [pscustomobject]@{ 단계 = $_.Key; 초 = $_.Value } } | Format-Table -AutoSize | Out-Host
    Write-Host '연습 DB 를 지웠다(서비스는 살아 있는 chaos-pg 를 계속 쓴다). 전환은 -Rehearse 없이 다시 실행한다.'
    return
}

if (Test-AlreadyMigrated) {
    Write-Host ''
    Write-Host 'Patroni 의 exchange DB 에 이미 스키마가 있다(전환 끝). 데이터를 다시 옮기지 않는다.' -ForegroundColor Yellow
    Write-Host '앱·관측 설정만 맞추려면 demo-obs-up.ps1 을 실행한다.'
    return
}
if (-not (Test-DemoRunning 'chaos-pg')) { throw 'chaos-pg 가 떠 있지 않아 옮길 데이터가 없다' }
$adminText = Get-Content -LiteralPath $Global:Demo.AdminOverlay -Raw -Encoding utf8
if ($adminText -notmatch 'SPRING_DATASOURCE_URL:\s*jdbc:postgresql://chaos-haproxy-b:5000/') {
    throw 'docker-compose.demo-admin.yml 의 관리 평면 DB 주소가 chaos-haproxy-b 가 아니다. 전환 전에 고친다(머리 주석)'
}

$t0 = Get-Date
Write-DemoStep '2 쓰기 정지(봇·관리 평면)와 드레인'
& docker stop chaos-bots chaos-admin | Out-Null
$drainSql = "SELECT count(*) FROM command_journal c WHERE c.command_type='NEW' AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = c.assigned_order_id)"
$script:drainStreak = 0
Wait-DemoUntil '미물질화 주문 0(두 번 연속)' {
    $script:drainStreak = if ((Invoke-OldSql $drainSql) -eq '0') { $script:drainStreak + 1 } else { 0 }
    $script:drainStreak -ge 2
} 120 2

Write-DemoStep '3 chaos-pg 덤프'
$before = Invoke-OldSql $factsSql
Measure-DemoStep 'roles' { Copy-Roles }
Measure-DemoStep 'dump' { Invoke-Dump }
$after = Invoke-OldSql $factsSql
Write-Host "  옛 DB 사실: $after"
if ((ConvertTo-Facts $before) -ne (ConvertTo-Facts $after)) {
    Write-Host "  덤프 동안 옛 DB 사실이 바뀌었다(덤프 앞 $before). 덤프는 그 사이 한 시점의 값이다" -ForegroundColor Yellow
}

$restored = $false
try {
    Write-DemoStep '4 옛 DB 계층과 앱 노드 정지(지우지 않는다)'
    & docker stop chaos-pg | Out-Null
    & docker stop chaos-api-2 chaos-api-1 chaos-db-backup chaos-pg-standby-exporter chaos-pg-exporter chaos-pg-standby | Out-Null
    Write-Host '  멈춤: chaos-pg, chaos-pg-standby, 두 수집기, 옛 백업 작업, chaos-api-1·2'

    Write-DemoStep '5 새 Primary 에 복원'
    Measure-DemoStep 'restore_analyze' { Invoke-Restore 'exchange' }
    $obs = Read-DemoObsEnv
    $env:PG_MONITOR_PASSWORD = $obs['DEMO_MONITOR_PASSWORD']
    try {
        Invoke-DemoSqlScript (Get-Content -LiteralPath (Join-Path $Global:Demo.Repo 'deploy\observability\provision-monitor.sql') -Raw) @('PG_MONITOR_PASSWORD')
    } finally { $env:PG_MONITOR_PASSWORD = $null }
    $newFacts = Invoke-DemoSql $factsSql 'exchange' 120
    Write-Host "  새 DB 사실: $newFacts"
    if ((ConvertTo-Facts $before) -eq (ConvertTo-Facts $after) -and (ConvertTo-Facts $newFacts) -ne (ConvertTo-Facts $after)) {
        throw '옛 DB 와 새 DB 의 건수·잔고 합이 다르다'
    }
    Write-Host '  건수·잔고 합·마이그레이션 수 확인'
    $restored = $true
} catch {
    Write-Host "전환 실패: $($_.Exception.Message)" -ForegroundColor Red
    if (-not $restored) { Restore-OldStack }
    throw
}

Write-DemoStep '6 앱과 나머지(Patroni 설정)'
Measure-DemoStep 'apps' {
    Invoke-DemoCompose patroni up -d --no-deps chaos-api-1
    Wait-DemoUntil 'chaos-api-1 readiness 200(리더)' { (Get-DemoHttpCode 'http://localhost:18080/actuator/health/readiness') -eq 200 } 300
    Invoke-DemoCompose patroni up -d --no-deps chaos-api-2
    Wait-DemoUntil 'chaos-api-2 liveness 200' { (Get-DemoHttpCode 'http://localhost:28080/actuator/health/liveness') -eq 200 } 240
    # 만들기만 한다. 조정기가 희망 대수에 따라 docker start 한다.
    Invoke-DemoCompose patroni --profile scale up --no-deps --no-start chaos-api-3
}
Wait-DemoUntil '두 앱 노드 거래 상태 ACTIVE' { Test-DemoTradingActive } 120
Update-DemoBotPrices
Invoke-DemoCompose patroni up -d --no-deps chaos-bots chaos-db-backup chaos-prometheus chaos-grafana
Invoke-DemoAdminCompose up -d --no-deps --wait chaos-admin
# 관문(nginx)은 chaos-admin 주소를 기동 때 한 번 푼다. 관리 평면을 다시 만들면 주소가 바뀔 수 있어 관문을 다시 띄운다.
if (Test-DemoRunning 'chaos-admin-gw') { & docker restart chaos-admin-gw | Out-Null }
Write-Host "  쓰기 정지 창(봇 정지 → 봇 재기동): $([Math]::Round(((Get-Date) - $t0).TotalSeconds, 0))초"
Invoke-ToolboxShell "rm -rf $work"

Write-DemoStep '끝'
Show-DemoCluster
$timing.GetEnumerator() | ForEach-Object { [pscustomobject]@{ 단계 = $_.Key; 초 = $_.Value } } | Format-Table -AutoSize | Out-Host
Write-Host '조정기를 다시 띄운다(부모 세션, C:\dev\exchange):'
Write-Host '  python harness/asg/capacity_reconciler.py --stack chaos --lb-conf harness/nginx/nginx.conf --pg-container chaos-db-toolbox --sd-file harness/demo/prometheus-sd.local/reader.json --log <경로>'
