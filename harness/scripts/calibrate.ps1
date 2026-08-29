<#
.SYNOPSIS
용량 캘리브레이션(검증설계 §2-4). 기저 OPS를 확정하고 스파이크 상한을 유도한다.

.DESCRIPTION
§2-4 1항이 규정한 판정 기준 4가지와 파생 2가지를 실측한다.
  C1 호스트 전체 CPU < 60%           (-Provisional이면 스택 기여 CPU로 대체, §2-4 6항)
  C2 k6 프로세스 CPU < 1코어
  C3 부하기 내부 지연 기여(iteration_duration - http_req_duration 중앙값) < 5ms
  C4 PG fsync 대기(pg_stat_wal)·HikariCP pending 정상
  C5 dropped_iterations = 0          부하기가 발행 자체를 못 하면 위 넷이 무의미하다
  C6 주문 429 비율 < 1%              §2-3이 steady에 요구하는 "429 비율 ~0"
  C7 멱등 재생 비율 < 1%             재생(200)은 엔진에 닿지 않아 용량을 증명하지 못한다

전부 충족한 최고 OPS가 기저다. 스파이크 상한은 그 10배로 정의한다.
목표는 절대값 3,000이 아니라 "기저 대비 10배에서의 무결성·백프레셔 증명"이다(§2-4 머리말).

-Ladder는 짧은 탐색 주행으로 후보를 좁히는 용도다. 최종 확정은 -Duration 30m 단독
주행으로 한다. 짧은 주행은 WAL 증가·커넥션 누수·GC 누적 같은 느린 발현을 못 본다.

.PARAMETER Ops
시험할 기저 OPS 목록. 내림차순으로 돌며 처음 통과한 값을 채택한다.

.PARAMETER Duration
각 주행의 길이. 정본 확정 주행은 30m.

.PARAMETER Record
통과한 결과를 harness.calibration_runs에 기록한다.
#>
[CmdletBinding()]
param(
    [int[]]$Ops = @(300, 200, 150, 100),
    [string]$Duration = '3m',
    [switch]$Record,
    [switch]$AllowNoisyHost,
    [switch]$Provisional,
    [string]$K6Host = $env:COMPUTERNAME
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$loadDir = Join-Path $root 'load'
$artifacts = Join-Path $loadDir 'artifacts'
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null

# §2-4 판정 임계. 코드에 흩어두면 문서와 어긋나므로 한자리에 모은다.
$LIMIT_HOST_CPU = 60.0   # 퍼센트, 호스트 전체
$LIMIT_K6_CORES = 1.0    # 코어
$LIMIT_OVERHEAD = 5.0    # ms, 부하기 내부 지연 기여 중앙값
$LIMIT_PENDING  = 1.0    # HikariCP pending 스레드 표본 평균
# 코어, 스택 밖 프로세스 허용치(§2-4 2항 부하기 격리). 1.0인 이유는 주행을 구동하는
# 프로세스(셸·에이전트·터미널) 자체가 스택 밖으로 계상되기 때문이다. 같은 호스트에서
# 돌리는 한 이 몫은 없앨 수 없어 0.5로는 어떤 유휴 호스트도 통과하지 못한다.
# 1.0코어는 16코어 기준 6.25%로 C1 예산 60%의 1/10 미만이라 판정을 뒤집지 못한다.
# PS5.1에는 ??가 없다. 이 스크립트는 pwsh7 미설치 환경에서도 돌아야 한다.
$LIMIT_NOISE    = if ($env:CAL_NOISE_LIMIT) { [double]$env:CAL_NOISE_LIMIT } else { 1.0 }
$LOGICAL_CPUS   = [int]$env:NUMBER_OF_PROCESSORS

$PG = 'chaos-pg'
$API = 'http://localhost:58080'
$NET = 'chaos-net'
$K6_IMAGE = 'grafana/k6:1.4.0'
$CAL_CONTAINER = 'chaos-k6-cal'

function Get-PgScalar([string]$Sql) {
    # -q가 없으면 비질의 태그(UPDATE 0 등)가 섞여 파싱이 깨진다(lib.ps1과 같은 이유).
    $out = & docker exec $PG psql -U exchange -d exchange -q -t -A -c $Sql 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($out | Where-Object { $_ -ne '' } | Select-Object -First 1)
}

function Get-WalSnapshot {
    $row = Get-PgScalar "SELECT wal_records || '|' || wal_sync || '|' || round(wal_sync_time)::text FROM pg_stat_wal"
    if (-not $row) { return $null }
    $p = $row -split '\|'
    return [pscustomobject]@{ Records = [long]$p[0]; Syncs = [long]$p[1]; SyncMs = [double]$p[2] }
}

function Get-HikariPending {
    try {
        $r = Invoke-RestMethod -Uri "$API/actuator/metrics/hikaricp.connections.pending" -TimeoutSec 5
        return [double]($r.measurements | Where-Object statistic -eq 'VALUE' | Select-Object -First 1).value
    } catch { return $null }
}

function Get-K6Cores([string]$Name) {
    # docker stats의 CPUPerc는 100%가 1코어다. 코어 단위로 환산해 §2-4 기준과 맞춘다.
    $s = & docker stats --no-stream --format '{{.CPUPerc}}' $Name 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $s) { return $null }
    return [math]::Round(([double]($s -replace '%', '')) / 100.0, 3)
}

function Get-StackCores {
    # 스택 컨테이너 CPU 합. 호스트 전체 CPU는 부하와 무관한 데스크톱 프로세스까지 포함하므로
    # 그것만으로는 "부하가 호스트를 포화시켰는가"를 판정할 수 없다. 둘을 함께 남긴다.
    # k6 컨테이너도 합에 든다. C1 대체 판정(§2-4 6항)은 서버만이 아니라 계측 일습이
    # 호스트에 들어가는지를 묻는 것이라, 원 기준(호스트 전체 CPU)과 대상 범위를 맞춘다.
    $lines = & docker stats --no-stream --format '{{.CPUPerc}}' 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $lines) { return $null }
    $sum = 0.0
    foreach ($l in $lines) { $sum += [double]($l -replace '%', '') }
    return [math]::Round($sum / 100.0, 2)
}

function Get-ExternalCores([int]$Seconds = 8) {
    # 스택 밖 프로세스가 쓰는 코어. §2-4 2항(부하기 격리)이 충족되는지 사전 확인한다.
    $a = @{}
    Get-Process | Where-Object CPU | ForEach-Object { $a[$_.Id] = $_.CPU }
    Start-Sleep -Seconds $Seconds
    $top = @()
    $total = 0.0
    foreach ($p in (Get-Process | Where-Object CPU)) {
        if (-not $a.ContainsKey($p.Id)) { continue }
        $c = ($p.CPU - $a[$p.Id]) / $Seconds
        if ($c -gt 0.05) { $top += [pscustomobject]@{ Name = $p.ProcessName; Cores = [math]::Round($c, 2) } }
        $total += $c
    }
    # Docker Desktop VM(vmmem*)은 스택 자체라 소음으로 세지 않는다.
    $noise = $top | Where-Object { $_.Name -notmatch '^(vmmem|Vmmem|docker|com\.docker)' }
    return [pscustomobject]@{
        Total = [math]::Round($total, 2)
        Noise = [math]::Round((($noise | Measure-Object Cores -Sum).Sum), 2)
        Top   = ($noise | Sort-Object Cores -Descending | Select-Object -First 6)
    }
}

function Invoke-CalibrationRun([int]$BaseOps, [string]$Dur) {
    Write-Host ""
    Write-Host "=== 기저 $BaseOps OPS / $Dur ===" -ForegroundColor Cyan

    & docker rm -f $CAL_CONTAINER 2>$null | Out-Null
    Remove-Item (Join-Path $artifacts 'calibration.json') -ErrorAction SilentlyContinue

    $walBefore = Get-WalSnapshot
    $started = Get-Date
    # 접수 처리율 환산에 쓴다. k6 duration 표기(30m·3m·40s)를 초로 푼다.
    $durSec = switch -Regex ($Dur) {
        '^(\d+)m$' { [int]$Matches[1] * 60; break }
        '^(\d+)s$' { [int]$Matches[1]; break }
        '^(\d+)h$' { [int]$Matches[1] * 3600; break }
        default { 1 }
    }

    # 주행별 HTML 리포트. 발표 증거로 쓰려면 주행마다 파일이 갈려야 한다. 한 이름을
    # 재사용하면 사다리 마지막 주행만 남아 어느 OPS의 그래프인지 사후에 못 가린다.
    $reportName = "calibration-$BaseOps" + "ops-$Dur.html"
    # 주행마다 새 client_order_id 공간을 준다. 없으면 재주행이 이전 주행 키를 재사용해
    # 서버가 멱등 재생(200)으로 처리하고, 그 요청은 엔진에 닿지 않아 용량 측정이 무의미해진다.
    $runId = (Get-Date).ToString('HHmmss') + $BaseOps

    # 호스트 마운트 경로는 컨테이너 밖 절대경로여야 한다. Docker Desktop이 변환한다.
    $mountLoad = ($loadDir -replace '\\', '/')
    $mountArt = ($artifacts -replace '\\', '/')

    & docker run -d --name $CAL_CONTAINER --network $NET `
        -e "BASE_URL=http://chaos-lb:8080" -e "API_HOST=api.exchange.local" `
        -e "BASE_OPS=$BaseOps" -e "CAL_DURATION=$Dur" -e "CAL_OUT=/artifacts/calibration.json" `
        -e "K6_WEB_DASHBOARD=true" -e "K6_WEB_DASHBOARD_EXPORT=/artifacts/$reportName" `
        -e "RUN_ID=$runId" `
        -v "${mountLoad}:/load:ro" -v "${mountArt}:/artifacts" `
        -w /load --entrypoint k6 $K6_IMAGE run --quiet --no-color calibrate.js | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "k6 컨테이너 기동 실패" }

    # 표본 수집. 램프 구간을 판정에 넣으면 정상 상태가 아닌 값을 재게 되므로 앞 20초는 버린다.
    $hostCpu = New-Object System.Collections.ArrayList
    $k6Cores = New-Object System.Collections.ArrayList
    $stackCores = New-Object System.Collections.ArrayList
    $pending = New-Object System.Collections.ArrayList
    $warmup = 20
    while ($true) {
        $state = & docker inspect -f '{{.State.Running}}' $CAL_CONTAINER 2>$null
        if ($state -ne 'true') { break }
        $elapsed = ((Get-Date) - $started).TotalSeconds
        if ($elapsed -ge $warmup) {
            try {
                $c = (Get-Counter '\Processor(_Total)\% Processor Time' -ErrorAction Stop).CounterSamples[0].CookedValue
                [void]$hostCpu.Add([math]::Round($c, 1))
            } catch { }
            $k = Get-K6Cores $CAL_CONTAINER
            if ($null -ne $k) { [void]$k6Cores.Add($k) }
            $sc = Get-StackCores
            if ($null -ne $sc) { [void]$stackCores.Add($sc) }
            $p = Get-HikariPending
            if ($null -ne $p) { [void]$pending.Add($p) }
        }
        Start-Sleep -Seconds 5
    }

    $exit = [int](& docker inspect -f '{{.State.ExitCode}}' $CAL_CONTAINER 2>$null)
    $log = (& docker logs $CAL_CONTAINER 2>&1) -join "`n"
    & docker rm -f $CAL_CONTAINER 2>$null | Out-Null

    $walAfter = Get-WalSnapshot
    $summaryPath = Join-Path $artifacts 'calibration.json'
    if (-not (Test-Path $summaryPath)) {
        Write-Host $log
        throw "calibration.json 미생성. k6 종료코드 $exit"
    }
    $k6 = Get-Content $summaryPath -Raw | ConvertFrom-Json

    $syncDelta = $walAfter.Syncs - $walBefore.Syncs
    $syncMsDelta = $walAfter.SyncMs - $walBefore.SyncMs
    # 동기화 1회당 대기. 총량은 부하 크기에 비례하므로 건당으로 봐야 비교가 선다.
    $msPerSync = if ($syncDelta -gt 0) { [math]::Round($syncMsDelta / $syncDelta, 3) } else { 0 }

    $avg = { param($a) if ($a.Count -eq 0) { $null } else { [math]::Round((($a | Measure-Object -Average).Average), 2) } }
    $max = { param($a) if ($a.Count -eq 0) { $null } else { ($a | Measure-Object -Maximum).Maximum } }

    return [pscustomobject]@{
        BaseOps      = $BaseOps
        Duration     = $Dur
        K6Exit       = $exit
        HostCpuAvg   = & $avg $hostCpu
        HostCpuMax   = & $max $hostCpu
        K6CoresAvg   = & $avg $k6Cores
        K6CoresMax   = & $max $k6Cores
        StackCoresAvg = & $avg $stackCores
        StackCoresMax = & $max $stackCores
        StackCpuPct  = if ($stackCores.Count -eq 0) { $null } else {
            [math]::Round((($stackCores | Measure-Object -Average).Average) / $LOGICAL_CPUS * 100.0, 1) }
        PendingAvg   = & $avg $pending
        PendingMax   = & $max $pending
        OverheadMs   = $k6.k6_overhead_ms
        SignCostMs   = $k6.sign_cost_ms
        OrderP95Ms   = $k6.order_http_p95_ms
        ReqRate      = $k6.http_req_rate
        FailedRate   = $k6.http_req_failed_rate
        Dropped      = $k6.dropped_iterations
        OrderAccepted = $k6.order_accepted
        OrderThrottled = $k6.order_throttled
        OrderReplayed = $k6.order_replayed
        # 재생 비율. 0이 아니면 이 주행의 부하 일부가 엔진에 닿지 않았다는 뜻이다.
        ReplayRate = if (($k6.order_accepted + $k6.order_throttled + $k6.order_replayed) -le 0) { $null } else {
            [math]::Round($k6.order_replayed / ($k6.order_accepted + $k6.order_throttled + $k6.order_replayed), 4) }
        # 제공 부하 대비 실제 접수 비율. 이 값이 1에서 멀면 잰 것은 엔진 용량이 아니라 거절 비용이다.
        Order429Rate = if (($k6.order_accepted + $k6.order_throttled) -le 0) { $null } else {
            [math]::Round($k6.order_throttled / ($k6.order_accepted + $k6.order_throttled), 4) }
        AcceptedRate = if ($k6.order_accepted -gt 0) {
            [math]::Round($k6.order_accepted / [double]$durSec, 1) } else { 0 }
        WalSyncs     = $syncDelta
        WalMsPerSync = $msPerSync
        Samples      = $hostCpu.Count
        Report       = (Join-Path $artifacts $reportName)
    }
}

function Test-Criteria($r) {
    $c = [ordered]@{}
    if ($Provisional) {
        # 소음 주행에서는 호스트 전체 CPU가 부하의 함수가 아니다(§2-4 6항). 스택 기여로 대체
        # 판정하되 기록에 근거를 남긴다. 이 결과는 확정이 아니라 잠정이다.
        $c['C1* 스택기여CPU<60%'] = ($null -ne $r.StackCpuPct -and $r.StackCpuPct -lt $LIMIT_HOST_CPU)
    } else {
        $c['C1 호스트CPU<60%'] = ($null -ne $r.HostCpuAvg -and $r.HostCpuAvg -lt $LIMIT_HOST_CPU)
    }
    $c['C2 k6CPU<1코어'] = ($null -ne $r.K6CoresAvg -and $r.K6CoresAvg -lt $LIMIT_K6_CORES)
    $c['C3 부하기지연<5ms'] = ($null -ne $r.OverheadMs -and $r.OverheadMs -lt $LIMIT_OVERHEAD)
    # fsync는 절대 기준이 문서에 없다. 건당 대기가 커밋 지연을 지배하지 않는지로 본다.
    # pending은 대기 스레드라 정상 상태에서 0에 붙어 있어야 한다.
    $c['C4 fsync·pending정상'] = ($null -ne $r.PendingAvg -and $r.PendingAvg -lt $LIMIT_PENDING -and $r.WalMsPerSync -lt 10)
    # 개방 루프에서 발행 못한 반복이 있으면 부하기가 이미 포화다. 위 넷이 통과해도 무효다.
    $c['C5 dropped=0'] = ($r.Dropped -eq 0)
    # §2-3이 steady에 요구하는 "429 비율 ~0". 수용 제한(in-flight 12)을 넘는 제공 부하는
    # 초과분이 전량 429가 되고, 그 상태의 p95·처리율은 엔진 용량이 아니라 거절 비용이다.
    $c['C6 주문429<1%'] = ($null -ne $r.Order429Rate -and $r.Order429Rate -lt 0.01)
    # 재생이 섞이면 위 판정 전체가 무효다. 엔진에 닿지 않은 요청은 용량을 증명하지 못한다.
    $c['C7 멱등재생<1%'] = ($null -ne $r.ReplayRate -and $r.ReplayRate -lt 0.01)
    return $c
}

Write-Host "사전 점검: 스택 밖 프로세스 CPU 측정(8초)" -ForegroundColor DarkGray
$ext = Get-ExternalCores 8
Write-Host ("  스택 밖 소음 {0}코어 / 논리 {1}코어" -f $ext.Noise, $LOGICAL_CPUS)
if ($ext.Top) { $ext.Top | ForEach-Object { Write-Host ("    {0,-24} {1}코어" -f $_.Name, $_.Cores) } }
if ($ext.Noise -gt $LIMIT_NOISE) {
    Write-Host ("  경고: 소음이 {0}코어로 허용치 {1}코어를 넘는다. C1(호스트 전체 CPU)은 부하가 아니라" -f $ext.Noise, $LIMIT_NOISE) -ForegroundColor Yellow
    Write-Host "  이 프로세스들을 재는 값이 된다. 정본 확정 주행은 §2-4 2항대로 유휴 호스트에서 한다." -ForegroundColor Yellow
    if (-not $AllowNoisyHost) {
        Write-Host "  -AllowNoisyHost 없이는 탐색 주행만 의미가 있다. 중단한다." -ForegroundColor Red
        exit 2
    }
}

$results = @()
$winner = $null
foreach ($o in ($Ops | Sort-Object -Descending)) {
    $r = Invoke-CalibrationRun $o $Duration
    $crit = Test-Criteria $r
    $pass = -not ($crit.Values -contains $false)

    Write-Host ("  호스트CPU {0}% (최대 {1}) / 스택 {2}코어 = {3}% / k6 {4}코어 / 오버헤드 {5}ms / 서명 {6}ms" -f `
        $r.HostCpuAvg, $r.HostCpuMax, $r.StackCoresAvg, $r.StackCpuPct, $r.K6CoresAvg, `
        [math]::Round($r.OverheadMs, 3), [math]::Round($r.SignCostMs, 3))
    Write-Host ("  주문p95 {0}ms / 접수 {1}/s({2}건) / 429 {3} / dropped {4} / fsync {5}ms건" -f `
        [math]::Round($r.OrderP95Ms, 1), $r.AcceptedRate, $r.OrderAccepted, `
        $r.Order429Rate, $r.Dropped, $r.WalMsPerSync)
    Write-Host ("  멱등재생 {0} ({1}건) / 신규 {2}건 / 거절 {3}건" -f `
        $r.ReplayRate, $r.OrderReplayed, $r.OrderAccepted, $r.OrderThrottled)
    foreach ($k in $crit.Keys) {
        $mark = if ($crit[$k]) { 'PASS' } else { 'FAIL' }
        $color = if ($crit[$k]) { 'Green' } else { 'Red' }
        Write-Host ("  [{0}] {1}" -f $mark, $k) -ForegroundColor $color
    }

    $r | Add-Member -NotePropertyName Pass -NotePropertyValue $pass
    $r | Add-Member -NotePropertyName Criteria -NotePropertyValue $crit
    $results += $r
    if ($pass) { $winner = $r; break }
}

Write-Host ""
if (-not $winner) {
    Write-Host "판정: 후보 전부 미충족. 기저를 더 낮추거나 부하기를 분리해야 한다(§2-4 2항)." -ForegroundColor Red
    $results | Export-Clixml (Join-Path $artifacts 'calibration-runs.xml')
    exit 1
}

$baseOps = $winner.BaseOps
$spikeCap = $baseOps * 10
$verdict = if ($Provisional) { '잠정' } else { '확정' }
Write-Host ("판정($verdict): 기저 $baseOps OPS, 스파이크 상한 $spikeCap OPS") -ForegroundColor Green
if ($Provisional) {
    Write-Host ("  근거는 스택 기여 CPU다. 호스트 전체 CPU {0}%는 스택 밖 소음 {1}코어를 포함한다." -f `
        $winner.HostCpuAvg, $ext.Noise) -ForegroundColor Yellow
    Write-Host "  §2-4 6항상 이 값은 기저 확정이 아니다. 유휴 호스트 주행으로 다시 받아야 한다." -ForegroundColor Yellow
}

if ($Record) {
    $winner | Add-Member -NotePropertyName Provisional -NotePropertyValue ([bool]$Provisional) -Force
    $winner | Add-Member -NotePropertyName C1Basis -NotePropertyValue $(if ($Provisional) { 'stack' } else { 'host' }) -Force
    $winner | Add-Member -NotePropertyName ExternalNoiseCores -NotePropertyValue $ext.Noise -Force
    $winner | Add-Member -NotePropertyName LogicalCpus -NotePropertyValue $LOGICAL_CPUS -Force
    $details = ($winner | Select-Object -Property * -ExcludeProperty Criteria, Pass |
        ConvertTo-Json -Compress -Depth 4)
    # psql -c에 인자로 넘기면 따옴표·달러가 Windows 인자 분해와 셸 사이에서 두 번 해석된다.
    # 파일로 써서 stdin으로 먹이면 해석 단계가 없어진다.
    $sqlFile = Join-Path $artifacts 'calibration-insert.sql'
    $dollar = [char]36
    $q = "$dollar" + "cal" + "$dollar"
    @(
        "INSERT INTO harness.calibration_runs (base_ops, spike_cap, k6_host, details)",
        "VALUES ($baseOps, $spikeCap, $q$K6Host$q, $q$details$q" + "::jsonb);"
    ) -join "`n" | Set-Content -Path $sqlFile -Encoding UTF8
    Get-Content $sqlFile -Raw | & docker exec -i $PG psql -U exchange -d exchange -q -v ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) { Write-Host "기록 실패" -ForegroundColor Red; exit 1 }
    $id = Get-PgScalar "SELECT max(id) FROM harness.calibration_runs"
    Write-Host "harness.calibration_runs 기록 완료 (id=$id)"
}
exit 0
