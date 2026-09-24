# 계층 프로브 판별력 검증(CLAUDE.md §19). 팔로워 한 노드에 계층별 고장을 하나씩 넣고
# L3·L4·L7 프로브 값과 Alertmanager 억제 결과가 기대 행렬과 같은지 기계 판정한다.
#
# 주입 셋. docker pause 는 응용만 멈추고 커널 네트워크는 산다(L7 만 실패 기대).
# Toxiproxy 리스너 비활성은 호스트는 살고 포트만 닫힌다(L4 실패 기대). 팔로워의 DB 경로라
# 그 노드의 조회 헬스도 함께 실패한다. docker network disconnect 는 도달 자체를 끊는다(L3 실패 기대).
#
# 리더가 아닌 노드에만 주입해 페일오버를 일으키지 않는다. 주입 중 리더가 바뀌면 판정하지 않는다.
# 전제: up.ps1 -Obs 로 뜬 카오스 스택. 종료코드 0 = PASS, 1 = FAIL 또는 NOT RUN.

. (Join-Path $PSScriptRoot 'lib.ps1')

$PromUrl = 'http://localhost:59090'
$AmUrl = 'http://localhost:59093'
$ExpectedSeries = @{ l3 = 5; l4 = 6; l7 = 3 }
$ProxyOf = @{ 'chaos-api-1' = @{ Name = 'pg-api1'; Port = 15432 }; 'chaos-api-2' = @{ Name = 'pg-api2'; Port = 25432 } }
$TimeoutSec = 150

function Get-ProbeSeries {
    $q = [uri]::EscapeDataString('probe_success{layer=~"l3|l4|l7"}')
    $r = Invoke-RestMethod "$PromUrl/api/v1/query?query=$q"
    foreach ($s in $r.data.result) {
        [pscustomobject]@{ Layer = $s.metric.layer; Host = $s.metric.host; Instance = $s.metric.instance; Value = [int]$s.value[1] }
    }
}

# 기대는 (계층, 인스턴스) -> 값. 모든 항목이 맞아야 참이다.
function Test-ProbeState {
    param([hashtable] $Expected)
    $series = @(Get-ProbeSeries)
    foreach ($key in $Expected.Keys) {
        $layer, $instance = $key -split '\|', 2
        $hit = $series | Where-Object { $_.Layer -eq $layer -and $_.Instance -eq $instance }
        if (-not $hit -or $hit.Value -ne $Expected[$key]) { return $false }
    }
    return $true
}

function Get-ProbeAlerts {
    $alerts = Invoke-RestMethod "$AmUrl/api/v2/alerts?active=true&silenced=true&inhibited=true"
    foreach ($a in $alerts) {
        if ($a.labels.alertname -notlike 'Probe*') { continue }
        [pscustomobject]@{ Name = $a.labels.alertname; Host = $a.labels.host; Instance = $a.labels.instance; State = $a.status.state; InhibitedBy = @($a.status.inhibitedBy).Count; StartsAt = ([datetime]$a.startsAt).ToUniversalTime() }
    }
}

# 기대는 (알람, host) -> active 또는 suppressed. 앞 단계에서 남은 알람이 기대를 채우지 않도록
# 이번 주입 뒤에 시작한 알람만 센다.
function Test-AlertState {
    param([hashtable] $Expected, [datetime] $Since)
    $alerts = @(Get-ProbeAlerts)
    foreach ($key in $Expected.Keys) {
        $name, $hostName = $key -split '\|', 2
        $hit = @($alerts | Where-Object { $_.Name -eq $name -and $_.Host -eq $hostName -and $_.StartsAt -ge $Since })
        if ($hit.Count -eq 0 -or ($hit | Where-Object { $_.State -ne $Expected[$key] })) { return $false }
    }
    # 기대 밖 알람이 함께 떠도 통과하면 억제가 원인 계층만 남긴다는 판정이 성립하지 않는다.
    $started = @($alerts | Where-Object { $_.StartsAt -ge $Since } | ForEach-Object { "$($_.Name)|$($_.Host)" } | Sort-Object -Unique)
    return (($started -join ',') -eq ((@($Expected.Keys) | Sort-Object) -join ','))
}

# 리더 불변은 컨테이너 로그가 아니라 리스 행으로 판정한다. holder 나 epoch 가 바뀌면 리더가 바뀐 것이다. 모드가 쓰는
# 리스 자원을 모두 본다(P1·P2 matching-engine, P3 matching-p0·matching-p1, S4 설계 4-18).
function Get-LeaseHolder {
    $resources = (Get-ModeLeaseResources | ForEach-Object { "'$_'" }) -join ','
    $v = Invoke-Psql ("SELECT string_agg(resource || ':' || holder || '/' || epoch, ',' ORDER BY resource) " +
        "FROM leader_lease WHERE resource IN ($resources) AND lease_until > now()")
    if ([string]::IsNullOrWhiteSpace($v)) { return $null }
    return "$v".Trim()
}

function Get-HealthyExpectation {
    $e = @{}
    foreach ($h in 'chaos-lb', 'chaos-api-1', 'chaos-api-2', 'chaos-toxiproxy', 'chaos-pg') { $e["l3|$h"] = 1 }
    foreach ($i in 'chaos-lb:8080', 'chaos-api-1:8080', 'chaos-api-2:8080', 'chaos-toxiproxy:15432', 'chaos-toxiproxy:25432', 'chaos-pg:5432') { $e["l4|$i"] = 1 }
    foreach ($h in 'chaos-lb', 'chaos-api-1', 'chaos-api-2') { $e["l7|http://${h}:8080/actuator/health/read"] = 1 }
    return $e
}

function Set-ToxiproxyEnabled {
    param([string] $Proxy, [bool] $Enabled)
    $body = @{ enabled = $Enabled } | ConvertTo-Json -Compress
    # Toxiproxy 는 브라우저 User-Agent(Mozilla 접두)를 거부한다. PowerShell 기본값이 그 접두다.
    Invoke-RestMethod -Method Post -Uri "$($Global:CHAOS.ToxiUrl)/proxies/$Proxy" -Body $body -ContentType 'application/json' -UserAgent 'probe-layers' | Out-Null
}

# 한 단계: 주입 -> 프로브 기대 도달 -> 알람 기대 도달 -> 복귀 -> 전 프로브 정상 복귀.
function Invoke-Phase {
    param([string] $Name, [scriptblock] $Inject, [scriptblock] $Restore, [hashtable] $ProbeExpected, [hashtable] $AlertExpected, [string] $Follower, [string] $BaselineLease)
    Write-Section "단계 $Name"
    $phase = [ordered]@{ name = $Name; follower = $Follower; probeExpected = $ProbeExpected; alertExpected = $AlertExpected }
    $t0 = Get-Date
    $probeAt = $null; $alertAt = $null
    try {
        & $Inject
        $phase.injectedAt = Now-Iso
        $probeAt = Wait-For { Test-ProbeState $ProbeExpected } -TimeoutSec $TimeoutSec -PollSec 2
        $phase.probeObserved = @(Get-ProbeSeries)
        $phase.probeDetectSec = if ($probeAt) { [math]::Round(($probeAt - $t0.ToUniversalTime()).TotalSeconds, 1) } else { $null }
        $alertAt = if ($probeAt) { Wait-For { Test-AlertState $AlertExpected $t0.ToUniversalTime() } -TimeoutSec $TimeoutSec -PollSec 3 } else { $null }
        $phase.alertObserved = @(Get-ProbeAlerts)
        $phase.alertDetectSec = if ($alertAt) { [math]::Round(($alertAt - $t0.ToUniversalTime()).TotalSeconds, 1) } else { $null }
        # 알람을 기다리는 동안 프로브 값이 뒤집히지 않았는지 알람 시점에 한 번 더 본다.
        $phase.probeHeldAtAlert = [bool]($alertAt -and (Test-ProbeState $ProbeExpected))
        $phase.leaseDuring = Get-LeaseHolder
    } catch {
        # 주입이 실제로 들어가지 않은 단계는 판정 대상이 없다. 증거에 원인을 남기고 FAIL 로 센다.
        $phase.error = $_.Exception.Message
    } finally {
        try { & $Restore } catch { $phase.restoreError = $_.Exception.Message }
    }
    $recovered = Wait-For { Test-ProbeState (Get-HealthyExpectation) } -TimeoutSec $TimeoutSec -PollSec 2
    $phase.recovered = [bool]$recovered
    $phase.verdict = if ($phase.error -or -not $probeAt -or -not $alertAt -or -not $phase.probeHeldAtAlert -or -not $recovered) { 'FAIL' }
        elseif (-not $phase.leaseDuring -or $phase.leaseDuring -ne $BaselineLease) { 'NOT RUN' }
        else { 'PASS' }
    Write-Host ("{0}: 프로브 {1}s, 알람 {2}s, 복귀 {3}, 판정 {4}" -f $Name, $phase.probeDetectSec, $phase.alertDetectSec, $phase.recovered, $phase.verdict)
    return $phase
}

$result = [ordered]@{
    experiment = 'layer-probes'
    startedAt = Now-Iso
    commit = (& git -C $Global:CHAOS.RepoRoot rev-parse HEAD)
    worktreeDirty = [bool](& git -C $Global:CHAOS.RepoRoot status --porcelain -- harness docs)
    blackboxImage = (& docker inspect --format '{{.Config.Image}} {{.Image}}' chaos-blackbox)
    configSha256 = [ordered]@{}
    note = '단일 호스트 로컬 Docker Desktop. 프로브가 같은 도커망 안에 있어 망 밖 관점이 아니다'
}

Write-Section '기준선: 계층별 시계열 수와 전부 정상'
$baselineAt = Wait-For { Test-ProbeState (Get-HealthyExpectation) } -TimeoutSec 180 -PollSec 3
$series = @(Get-ProbeSeries)
$counts = @{}
foreach ($l in 'l3', 'l4', 'l7') { $counts[$l] = @($series | Where-Object Layer -eq $l).Count }
$result.baseline = [ordered]@{ healthy = [bool]$baselineAt; seriesCount = $counts; expectedCount = $ExpectedSeries }
$leader = Get-LeaderContainer
$follower = @('chaos-api-1', 'chaos-api-2') | Where-Object { $_ -ne $leader } | Select-Object -First 1
$result.leader = $leader
$result.follower = $follower
$lease = Get-LeaseHolder
$result.lease = $lease
foreach ($f in 'blackbox/blackbox.yml', 'prometheus/prometheus.yml', 'prometheus/rules/exchange-alerts.yml', 'alertmanager/alertmanager.yml') {
    $result.configSha256[$f] = (Get-FileHash (Join-Path $Global:CHAOS.RepoRoot "harness/$f") -Algorithm SHA256).Hash
}

$countsOk = ($counts.l3 -eq $ExpectedSeries.l3) -and ($counts.l4 -eq $ExpectedSeries.l4) -and ($counts.l7 -eq $ExpectedSeries.l7)
if (-not $baselineAt -or -not $countsOk -or -not $leader -or -not $lease) {
    $result.verdict = 'NOT RUN'
    $result.reason = "기준선 불성립: healthy=$([bool]$baselineAt) counts=$($counts | ConvertTo-Json -Compress) leader=$leader lease=$lease"
} else {
    $proxy = $ProxyOf[$follower]
    $net = $Global:CHAOS.Network
    $phases = @()

    $pauseProbe = Get-HealthyExpectation
    $pauseProbe["l7|http://${follower}:8080/actuator/health/read"] = 0
    $phases += Invoke-Phase -Name 'L7 응용 정지(docker pause)' -Follower $follower -BaselineLease $lease `
        -Inject { & docker pause $follower | Out-Null; if ($LASTEXITCODE -ne 0) { throw 'docker pause 실패' } } `
        -Restore { & docker unpause $follower 2>&1 | Out-Null } `
        -ProbeExpected $pauseProbe `
        -AlertExpected @{ "ProbeL7Failed|$follower" = 'active' }

    $portProbe = Get-HealthyExpectation
    $portProbe["l4|chaos-toxiproxy:$($proxy.Port)"] = 0
    $portProbe["l7|http://${follower}:8080/actuator/health/read"] = 0
    $phases += Invoke-Phase -Name 'L4 리스너 닫힘(Toxiproxy 비활성)' -Follower $follower -BaselineLease $lease `
        -Inject { Set-ToxiproxyEnabled $proxy.Name $false } `
        -Restore { Set-ToxiproxyEnabled $proxy.Name $true } `
        -ProbeExpected $portProbe `
        -AlertExpected @{ 'ProbeL4ConnectFailed|chaos-toxiproxy' = 'active'; "ProbeL7Failed|$follower" = 'active' }

    $netProbe = Get-HealthyExpectation
    $netProbe["l3|$follower"] = 0
    $netProbe["l4|${follower}:8080"] = 0
    $netProbe["l7|http://${follower}:8080/actuator/health/read"] = 0
    $phases += Invoke-Phase -Name 'L3 도달 단절(docker network disconnect)' -Follower $follower -BaselineLease $lease `
        -Inject { & docker network disconnect $net $follower | Out-Null; if ($LASTEXITCODE -ne 0) { throw 'docker network disconnect 실패' } } `
        -Restore { & docker network connect $net $follower 2>&1 | Out-Null } `
        -ProbeExpected $netProbe `
        -AlertExpected @{ "ProbeL3Unreachable|$follower" = 'active'; "ProbeL4ConnectFailed|$follower" = 'suppressed'; "ProbeL7Failed|$follower" = 'suppressed' }

    $result.phases = $phases
    $verdicts = @($phases | ForEach-Object { $_.verdict })
    $result.verdict = if ($verdicts -contains 'FAIL') { 'FAIL' } elseif ($verdicts -contains 'NOT RUN') { 'NOT RUN' } else { 'PASS' }
}

$result.finishedAt = Now-Iso
$outDir = Join-Path $Global:CHAOS.RepoRoot ("harness/evidence/layer-probes-" + (Get-Date).ToString('yyyyMMdd'))
New-Item -ItemType Directory -Force $outDir | Out-Null
$outFile = Join-Path $outDir 'result.json'
$result | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 $outFile
Write-Host "판정: $($result.verdict). 증거: $outFile"
exit $(if ($result.verdict -eq 'PASS') { 0 } else { 1 })
