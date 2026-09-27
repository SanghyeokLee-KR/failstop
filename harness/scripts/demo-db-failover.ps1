# 시연 스택(Patroni 판)의 DB 장애 시연 도구. 주 DB 강제 종료, 자동 승격·거래 재개 측정, 주문·체결 대조, 다음 촬영을 위한 초기화.
#
#   pwsh -NoProfile -File C:\dev\exchange\harness\scripts\demo-db-failover.ps1 -Action status   지금 상태(주 DB, 동기 예비, 거래 상태)
#   pwsh -NoProfile -File C:\dev\exchange\harness\scripts\demo-db-failover.ps1 -Action kill     현재 주 DB 컨테이너를 docker kill
#   pwsh -NoProfile -File C:\dev\exchange\harness\scripts\demo-db-failover.ps1 -Action return   멈춘 DB 노드를 다시 켜고 동기 복귀·거래 재개까지 기다린다
#   pwsh -NoProfile -File C:\dev\exchange\harness\scripts\demo-db-failover.ps1 -Action reset    다음 촬영 준비(아래)
#   pwsh -NoProfile -File C:\dev\exchange\harness\scripts\demo-db-failover.ps1 -Action run      측정 한 회차(아래)
#
# 모든 동작은 끝에 결과를 JSON 한 줄로 낸다(녹화 도구가 마지막 줄을 읽는다). 사람이 읽는 줄은 그 앞에 있다.
#
# 엄격 동기(ADR 0036)라 새 주 DB 는 동기 예비가 없으면 쓰기를 확인하지 않는다. 그래서 주 DB 를 죽이면 예비 DB 가 약 20~25초 뒤
# 자동 승격해 조회는 돌아오지만 거래(쓰기)는 '거래 일시 중단'으로 남고, 옛 주 DB 가 되감기(pg_rewind)로 예비로 돌아와 동기가 붙으면
# 사람 조작 없이 재개된다. AWS 에서는 옛 호스트의 복구(EC2 자동 복구·재부팅)나 새 멤버가 그 자리다. 이 스크립트는 그 복귀를
# docker start 로 흉내 낸다. docker kill 한 컨테이너는 Docker 가 스스로 다시 띄우지 않는다(restart 정책 무시, 2026-09-27 확인).
#
# run(측정 한 회차). 실험 스택의 E-P1(harness/scripts/ep-p1-primary-kill.ps1)과 같은 순서·도구를 시연 스택에 쓴다. 스택을 새로
# 세우지 않는다. 도구는 기존 것이다.
#   DB 수준 ACK 기록기 harness/scale/ack_recorder.py(컨테이너 chaos-db-ackrec, infra_probe.ack_probe)
#   관측기 harness/scale/patroni_observe.py(Patroni /cluster 0.5초, 노드별 거래 상태, 조회 대표 경로, 인증 없는 주문 POST, HAProxy)
#   (-WithK6) k6 접수 주행 harness/load/order_flow.js → harness/scripts/load-acks.ps1 → 사후 불변식 C16(ACK 받은 주문이 저널에 있는가).
#   기본은 끈다(-WithK6 설명). 끄면 대조는 ACK 기록기와 직전 60초 주문·체결 둘이다
#   판정 harness/scale/patroni_axes.py --scenario E-P1(멤버 이름 db-1·db-2)
#   순서: 주입 전 창 → 시계 가드 → 직전 60초에 확인된 주문·체결 목록 기록 → 주 DB docker kill(T0) → 승격 관찰 → T0+HoldSec 와
#   승격+3초 가운데 늦은 때 옛 주 DB docker start(-ManualReturn 이면 다른 창의 -Action return 을 기다린다) → 재개 → 재개 뒤 창 →
#   k6 정지·ACK 적재 → 봇 정지·드레인·사후 불변식 run·봇 재개(-NoPostCheck 로 뺀다) → 대조 → 판정.
#   대조는 셋이다. (1) ACK 기록기가 확인받은 쓰기가 최종 주 DB 에 모두 있는가. (2) 주입 직전 60초 동안 커밋이 보인 주문(저널)·체결이
#   최종 주 DB 에 모두 있는가(엄격 동기에서 다른 세션에 보인 커밋은 동기 예비가 받은 것이다, 설계 4-1). (3) (-WithK6) k6 가 접수 응답을 받은
#   주문이 저널에 모두 있는가(C16). 셋의 빠진 건수 합이 lost 다.
#   원자료(관측 표본, 기록기 기록, k6 로그, 키)는 저장소 밖 %TEMP%\exchange-demo-dbfail\<회차>\ 에, 판정·시각·사실·대조는
#   harness/evidence/demo-patroni-failover-<UTC 시각>/ 에 둔다.
#
# reset(다음 촬영 준비). 멈춘 DB 노드를 켜고 동기 복귀를 기다린 뒤, 주 DB 가 db-1 이 아니면 계획 전환(patronictl switchover)으로
# db-1 을 주 DB 로 되돌린다(-KeepLeader 면 두지 않는다). 앱 리더가 chaos-api-1 이 아니면 chaos-api-2 를 재시작한다(demo-obs-up.ps1 과
# 같은 규칙). 봇이 멈춰 있으면 켠다. 끝에 두 노드의 복제 상태(sync_state)를 확인한다. 계획 전환도 엄격 동기라 몇 초 쓰기가 멈춘다.
#
# 이 스크립트는 사람이 실행한다. AI 와 관리 콘솔은 DB 를 끄거나 승격·전환하지 않는다(ADR 0035·0036).
param(
    [ValidateSet('run', 'kill', 'return', 'reset', 'status')] [string] $Action = 'status',
    # run: T0 부터 옛 주 DB 를 다시 켤 때까지 최소(초). 승격을 본 뒤 3초 전에는 켜지 않는다(먼저 켜면 같은 노드가 리더 키를 되찾아
    # 전환이 없다). 기본 0 은 '승격 + 3초에 켠다'로 촬영에서 가장 짧은 복구다. 실험 스택 E-P1 과 같게 보려면 -HoldSec 60 을 준다.
    [int] $HoldSec = 0,
    [int] $PreWindowSec = 40,
    [int] $PostWindowSec = 40,
    # run: 옛 주 DB 를 자동으로 켜지 않고 다른 창에서 -Action return 을 기다린다(촬영에서 복구 장면을 사람이 정할 때).
    [switch] $ManualReturn,
    # run: k6 접수 주행과 C16 대조를 더한다. harness/load/order_flow.js 는 마켓마다 고정 가격(KRW-BTC 매수 49,900,000·매도 50,100,000)
    # 으로 GTC 주문을 내므로, 지우지 않는 시연 스택에서는 봇 호가와 체결해 시세를 끌어내리고 호가창에 벽을 남긴다(2026-09-27 두 회차에서
    # BTC 가 약 8,440만에서 7,480만으로 내려가고 미체결 534건이 남았다). 그래서 기본은 끄고, 켜면 회차 끝에 cleanup.js 로 이 회차 부하
    # 계정의 미체결을 모두 취소한다. 체결된 시세 변화는 되돌리지 않는다.
    [switch] $WithK6,
    # run: 봇 정지·드레인·사후 불변식 run 을 뺀다(촬영 중 봇을 멈추지 않을 때). 판정의 불변식 축은 판정 불가가 된다.
    [switch] $NoPostCheck,
    # reset: 주 DB 를 db-1 로 되돌리지 않는다.
    [switch] $KeepLeader
)

$env:CHAOS_PSQL_CONTAINER = 'chaos-db-toolbox'
# 주입 동안 psql 이 매달려 스크립트가 멈추지 않게 한다(lib.ps1 Invoke-Psql).
$env:CHAOS_PSQL_TIMEOUT_SEC = '6'
. (Join-Path $PSScriptRoot 'demo-common.ps1')
Set-StrictMode -Off
$runStamp = (Get-Date).ToUniversalTime()
$evidenceDir = Join-Path $Global:Demo.Harness ("evidence\demo-patroni-failover-" + $runStamp.ToString('yyyyMMdd-HHmm'))
if ($Action -eq 'run') { $env:CHAOS_EVIDENCE_DIR = $evidenceDir }
. (Join-Path $PSScriptRoot 'lib.ps1')

$scaleDir = Join-Path $Global:Demo.Harness 'scale'
$python = if ($env:EP_PYTHON) { $env:EP_PYTHON } else { 'python' }
$k6Name = 'chaos-dbfail-k6'
$preferredLeader = 'db-1'

function Write-ResultLine($Object) {
    # 마지막 줄. 한 줄 JSON 이다.
    Write-Output ($Object | ConvertTo-Json -Compress -Depth 6)
}

function Round3($v) { if ($null -eq $v) { return $null } return [Math]::Round([double]$v, 3) }

function Get-DemoLeaseEpoch {
    $v = Invoke-DemoSql "SELECT max(epoch) FROM leader_lease WHERE resource = 'matching-engine'" 'exchange' 6
    if ([string]::IsNullOrWhiteSpace($v)) { return $null }
    return [long]$v
}

function Get-StoppedMembers {
    return @($Global:Demo.Members.GetEnumerator() | Where-Object { -not (Test-DemoRunning $_.Value) } | ForEach-Object { $_.Key })
}

function Start-DemoDbContainer([string] $Container, [int] $TimeoutSec = 30) {
    # 멈춘 동안 다른 프로세스가 호스트 포트를 잡으면 docker start 가 실패한다(실험 스택 E-P2a 사례). 2초마다 다시 시도한다.
    if ($Container -notmatch '^chaos-db-[12]$') { throw "이 도구가 다루지 않는 컨테이너: $Container" }
    $until = (Get-Date).AddSeconds($TimeoutSec)
    while ($true) {
        $out = (& docker start $Container 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -eq 0) { return }
        Write-Host "  docker start $Container 실패: $out" -ForegroundColor Yellow
        if ((Get-Date) -ge $until) { throw "docker start $Container 가 ${TimeoutSec}초 안에 성공하지 못했다" }
        Start-Sleep -Seconds 2
    }
}

. (Join-Path $PSScriptRoot 'demo-rejoin.ps1')

function Get-ReplicationRows {
    $raw = Invoke-DemoSql "SELECT coalesce(json_agg(json_build_object('app', application_name, 'state', state, 'sync_state', sync_state)), '[]') FROM pg_stat_replication" 'exchange' 6
    if (-not $raw) { return @() }
    return @($raw | ConvertFrom-Json)
}

function Get-StatusObject {
    $c = Get-DemoLeader
    $repl = Get-ReplicationRows
    $states = [ordered]@{}
    foreach ($entry in $Global:Demo.ApiPorts.GetEnumerator()) {
        $s = Get-DemoTradingState $entry.Value
        $states[$entry.Key] = $(if ($s) { if ($s.trading_suspended) { "SUSPENDED:$($s.reason)" } else { 'ACTIVE' } } else { 'NO_RESPONSE' })
    }
    # 배열은 변수에 담아 넣는다. $( ) 안에서 만든 한 원소 배열은 풀려서 JSON 에 문자열로 나간다.
    [object[]] $sync = @(); [object[]] $members = @()
    if ($c) { $sync = @($c.Sync); $members = @($c.Members | ForEach-Object { "$($_.name):$($_.role):$($_.state)" }) }
    [object[]] $stopped = @(Get-StoppedMembers)
    [object[]] $replication = @($repl | ForEach-Object { "$($_.app):$($_.state):$($_.sync_state)" })
    return [ordered]@{
        primary = $(if ($c) { $c.Leader } else { $null })
        primary_container = $(if ($c -and $c.Leader) { $Global:Demo.Members[$c.Leader] } else { $null })
        sync_standby = $sync
        timeline = $(if ($c) { $c.Timeline } else { $null })
        members = $members
        stopped = $stopped
        replication = $replication
        trading = $states
        app_leader = Get-DemoAppLeader
        lease_epoch = Get-DemoLeaseEpoch
    }
}

function Invoke-Status {
    Write-DemoStep 'DB 계층 상태'
    Show-DemoCluster
    $s = Get-StatusObject
    Write-Host "  복제: $($s.replication -join ', ')"
    Write-Host "  거래: $(($s.trading.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ', ')  앱 리더 $($s.app_leader)  리스 epoch $($s.lease_epoch)"
    $s['action'] = 'status'
    Write-ResultLine $s
}

function Invoke-Kill {
    $c = Get-DemoLeader
    if (-not $c -or -not $c.Leader) { throw 'Patroni 리더를 읽지 못했다. 주 DB 가 이미 없을 수 있다(-Action status)' }
    if ($c.Sync.Count -lt 1) { Write-Host '  주의: 동기 예비 DB 가 없다. 죽이면 승격할 노드가 없다' -ForegroundColor Yellow }
    $container = $Global:Demo.Members[$c.Leader]
    $t0 = Get-DemoNow
    & docker kill $container | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker kill $container 실패" }
    Write-Host ("주 DB {0}({1}) 강제 종료 T0={2}" -f $c.Leader, $container, (ConvertTo-IsoUtc $t0))
    return [ordered]@{ action = 'kill'; killed_member = $c.Leader; killed_container = $container; sync_standby_before = @($c.Sync)
        timeline_before = $c.Timeline; t0 = (Round3 $t0); t0_utc = (ConvertTo-IsoUtc $t0) }
}


# 멈춘 DB 노드를 켜고 동기 복귀와 거래 재개(두 앱 노드 ACTIVE 두 번 연속)를 기다린다. 켠 노드가 예비로 붙지 못하면 reinit 으로
# 넘어간다(New-RejoinWatch 주석). 켜져 있지만 붙지 못한 채 머문 노드(앞 회차가 남긴 상태)도 같이 지켜본다.
function Invoke-Return([int] $TimeoutSec = 300) {
    $t = Get-DemoNow
    [object[]] $stopped = @(Get-StoppedMembers)
    foreach ($m in $stopped) { Start-DemoDbContainer $Global:Demo.Members[$m] }
    if ($stopped.Count -gt 0) { Write-Host "  다시 켬: $($stopped -join ', ')" }
    $leader = (Get-DemoLeader).Leader
    $watches = @($Global:Demo.Members.Keys | Where-Object { $_ -ne $leader } | ForEach-Object {
        $w = New-RejoinWatch $_ $t
        # 이번에 켜지 않은 노드가 이미 붙어 있으면 되감기를 한 것이 아니다.
        if ($_ -notin $stopped) { $w.Method = 'already_streaming' }
        $w })
    $syncAt = $null; $activeAt = $null; $streak = 0
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        foreach ($w in $watches) { Step-RejoinWatch $w }
        if (-not $syncAt -and (Test-DemoClusterHealthy)) { $syncAt = Get-DemoNow }
        if (Test-DemoTradingActive) { $streak++ } else { $streak = 0 }
        if ($syncAt -and $streak -ge 2) { $activeAt = Get-DemoNow; break }
        Start-Sleep -Seconds 1
    }
    if (-not $activeAt) { throw "동기 복귀와 거래 재개를 ${TimeoutSec}초 안에 확인하지 못했다(-Action status 로 본다)" }
    Write-Host ("  동기 복귀 {0:N1}초, 거래 재개 {1:N1}초(켠 뒤)" -f ($syncAt - $t), ($activeAt - $t))
    [object[]] $rejoin = @($watches | ForEach-Object { [ordered]@{ member = $_.Member; method = $_.Method; reason = $_.Reason
        reinit_after_s = $(if ($_.ReinitAt) { Round3 ($_.ReinitAt - $t) } else { $null }) } })
    return [ordered]@{ action = 'return'; started = $stopped; t_back = (Round3 $t); sync_after_s = (Round3 ($syncAt - $t))
        active_after_s = (Round3 ($activeAt - $t)); rejoin = $rejoin }
}

function Invoke-Reset {
    Write-DemoStep '초기화: 멈춘 노드 복귀'
    $ret = Invoke-Return
    $switched = $false
    $c = Get-DemoLeader
    if (-not $KeepLeader -and $c.Leader -ne $preferredLeader) {
        Write-DemoStep "초기화: 계획 전환 $($c.Leader) → $preferredLeader(patronictl switchover)"
        $container = $Global:Demo.Members[$preferredLeader]
        & docker exec $container patronictl -c /patroni/patroni.yml switchover --leader $c.Leader --candidate $preferredLeader --force
        if ($LASTEXITCODE -ne 0) { throw 'patronictl switchover 실패' }
        Wait-DemoUntil "리더 $preferredLeader" { (Get-DemoLeader).Leader -eq $preferredLeader } 120 1
        Wait-DemoUntil '동기 예비 복귀' { Test-DemoClusterHealthy } 180 1
        Wait-DemoUntil '두 앱 노드 거래 상태 ACTIVE' { Test-DemoTradingActive } 180 2
        $switched = $true
    }
    Write-DemoStep '초기화: 앱 파티션 차단기·앱 리더·봇'
    # DB 전환마다 옛 앱 리더는 리스 갱신을 거부당해 한 번 강등된다(설계 5-1 5). 계획 전환도 같다. 10분에 세 번이면 그 노드의 차단기가
    # 열려 리스를 노리지 않으므로(2026-09-27 연속 촬영 연습에서 chaos-api-1 이 막힘) 다음 촬영 전에 두 노드의 차단기를 푼다.
    # 운영자용 쓰기(/actuator/partitions reset_breaker)는 loopback 에서만 받으므로 컨테이너 안에서 부른다.
    foreach ($c in $Global:Demo.ApiPorts.Keys) {
        $code = & docker exec $c curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' `
            -d '{"resource":"matching-engine","reset_breaker":true}' http://localhost:8080/actuator/partitions 2>$null
        Write-Host "  $c 파티션 차단기 해제 HTTP $code"
    }
    if ((Get-DemoHttpCode 'http://localhost:18080/actuator/health/readiness') -ne 200) {
        Start-Sleep -Seconds 10
        if ((Get-DemoHttpCode 'http://localhost:18080/actuator/health/readiness') -ne 200) {
            Write-Host '  앱 리더가 chaos-api-1 이 아니다. chaos-api-2 를 재시작해 리더를 chaos-api-1 로 돌린다' -ForegroundColor Yellow
            & docker restart chaos-api-2 | Out-Null
            Wait-DemoUntil 'chaos-api-1 readiness 200(리더)' { (Get-DemoHttpCode 'http://localhost:18080/actuator/health/readiness') -eq 200 } 120
            Wait-DemoUntil 'chaos-api-2 liveness 200' { (Get-DemoHttpCode 'http://localhost:28080/actuator/health/liveness') -eq 200 } 180
        }
    }
    if (-not (Test-DemoRunning 'chaos-bots')) { & docker start chaos-bots | Out-Null; Write-Host '  봇을 켰다' }
    foreach ($leftover in @('chaos-db-ackrec', $k6Name)) {
        if ((& docker ps -a --filter "name=^$leftover$" --format '{{.Names}}')) { & docker rm -f $leftover | Out-Null }
    }
    Wait-DemoUntil '두 앱 노드 거래 상태 ACTIVE' { Test-DemoTradingActive } 120 2
    $s = Get-StatusObject
    $syncRows = @($s.replication | Where-Object { $_ -like '*:streaming:sync' })
    Show-DemoCluster
    Write-Host "  복제: $($s.replication -join ', ')  앱 리더 $($s.app_leader)"
    if ($syncRows.Count -lt 1) { throw "초기화 뒤 동기 복제(sync_state=sync)가 없다: $($s.replication -join ', ')" }
    $s['action'] = 'reset'; $s['switched_over'] = $switched; $s['return'] = $ret
    Write-ResultLine $s
}

# ── run ────────────────────────────────────────────────────────────────────

# 옵저버와 판정 도구가 쓰는 앱 노드 이름(api-1·api-2)과 주소.
$observerArgs = @('--lb', 'http://127.0.0.1:58080', '--node', 'api-1=http://127.0.0.1:18080', '--node', 'api-2=http://127.0.0.1:28080',
    '--patroni', $Global:Demo.PatroniRest['db-1'], '--patroni', $Global:Demo.PatroniRest['db-2'],
    '--haproxy', "haproxy-1=$($Global:Demo.Haproxy['haproxy-1'])", '--haproxy', "haproxy-2=$($Global:Demo.Haproxy['haproxy-2'])")

function Get-NodeMetricsText([int] $Port) {
    try { return (Invoke-WebRequest -Uri "http://127.0.0.1:$Port/actuator/prometheus" -TimeoutSec 5 -UseBasicParsing).Content } catch { return $null }
}

# 실험 스택 실행기(ep-lib.ps1 ConvertFrom-EpMetricsText)와 같은 계수를 같은 방식으로 읽는다.
function ConvertFrom-MetricsText([string] $Text) {
    $sum = {
        param([string] $Name, [string] $LabelPattern)
        $total = 0.0
        foreach ($m in [regex]::Matches($Text, "(?m)^$Name(?:\{([^}]*)\})?\s+(\S+)")) {
            if ($LabelPattern -and $m.Groups[1].Value -notmatch $LabelPattern) { continue }
            $total += [double]::Parse($m.Groups[2].Value, [Globalization.CultureInfo]::InvariantCulture)
        }
        return $total
    }
    return [ordered]@{
        demotions = (& $sum 'exchange_partition_demotions_total' $null) + (& $sum 'exchange_leader_standdown_total' $null) +
            (& $sum 'exchange_leader_recoverable_standdown_total' $null)
        lease_acquire_expired = (& $sum 'exchange_lease_acquire_expired_total' $null)
        renew_rejected = (& $sum 'exchange_leader_lease_renew_total' 'outcome="rejected"')
        gate_423 = (& $sum 'exchange_write_gate_rejected_total' 'response="423"')
        gate_503 = (& $sum 'exchange_write_gate_rejected_total' 'response="503"')
        probe_unconfirmed = (& $sum 'exchange_write_probe_unconfirmed_total' $null)
        definitive_deferred = (& $sum 'exchange_write_definitive_deferred_total' $null)
        orders_500 = (& $sum 'http_server_requests_seconds_count' 'status="500".*uri="/api/v1/orders|uri="/api/v1/orders.*status="500"')
        commit_recovered = (& $sum 'exchange_order_intake_commit_recovered_total' $null)
        unresolved = (& $sum 'exchange_order_intake_unresolved_total' $null)
        unavailable = (& $sum 'exchange_order_intake_unavailable_total' $null)
    }
}

function Get-MetricsTotal {
    $total = [ordered]@{}
    foreach ($entry in $Global:Demo.ApiPorts.GetEnumerator()) {
        $text = Get-NodeMetricsText $entry.Value
        if (-not $text) { continue }
        $m = ConvertFrom-MetricsText $text
        foreach ($k in $m.Keys) { $total[$k] = [double]($total[$k]) + [double]$m[$k] }
    }
    return $total
}

function Get-NodeMetrics([string] $Container) {
    $text = Get-NodeMetricsText $Global:Demo.ApiPorts[$Container]
    if (-not $text) { return $null }
    return ConvertFrom-MetricsText $text
}

function Get-PromScalar([string] $Query, [double] $At = 0) {
    $body = @{ query = $Query }
    if ($At -gt 0) { $body['time'] = $At.ToString([Globalization.CultureInfo]::InvariantCulture) }
    try {
        $r = Invoke-RestMethod -Uri "$($Global:Demo.PrometheusUrl)/api/v1/query" -Body $body -TimeoutSec 10
        if ($r.status -ne 'success' -or -not $r.data.result) { return $null }
        $v = $r.data.result[0].value[1]
        if ($v -eq 'NaN') { return $null }
        return [double]::Parse($v, [Globalization.CultureInfo]::InvariantCulture)
    } catch { return $null }
}

function Get-HostCpu {
    try { return [double](Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'").PercentProcessorTime } catch { return $null }
}

# 회차 무효 조건(c)의 실험 밖 부하 도구(ep-lib.ps1 Get-EpForeignLoad 와 같은 기준). 이 회차의 k6 는 뺀다.
function Get-ForeignLoad {
    $candidates = @()
    foreach ($p in @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)) {
        $cmd = [string]$p.CommandLine
        if ($p.Name -match '^k6(\.exe)?$') { $candidates += [pscustomobject]@{ Id = $p.ProcessId; Kind = 'k6' } }
        elseif ($p.Name -match '^java(w)?\.exe$' -and $cmd -match 'GradleDaemon|GradleWorkerMain|junit|surefire') {
            $candidates += [pscustomobject]@{ Id = $p.ProcessId; Kind = 'java-gradle' }
        } elseif ($p.Name -match '^python' -and $cmd -match 'read-load\.py') {
            $candidates += [pscustomobject]@{ Id = $p.ProcessId; Kind = 'read-load' }
        }
    }
    $before = @{}
    foreach ($c in $candidates) {
        $proc = Get-Process -Id $c.Id -ErrorAction SilentlyContinue
        if ($proc) { $before[$c.Id] = $proc.TotalProcessorTime.TotalMilliseconds }
    }
    Start-Sleep -Seconds 2
    $found = @()
    foreach ($c in $candidates) {
        $proc = Get-Process -Id $c.Id -ErrorAction SilentlyContinue
        if (-not $proc -or -not $before.ContainsKey($c.Id)) { continue }
        $cpuMs = $proc.TotalProcessorTime.TotalMilliseconds - $before[$c.Id]
        if ($cpuMs -gt 200) { $found += ('{0}(pid {1}, {2}ms/2s)' -f $c.Kind, $c.Id, [int]$cpuMs) }
    }
    foreach ($c in @(& docker ps --format '{{.Names}} {{.Image}}' 2>$null)) {
        if ($c -match 'k6' -and $c -notmatch "^$k6Name ") { $found += "container:$($c.Split(' ')[0])" }
    }
    return $found
}

# chaos-net 주소(ACK 기록기의 inet_server_addr() 와 같은 모양, 주소/32)를 멤버 이름으로.
function Get-DbIps {
    $map = [ordered]@{}
    foreach ($entry in $Global:Demo.Members.GetEnumerator()) {
        $ip = (& docker inspect -f '{{(index .NetworkSettings.Networks "exchange-chaos_chaos-net").IPAddress}}' $entry.Value 2>$null | Out-String).Trim()
        if ($ip) { $map[$entry.Key] = "$ip/32" }
    }
    return $map
}

# 두 노드의 주요 표 건수·합(각 노드의 로컬 소켓, E-P6a 기록과 같은 모양). 옛 주 DB 가 되감기로 돌아온 뒤 같아야 한다.
# -CutoffIso 를 주면 그 시각 전에 만든 행만 센다(봇을 멈추지 않은 회차, 아래 Get-RejoinFacts). 잔고 합은 시각으로 자를 수 없어 뺀다.
function Get-NodeTableFacts([string] $Container, [string] $CutoffIso) {
    if ($CutoffIso) {
        $w = "created_at < '$CutoffIso'"
        $sql = "SELECT json_build_object('cutoff', '$CutoffIso', " +
            "'command_journal', (SELECT count(*) FROM command_journal WHERE $w), " +
            "'event_journal', (SELECT count(*) FROM event_journal WHERE $w), 'orders', (SELECT count(*) FROM orders WHERE $w), " +
            "'trades', (SELECT count(*) FROM trades WHERE $w), 'max_cmd_seq', (SELECT max(seq) FROM command_journal WHERE $w))"
    } else {
        $sql = "SELECT json_build_object('command_journal', (SELECT count(*) FROM command_journal), " +
            "'event_journal', (SELECT count(*) FROM event_journal), 'orders', (SELECT count(*) FROM orders), " +
            "'trades', (SELECT count(*) FROM trades), 'max_cmd_seq', (SELECT max(seq) FROM command_journal), " +
            "'balances_abs_sum', (SELECT coalesce(sum(abs(balance)), 0)::text FROM balances))"
    }
    $raw = (& docker exec $Container timeout 60 psql -h /var/run/postgresql -U postgres -d exchange -Atq -c $sql 2>$null | Out-String).Trim()
    if (-not $raw) { return $null }
    return $raw | ConvertFrom-Json
}

# 봇을 멈추지 않은 회차(-NoPostCheck)는 -CutoffIso 로 주입 10초 전까지 만든 행만 비교한다. 거래가 다시 도는 동안 두 노드를 차례로
# 세면 그 사이 들어온 행만큼 어긋난다(2026-09-27 촬영 두 회차에서 주문 4~5건 차이의 거짓 불일치). 그 전의 행은 엄격 동기로 두 노드에
# 이미 있었고 뒤에 더 생기지 않으므로, 부하와 상관없이 되감기가 옛 주 DB 의 과거를 어긋나게 남겼는지 가린다.
function Get-RejoinFacts([string] $OldContainer, [string] $CutoffIso) {
    $log = (& cmd /c "docker logs $OldContainer 2>&1" | Out-String)
    $method = if ($log -match 'pg_rewind') { 'rewind' } elseif ($log -match 'removing data directory|reinitializ') { 'reinit' } else { 'none-seen' }
    # 복제가 따라잡을 때까지 둘 다 같은 값을 낼 때까지 몇 번 읽는다(-CutoffIso 가 없으면 부하는 이미 멈췄다).
    $a = $null; $b = $null; $equal = $null
    for ($i = 0; $i -lt 10; $i++) {
        $a = Get-NodeTableFacts 'chaos-db-1' $CutoffIso
        $b = Get-NodeTableFacts 'chaos-db-2' $CutoffIso
        if ($a -and $b) { $equal = (($a | ConvertTo-Json -Compress) -eq ($b | ConvertTo-Json -Compress)) }
        if ($equal) { break }
        Start-Sleep -Seconds 2
    }
    $scope = if ($CutoffIso) { 'before_t0' } else { 'all' }
    return [ordered]@{ old_primary = $OldContainer; method = $method; scope = $scope; nodes = [ordered]@{ 'chaos-db-1' = $a; 'chaos-db-2' = $b }; equal = $equal }
}

# 주입 직전 60초 동안 커밋이 보인 주문(저널 NEW)과 체결(각 최근 2000건까지. 대조 SQL 이 명령줄 길이 안에 들게 한다).
# 엄격 동기에서 다른 세션에 보인 커밋은 동기 예비가 이미 받은 것이다.
$visibleSql = "SELECT json_build_object('t', extract(epoch FROM clock_timestamp()), " +
    "'orders', (SELECT coalesce(json_agg(seq), '[]') FROM (SELECT seq FROM command_journal WHERE command_type = 'NEW' " +
    "AND created_at > now() - interval '60 seconds' ORDER BY seq DESC LIMIT 2000) o), " +
    "'trades', (SELECT coalesce(json_agg(id), '[]') FROM (SELECT id FROM trades WHERE created_at > now() - interval '60 seconds' " +
    "ORDER BY id DESC LIMIT 2000) t))"

function Test-VisibleSurvived($Visible) {
    $orders = @($Visible.orders); $trades = @($Visible.trades)
    $o = if ($orders.Count -gt 0) { Invoke-DemoSql ("SELECT count(*) FROM command_journal WHERE seq = ANY('{" + ($orders -join ',') + "}'::bigint[])") 'exchange' 60 } else { '0' }
    $t = if ($trades.Count -gt 0) { Invoke-DemoSql ("SELECT count(*) FROM trades WHERE id = ANY('{" + ($trades -join ',') + "}'::bigint[])") 'exchange' 60 } else { '0' }
    if ($null -eq $o -or $null -eq $t) { throw '주문·체결 대조 조회 실패' }
    return [ordered]@{ orders_checked = $orders.Count; orders_present = [long]$o; orders_missing = $orders.Count - [long]$o
        trades_checked = $trades.Count; trades_present = [long]$t; trades_missing = $trades.Count - [long]$t }
}

# 쓰기 중단 구간 둘(관측기의 주문 POST 가 401 이 아닌 구간, ACK 기록기의 확인 공백). 원자료가 수만 줄이라 파이썬 도구로 센다
# (harness/scale/failover_gaps.py, 정의는 그 머리 주석).
function Get-FailoverGaps([string] $Raw, [double] $T0) {
    $out = & $python (Join-Path $scaleDir 'failover_gaps.py') --observe (Join-Path $Raw 'observe.jsonl') --ackrec (Join-Path $Raw 'ackrec.jsonl') `
        --t0 $T0.ToString([Globalization.CultureInfo]::InvariantCulture)
    if ($LASTEXITCODE -ne 0) { return $null }
    return (($out | Out-String).Trim() | ConvertFrom-Json)
}

function ConvertFrom-DbTimestamp([string] $Raw) {
    if ([string]::IsNullOrWhiteSpace($Raw)) { return $null }
    return ([DateTimeOffset]::Parse($Raw.Trim().Replace(' ', 'T') + 'Z')).ToUnixTimeMilliseconds() / 1000.0
}

function Start-Recorder([string] $Raw) {
    $init = "CREATE TABLE IF NOT EXISTS ack_probe (n bigint PRIMARY KEY, writer int NOT NULL, t_client timestamptz NOT NULL); TRUNCATE ack_probe;"
    if ($null -eq (Invoke-DemoSql $init 'infra_probe' 30)) { throw 'ack_probe 준비 실패' }
    $env:DEMO_FAILOVER_OUT = $Raw
    Remove-Item (Join-Path $Raw 'ackrec.stop') -ErrorAction SilentlyContinue
    Invoke-DemoCompose patroni --profile dbfail up -d --no-deps --force-recreate chaos-db-ackrec
}

function Stop-Recorder([string] $Raw) {
    New-Item -ItemType File -Force (Join-Path $Raw 'ackrec.stop') | Out-Null
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline -and (Test-DemoRunning 'chaos-db-ackrec')) { Start-Sleep -Milliseconds 500 }
    if (Test-DemoRunning 'chaos-db-ackrec') { & docker stop -t 5 chaos-db-ackrec | Out-Null }
    & cmd /c "docker logs chaos-db-ackrec > `"$(Join-Path $Raw 'ackrec.container.log')`" 2>&1"
    $env:DEMO_FAILOVER_OUT = $Raw
    $composeArgs = Get-DemoComposeArgs 'patroni'
    & docker compose @composeArgs --profile dbfail run --rm --no-deps chaos-db-ackrec verify --log /out/ackrec.jsonl --out /out/ackrec-verify.json *> (Join-Path $Raw 'ackrec-verify.log')
    Write-Host "  기록기 대조 종료코드 $LASTEXITCODE(0 이면 빠진 번호 0)"
    & docker rm -f chaos-db-ackrec 2>$null | Out-Null
}

function Start-K6([string] $Raw, [string] $KeysPath, [string] $RunId) {
    $load = Join-Path $Global:Demo.Harness 'load'
    Copy-Item $KeysPath (Join-Path $Raw 'k6_keys.json') -Force
    & docker rm -f $k6Name 2>$null | Out-Null
    $rid = $RunId.Replace('-', '').Substring(0, [Math]::Min(16, $RunId.Replace('-', '').Length))
    & docker run -d --name $k6Name --network exchange-chaos_chaos-net -v "${load}:/w:ro" -v "${Raw}:/raw:ro" -w /w `
        grafana/k6:1.4.0 run --quiet -e RUN_ID=$rid -e BASE_URL=http://chaos-lb:8080 -e K6_KEYS=/raw/k6_keys.json -e PROFILE=e6 `
        -e STEADY_RATE=10 -e SPIKE_RATE=10 -e READ_RATE=1 -e WS_VUS=1 order_flow.js | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'k6 기동 실패' }
}

function Stop-K6([string] $Raw) {
    $running = (& docker inspect -f '{{.State.Running}}' $k6Name 2>$null | Out-String).Trim()
    if (-not $running) { return $null }
    if ($running -eq 'true') { & docker stop -t 10 $k6Name | Out-Null }
    $log = Join-Path $Raw 'k6.log'
    & cmd /c "docker logs $k6Name > `"$log`" 2>&1"
    & docker rm -f $k6Name | Out-Null
    return $log
}

# 이 회차 부하 계정의 미체결 주문을 모두 취소한다(harness/load/cleanup.js, LB 를 지나지 않고 앱 노드로 직접 보낸다).
function Clear-K6Orders([string] $Raw, [string] $KeysPath) {
    $load = Join-Path $Global:Demo.Harness 'load'
    Copy-Item $KeysPath (Join-Path $Raw 'cleanup_keys.json') -Force
    & docker run --rm --name chaos-dbfail-cleanup --network exchange-chaos_chaos-net -v "${load}:/w:ro" -v "${Raw}:/raw:ro" -w /w `
        -e K6_KEYS=/raw/cleanup_keys.json -e CLEANUP_BASE=http://chaos-api-1:8080 -e CLEANUP_BASE_ALT=http://chaos-api-2:8080 `
        grafana/k6:1.4.0 run --quiet --no-color cleanup.js *> (Join-Path $Raw 'k6-cleanup.log')
    $left = Invoke-DemoSql "SELECT count(*) FROM orders o JOIN harness.loadtest_accounts a ON a.user_id = o.user_id WHERE o.status = 'OPEN'" 'exchange' 60
    Write-Host "  k6 미체결 취소(cleanup.js), 남은 부하 계정 미체결 $left 건"
}
function Write-JsonFile([string] $Path, $Object) {
    [System.IO.File]::WriteAllText($Path, ($Object | ConvertTo-Json -Depth 12), [System.Text.UTF8Encoding]::new($false))
}

function Invoke-Run {
    if ((Get-DemoDbMode) -ne 'patroni') { throw 'Patroni 판이 아니다(demo-patroni-migrate.ps1 로 먼저 옮긴다)' }
    $runId = 'DBFAIL-' + $runStamp.ToString('yyyyMMdd-HHmmss')
    $raw = Join-Path ([System.IO.Path]::GetTempPath()) "exchange-demo-dbfail\$runId"
    New-Item -ItemType Directory -Force $raw, $evidenceDir | Out-Null
    $tl = [ordered]@{ scenario = 'E-P1'; run_id = $runId; stack = 'exchange-chaos(demo, Patroni)'; members = @($Global:Demo.Members.Keys) }
    $facts = [ordered]@{}
    $observer = $null
    $keysPath = $null
    Write-DemoStep "측정 회차 $runId (원자료 $raw)"
    if (-not (Test-DemoClusterHealthy)) { throw '주 DB 와 동기 예비가 모두 있어야 한다(-Action reset)' }
    if (-not (Test-DemoTradingActive)) { throw '두 앱 노드가 거래 ACTIVE 여야 한다' }
    if (Test-DemoRunning 'chaos-db-ackrec') { throw 'chaos-db-ackrec 가 이미 돈다(앞 회차가 남았다. -Action reset)' }
    try {
        $c = Get-DemoLeader
        $tl.pre = [ordered]@{ patroni_leader = $c.Leader; timeline = $c.Timeline; sync = @($c.Sync); epoch = (Get-DemoLeaseEpoch)
            app_leader = (Get-LeaderContainer) }
        $tl.db_ips = Get-DbIps
        # haproxy.cfg 의 pg_write 서버 이름이 멤버 이름과 같다(db-1·db-2).
        $tl.haproxy_server = [ordered]@{ 'db-1' = 'db-1'; 'db-2' = 'db-2' }
        Write-Host "  주 DB $($c.Leader), 동기 예비 $($c.Sync -join ','), 타임라인 $($c.Timeline), 앱 리더 $($tl.pre.app_leader), epoch $($tl.pre.epoch)"
        if ($WithK6) {
            $keys = $null
            for ($i = 0; $i -lt 6 -and -not $keys; $i++) {
                try { $keys = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/harness/v1/loadtest-keys?market=KRW-BTC&count=2&quote=1000000000&base=100' -Method Post -TimeoutSec 90 }
                catch { Start-Sleep -Seconds 5 }
            }
            if (-not $keys) { throw '부하 계정 키 발급 실패' }
            $keysPath = Join-Path $raw 'keys.json'
            Write-JsonFile $keysPath @($keys)
        }
        Write-DemoStep 'DB 수준 ACK 기록기·관측기·k6'
        Start-Recorder $raw
        $obsArgs = @((Join-Path $scaleDir 'patroni_observe.py'), '--out', (Join-Path $raw 'observe.jsonl'), '--stop-file', (Join-Path $raw 'observe.stop')) + $observerArgs
        if ($keysPath) { $obsArgs += @('--keys', $keysPath) }
        $observer = Start-Process -FilePath $python -ArgumentList $obsArgs -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $raw 'observe.stdout.log') -RedirectStandardError (Join-Path $raw 'observe.stderr.log')
        if ($WithK6) { Start-K6 $raw $keysPath $runId }
        Write-Host "  주입 전 창 $PreWindowSec 초"
        Start-Sleep -Seconds ([Math]::Max(5, $PreWindowSec - 10))
        $facts.metrics = [ordered]@{ before = (Get-MetricsTotal) }
        $facts.invalid = [ordered]@{ cpu_samples = @(Get-HostCpu); foreign_processes = @(Get-ForeignLoad) }
        $etcdBefore = Get-PromScalar 'sum(etcd_server_leader_changes_seen_total)'
        if (Test-FailoverClockGuard -ExperimentId 'E-P1' -RunId $runId) { throw [System.OperationCanceledException]::new('clock_skew') }
        $facts.invalid.cpu_samples += Get-HostCpu
        Start-Sleep -Seconds 3

        # ── 주입 ──
        $visibleRaw = Invoke-DemoSql $visibleSql 'exchange' 10
        if (-not $visibleRaw) { throw '직전 60초 주문·체결 목록을 읽지 못했다' }
        [System.IO.File]::WriteAllText((Join-Path $raw 'visible-before.json'), $visibleRaw, [System.Text.UTF8Encoding]::new($false))
        $visible = $visibleRaw | ConvertFrom-Json
        $kill = Invoke-Kill
        $t0 = [double]$kill.t0
        $tl.t0 = $t0
        $tl.injected = $kill.killed_container
        $tl.role_injected = 'primary'
        $tl.sync_member = $kill.killed_member
        $oldEpoch = [long]$tl.pre.epoch
        $promotedAt = $null; $promotedNode = $null; $staleBase = $null; $promotionMetrics = $null; $tPromote = $null; $rejoinWatch = $null
        $observe = {
            if (-not $tPromote) {
                $l = Get-DemoLeader
                if ($l -and $l.Leader -and $l.Leader -ne $kill.killed_member) { $tPromote = Get-DemoNow; Write-Host ("  DB 승격 관찰 {0} +{1:N1}초" -f $l.Leader, ($tPromote - $t0)) }
            }
            if (-not $promotedAt) {
                $e = Get-DemoLeaseEpoch
                if ($null -ne $e -and $e -gt $oldEpoch) {
                    $promotedAt = Get-DemoNow
                    $staleBase = Get-EpochWriteCounts -Epoch $oldEpoch -CmdHasFencing (Test-CommandFencingColumn)
                    $promotedNode = Get-LeaderContainer
                    if ($promotedNode) { $promotionMetrics = Get-NodeMetrics $promotedNode }
                    Write-Host ("  앱 리더 승격 관찰 epoch {0} {1} +{2:N1}초" -f $e, $promotedNode, ($promotedAt - $t0))
                }
            }
        }
        $cpuAt = @(($t0 + 3), ($t0 + 6), ($t0 + 9))
        if ($ManualReturn) {
            Write-Host '  옛 주 DB 를 자동으로 켜지 않는다. 다른 창에서 -Action return 을 실행한다(최대 15분 기다림)' -ForegroundColor Yellow
            $deadline = (Get-DemoNow) + 900
            while ((Get-DemoNow) -lt $deadline -and -not (Test-DemoRunning $kill.killed_container)) {
                . $observe
                if ($cpuAt.Count -gt 0 -and (Get-DemoNow) -ge $cpuAt[0]) { $facts.invalid.cpu_samples += Get-HostCpu; $cpuAt = @($cpuAt | Select-Object -Skip 1) }
                Start-Sleep -Milliseconds 500
            }
            $tl.t_back = Get-DemoNow
        } else {
            # 승격을 보기 전에는 켜지 않는다. 켜면 같은 노드가 리더 키를 되찾아 전환이 없다(E-P1b).
            $promoteDeadline = $t0 + 120
            while ((Get-DemoNow) -lt [Math]::Max($t0 + $HoldSec, $(if ($tPromote) { $tPromote + 3 } else { $promoteDeadline }))) {
                . $observe
                if ($cpuAt.Count -gt 0 -and (Get-DemoNow) -ge $cpuAt[0]) { $facts.invalid.cpu_samples += Get-HostCpu; $cpuAt = @($cpuAt | Select-Object -Skip 1) }
                if (-not $tPromote -and (Get-DemoNow) -ge $promoteDeadline) { Write-Host '  120초 안에 승격을 보지 못했다' -ForegroundColor Yellow; break }
                Start-Sleep -Milliseconds 500
            }
            $tl.t_back = Get-DemoNow
            Write-Host ("  옛 주 DB {0} 다시 켬 +{1:N1}초" -f $kill.killed_container, ($tl.t_back - $t0))
            Start-DemoDbContainer $kill.killed_container
            $rejoinWatch = New-RejoinWatch $kill.killed_member $tl.t_back
        }
        $tl.hold_s = [Math]::Round($tl.t_back - $t0, 1)
        $resumedAt = $null; $streak = 0; $nextStatus = 0
        $deadline = (Get-DemoNow) + 300
        while ((Get-DemoNow) -lt $deadline) {
            . $observe
            if ($rejoinWatch) { Step-RejoinWatch $rejoinWatch }
            if ((Get-DemoNow) -ge $nextStatus) {
                $nextStatus = (Get-DemoNow) + 2
                if (Test-DemoTradingActive) { $streak++ } else { $streak = 0 }
            }
            if (-not $resumedAt -and $streak -ge 2) { $resumedAt = Get-DemoNow; Write-Host ("  거래 재개 관찰 +{0:N1}초" -f ($resumedAt - $t0)) }
            if ($resumedAt -and $promotedAt -and (Get-DemoNow) -ge $promotedAt + 30) { break }
            Start-Sleep -Milliseconds 500
        }
        if ($promotedAt -and $promotedNode) {
            $after = Get-NodeMetrics $promotedNode
            if ($after -and $promotionMetrics) {
                $facts.post_promotion = [ordered]@{ node = $promotedNode; fencing_rejections = $after.renew_rejected - $promotionMetrics.renew_rejected
                    demotions = $after.demotions - $promotionMetrics.demotions }
            }
        }
        $tl.t_resume_hint = $resumedAt
        if ($rejoinWatch) {
            $tl.rejoin = [ordered]@{ member = $rejoinWatch.Member; method = $rejoinWatch.Method; reason = $rejoinWatch.Reason
                reinit_at = $rejoinWatch.ReinitAt; streaming_at = $rejoinWatch.StreamingAt }
        }
        if (-not $resumedAt) { Write-Host '  경고: 재개 미관찰(시간 초과)' -ForegroundColor Yellow }
        $facts.invalid.foreign_processes = @($facts.invalid.foreign_processes) + @(Get-ForeignLoad)
        Write-Host "  재개 뒤 창 $PostWindowSec 초"
        Start-Sleep -Seconds $PostWindowSec

        # ── 부하 정지와 사후 run ──
        $k6Log = Stop-K6 $raw
        if ($k6Log) {
            & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'load-acks.ps1') -LogPath $k6Log -Reset -Pg $Global:Demo.Toolbox | Out-Host
            $facts.k6 = [ordered]@{ log_sha256 = (Get-FileHash $k6Log -Algorithm SHA256).Hash.ToLowerInvariant()
                ack_lines = @(Select-String -Path $k6Log -Pattern 'ACK|' -SimpleMatch).Count }
        }
        Start-Sleep -Seconds 3
        if (-not $NoPostCheck) {
            $botsStopped = Stop-BotLoad
            $facts.drained = Wait-DrainZero -Sql $Global:CHAOS_DRAIN_SQL -TimeoutSec 90 -RequiredStreak 2
            $facts.invariants = [ordered]@{ post = (Invoke-InvariantRun -Port (Get-LiveNodePort) -RunId $runId -Window 'post') }
            $facts.bots_stopped = $botsStopped
        }

        # ── 사실 ──
        $facts.metrics.after = Get-MetricsTotal
        $facts.orders_500 = [long]($facts.metrics.after.orders_500 - $facts.metrics.before.orders_500)
        $clusterAfter = Get-DemoLeader
        $tl.post = [ordered]@{ patroni_leader = $clusterAfter.Leader; timeline = $clusterAfter.Timeline; sync = @($clusterAfter.Sync)
            epoch = (Get-DemoLeaseEpoch); app_leader = (Get-LeaderContainer) }
        if ($staleBase) {
            $facts.stale_write_delta = Get-StaleWriteDelta -Baseline $staleBase -After (Get-EpochWriteCounts -Epoch $oldEpoch -CmdHasFencing (Test-CommandFencingColumn))
        } else { $facts.stale_write_delta = $null; $facts.stale_note = '앱 승격을 관찰하지 못해 기준선이 없다' }
        # 봇을 멈추지 않았으면 주입 10초 전까지의 행만 비교한다(Get-RejoinFacts).
        $rejoinCutoff = if ($NoPostCheck) { ConvertTo-IsoUtc ($t0 - 10) } else { '' }
        $facts.rejoin = Get-RejoinFacts $kill.killed_container $rejoinCutoff
        if ($rejoinWatch -and $rejoinWatch.Method) { $facts.rejoin.method = $rejoinWatch.Method; $facts.rejoin.reason = $rejoinWatch.Reason }
        $facts.t5 = ConvertFrom-DbTimestamp (Invoke-Psql (Get-FirstTradeSql -OldEpoch $oldEpoch -T0Iso (ConvertTo-IsoUtc $t0)))
        if ($resumedAt) {
            $ft = Invoke-Psql ("SELECT min(created_at) AT TIME ZONE 'UTC' FROM event_journal WHERE event_type = 'TRADE' AND created_at > '{0}'" -f (ConvertTo-IsoUtc ($resumedAt - 5)))
            $facts.first_trade_after_resume = ConvertFrom-DbTimestamp $ft
        }
        $facts.duplicate_coid_orders = [long](Get-Scalar "SELECT count(*) FROM (SELECT user_id, client_order_id FROM command_journal WHERE command_type = 'NEW' AND client_order_id IS NOT NULL GROUP BY 1, 2 HAVING count(*) > 1) d" -Required)
        $facts.syncrep_waiters_after_resume = [long](Get-Scalar "SELECT count(*) FROM pg_stat_activity WHERE wait_event = 'SyncRep' AND application_name <> 'exchange-write-probe'" -Required)
        $facts.visible_before = Test-VisibleSurvived $visible
        $end = Get-DemoNow
        $tl.t_end = $end
        $etcdAfter = Get-PromScalar 'sum(etcd_server_leader_changes_seen_total)'
        if ($null -ne $etcdBefore -and $null -ne $etcdAfter) { $facts.invalid.etcd_leader_changes = $etcdAfter - $etcdBefore }
        $winEnd = $(if ($resumedAt) { $resumedAt } else { $end })
        $win = [int][Math]::Max(15, $winEnd - ($t0 - 10))
        $facts.invalid.fsync_p99_s = Get-PromScalar "histogram_quantile(0.99, sum by (le) (increase(etcd_disk_wal_fsync_duration_seconds_bucket[${win}s])))" $winEnd
        $facts.invalid.peer_rtt_p99_s = Get-PromScalar "histogram_quantile(0.99, sum by (le) (increase(etcd_network_peer_round_trip_time_seconds_bucket[${win}s])))" $winEnd
        $facts.failsafe_max = Get-PromScalar "max(max_over_time(patroni_failsafe_mode_is_active[$([int]($end - $t0 + 5))s]))" $end
    }
    catch [System.OperationCanceledException] {
        Write-Host '시계 스큐로 주입하지 않았다.' -ForegroundColor Yellow
        $facts.error = 'clock_skew'
    }
    catch {
        $facts.error = "$($_.Exception.Message) (line $($_.InvocationInfo.ScriptLineNumber))"
        Write-Host "회차 중 오류: $($facts.error)" -ForegroundColor Red
    }
    finally {
        if ($observer) {
            New-Item -ItemType File -Force (Join-Path $raw 'observe.stop') | Out-Null
            if (-not $observer.WaitForExit(20000)) { $observer.Kill() }
        }
        try { Stop-K6 $raw | Out-Null } catch { }
        if ($keysPath -and (Test-Path $keysPath)) {
            try { Clear-K6Orders $raw $keysPath } catch { Write-Host "k6 미체결 취소 실패: $($_.Exception.Message)" -ForegroundColor Yellow }
        }
        try { if ((& docker ps -a --filter 'name=^chaos-db-ackrec$' --format '{{.Names}}')) { Stop-Recorder $raw } } catch { Write-Host "기록기 정지 실패: $($_.Exception.Message)" -ForegroundColor Yellow }
        # 사후 run 에서 봇을 멈췄으면 다시 켠다. 멈춘 DB 노드가 남았으면(오류로 끝난 회차) 켜 둔다.
        if (-not (Test-DemoRunning 'chaos-bots')) { & docker start chaos-bots | Out-Null }
        foreach ($m in @(Get-StoppedMembers)) { try { Start-DemoDbContainer $Global:Demo.Members[$m] } catch { } }
        Write-JsonFile (Join-Path $raw 'timeline.json') $tl
        Write-JsonFile (Join-Path $raw 'facts.json') $facts
    }

    # ── 판정과 대조 ──
    $verdictPath = Join-Path $evidenceDir "$runId.verdict.json"
    & $python (Join-Path $scaleDir 'patroni_axes.py') --scenario E-P1 --members 'db-1,db-2' --dir $raw --out $verdictPath | Out-Host
    $verdict = if (Test-Path $verdictPath) { Get-Content $verdictPath -Raw -Encoding utf8 | ConvertFrom-Json } else { $null }
    $ackVerify = if (Test-Path (Join-Path $raw 'ackrec-verify.json')) { Get-Content (Join-Path $raw 'ackrec-verify.json') -Raw -Encoding utf8 | ConvertFrom-Json } else { $null }
    $gaps = if ($tl.t0) { Get-FailoverGaps $raw ([double]$tl.t0) } else { $null }
    $outage = if ($gaps) { $gaps.write_outage } else { $null }
    $ackGap = if ($gaps) { $gaps.ack_gap } else { $null }
    $c16 = $null
    if ($facts.invariants -and $facts.invariants.post) {
        $c16 = @($facts.invariants.post.checks | Where-Object { $_.checkName -eq 'C16_ACK_JOURNALED' } | ForEach-Object { [long]$_.violatedRows }) | Select-Object -First 1
    }
    $k6Acks = if ($facts.k6) { [long]$facts.k6.ack_lines } else { 0 }
    $vis = $facts.visible_before
    $lostParts = @(
        $(if ($ackVerify) { [long]$ackVerify.missing_count } else { $null }),
        $(if ($vis) { [long]$vis.orders_missing + [long]$vis.trades_missing } else { $null }),
        $(if ($null -ne $c16) { [long]$c16 } elseif (-not $WithK6 -or $NoPostCheck) { 0 } else { $null })
    )
    $lost = if (@($lostParts | Where-Object { $null -eq $_ }).Count -gt 0) { $null } else { ($lostParts | Measure-Object -Sum).Sum }
    [object[]] $failedAxes = @(); [object[]] $notJudgedAxes = @()
    if ($verdict) {
        $failedAxes = @($verdict.axes | Where-Object { $_.pass -eq $false } | ForEach-Object { $_.id })
        $notJudgedAxes = @($verdict.axes | Where-Object { $null -eq $_.pass } | ForEach-Object { $_.id })
    }    $times = if ($verdict) { $verdict.times } else { $null }
    $d = if ($verdict) { $verdict.derived } else { $null }
    $result = [ordered]@{
        action = 'run'; run_id = $runId; verdict = $(if ($verdict) { $verdict.verdict } else { 'ERROR' })
        old_primary = $tl.pre.patroni_leader; new_primary = $tl.post.patroni_leader
        promote_s = $(if ($d) { Round3 $d.db_failover_s } else { $null })
        reads_back_s = $(if ($d) { Round3 $d.read_recovery_s } else { $null })
        write_outage_s = $(if ($outage) { $outage.outage_s } else { $null })
        db_write_gap_s = $(if ($ackGap) { $ackGap.gap_s } else { $null })
        return_at_s = Round3 ($tl.t_back - $tl.t0)
        resume_s = $(if ($times -and $times.T_resume) { Round3 ($times.T_resume - $tl.t0) } else { $null })
        first_trade_s = $(if ($times -and $times.T5) { Round3 ($times.T5 - $tl.t0) } else { $null })
        orders_checked = $(if ($vis) { $vis.orders_checked } else { 0 }) + $k6Acks
        trades_checked = $(if ($vis) { $vis.trades_checked } else { 0 })
        acked_writes_checked = $(if ($ackVerify) { $ackVerify.acked } else { $null })
        lost = $lost
        detail = [ordered]@{ acked_missing = $(if ($ackVerify) { $ackVerify.missing_count } else { $null })
            visible_orders_missing = $(if ($vis) { $vis.orders_missing } else { $null }); visible_trades_missing = $(if ($vis) { $vis.trades_missing } else { $null })
            k6_acks = $k6Acks; c16_violations = $c16; write_codes = $(if ($outage) { $outage.codes } else { $null })
            epoch = "$($tl.pre.epoch)->$($tl.post.epoch)"; timeline = "$($tl.pre.timeline)->$($tl.post.timeline)"
            rejoin = $(if ($facts.rejoin) { $facts.rejoin.method } else { $null })
            reinit_s = $(if ($tl.rejoin -and $tl.rejoin.reinit_at) { Round3 ($tl.rejoin.reinit_at - $tl.t0) } else { $null })
            standby_back_s = $(if ($tl.rejoin -and $tl.rejoin.streaming_at) { Round3 ($tl.rejoin.streaming_at - $tl.t0) } else { $null })
            error = $facts.error }
        failed_axes = $failedAxes
        not_judged_axes = $notJudgedAxes
        evidence = $evidenceDir
    }
    foreach ($f in @('timeline.json', 'facts.json', 'ackrec-verify.json', 'visible-before.json')) {
        $src = Join-Path $raw $f
        if (Test-Path $src) { Copy-Item $src (Join-Path $evidenceDir "$runId.$f") -Force }
    }
    $obs = Join-Path $raw 'observe.jsonl'
    if (Test-Path $obs) { Copy-Item $obs (Join-Path $evidenceDir "$runId.observe.jsonl") -Force }
    $ack = Join-Path $raw 'ackrec.jsonl'
    Write-JsonFile (Join-Path $evidenceDir "$runId.hashes.json") ([ordered]@{
        ackrec_jsonl = $(if (Test-Path $ack) { (Get-FileHash $ack -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null })
        raw_dir_outside_repo = $raw })
    Write-JsonFile (Join-Path $evidenceDir "$runId.result.json") $result

    Write-DemoStep "결과 $($result.verdict)"
    $rows = [ordered]@{
        '옛 주 DB → 새 주 DB' = "$($result.old_primary) → $($result.new_primary)"
        '자동 승격(T_promote − T0)' = "$($result.promote_s)초"
        '조회 복구(T_reads − T0)' = "$($result.reads_back_s)초"
        '옛 주 DB 복귀(T_back − T0)' = "$($result.return_at_s)초"
        '거래 재개(T_resume − T0)' = "$($result.resume_s)초"
        '쓰기 중단(주문 POST 401 아님 구간)' = "$($result.write_outage_s)초"
        'DB 쓰기 공백(ACK 기록기 최장 빈틈)' = "$($result.db_write_gap_s)초"
        '옛 주 DB 예비 복귀(되감기/재초기화)' = "$($result.detail.standby_back_s)초 $($result.detail.rejoin)"
        '첫 체결(T5 − T0)' = "$($result.first_trade_s)초"
        '대조한 주문(직전 60초 + k6 ACK)' = "$($result.orders_checked)건(k6 $k6Acks)"
        '대조한 체결(직전 60초)' = "$($result.trades_checked)건"
        '확인받은 DB 쓰기(ACK 기록기)' = "$($result.acked_writes_checked)건"
        '유실' = "$($result.lost)건"
    }
    $rows.GetEnumerator() | ForEach-Object { [pscustomobject]@{ 항목 = $_.Key; 값 = $_.Value } } | Format-Table -AutoSize | Out-Host
    if ($result.failed_axes.Count -gt 0) { Write-Host "FAIL 축: $($result.failed_axes -join ', ')" -ForegroundColor Red }
    if ($result.not_judged_axes.Count -gt 0) { Write-Host "판정 불가 축: $($result.not_judged_axes -join ', ')" -ForegroundColor Yellow }
    Write-Host "증거: $evidenceDir"
    Write-ResultLine $result
}

switch ($Action) {
    'status' { Invoke-Status }
    'kill' { Write-ResultLine (Invoke-Kill) }
    'return' { Write-ResultLine (Invoke-Return) }
    'reset' { Invoke-Reset }
    'run' { Invoke-Run }
}
