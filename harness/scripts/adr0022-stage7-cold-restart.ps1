# ADR 0022 7단계 콜드 재기동 칸(실험 COLD). 부하 중 두 api 노드를 모두 멈췄다가 다시 올려 부팅 복구가 마켓별 스냅샷을
# 채택하고 꼬리만 재생하는 경로를 거치게 한다. 절차와 판정은 docs/contracts/하네스_검증설계.md §2-4 「ADR 0022 7단계 스냅샷
# 경계 재생 판정 규약」의 콜드 재기동 절차다. adr0022-stage5-chaos.ps1 -SnapshotEvidence 가 새 스택에서 돌리고, 스냅샷 경계
# 판정은 harness/scale/stage7_judge.py 가 한다. 이 스크립트의 판정은 새 epoch, 두 노드 기동 완료, 접수 재개, 사후 권위 run
# CRITICAL 위반 0 이다. 재기동 소요는 기록만 한다.
# 사용: pwsh -NoProfile -File harness/scripts/adr0022-stage7-cold-restart.ps1 (새로 세운 카오스 스택에서)
# 종료코드: 0 PASS 또는 시계 스큐 SKIP, 1 FAIL 또는 예외.
# -S4Boot 이면 '기동은 늘 팔로워'(마켓분할_리더_S4_설계 4-4, 7-2 X5)를 판정에 더한다. 먼저 올린 노드 로그에서 팔로워 기동 문구가
# 승격 문구보다 먼저이고(부팅 획득 없음), 나중 노드는 팔로워 상태 1 로 합류하며 승격 문구와 파티션 강등 계수가 0 이다.
param(
    [int] $BaseOps = 40,
    [int] $WarmupSec = 30,
    [int] $MinTradesBeforeStop = 10,
    [int] $StopTimeoutSec = 10,
    [int] $BootTimeoutSec = 180,
    [int] $ResumeTimeoutSec = 90,
    [string] $Resource,
    [switch] $S4Boot
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

$runId = "COLD-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
# 볼 리스 자원(S4 설계 4-18). P1·P2 는 matching-engine 이고 P3 에서는 -Resource 가 필수다.
$leaseResource = Get-LeaseResource -Resource $Resource
$ports = @{ 'chaos-api-1' = $Global:CHAOS.Api1Port; 'chaos-api-2' = $Global:CHAOS.Api2Port }

function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades" -Required) }

function Get-CheckRows {
    param($Run, [string] $Name)
    ($Run.checks | Where-Object { $_.checkName -eq $Name } | Select-Object -First 1).violatedRows
}

# 0 정지, 1 따라잡는 중, 2 리더라 쉬는 중, -1 상태 어긋남. 스크레이프 실패와 계열 부재는 $null 이다.
# 줄 머리에 고정한다. HELP 줄 설명이 '0 정지' 로 시작해 고정하지 않으면 그 0 을 값으로 읽는다.
function Get-CatchUpState {
    param([int] $Port)
    $v = Get-AppMetric -Port $Port -Pattern '(?m)^exchange_follower_catchup_state(?:\{[^}]*\})?[ \t]+(-?[0-9.]+(?:[eE][-+]?[0-9]+)?)'
    if ($null -eq $v) { return $null }
    return [double]$v
}

# 엔진 lifecycle 은 부팅 복구를 마친 뒤 따라잡기를 시작한다. liveness 만 보면 복구 중인 노드를 기동 완료로 셀 수 있다.
function Test-BootComplete {
    param([int] $Port)
    try { Invoke-RestMethod -Uri "http://localhost:$Port/actuator/health/liveness" -TimeoutSec 3 | Out-Null }
    catch { return $false }
    $state = Get-CatchUpState -Port $Port
    return ($null -ne $state) -and ($state -ne 0)
}

# 노드 로그에서 since(유닉스 초) 뒤의 줄을 읽어 팔로워 기동·승격 문구의 첫 위치와 수를 낸다. -1 은 없음이다.
function Get-BootOrder {
    param([string] $Container, [long] $Since)
    # 로그의 한글 문구를 대조하므로 도커 출력(UTF-8)을 코드 페이지로 풀지 않게 한다. 콘솔 기본값(OEM)이면 문구가 깨져 0 으로 읽힌다.
    $prevEncoding = [Console]::OutputEncoding
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    try { $lines = @(& cmd /c "docker logs --since $Since $Container 2>&1") } finally { [Console]::OutputEncoding = $prevEncoding }
    $follower = -1; $promotion = -1; $promotions = 0
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($follower -lt 0 -and "$($lines[$i])".Contains('팔로워로 기동')) { $follower = $i }
        if ("$($lines[$i])".Contains('리더 승격')) { $promotions++; if ($promotion -lt 0) { $promotion = $i } }
    }
    return [ordered]@{ lines = $lines.Count; followerBootLine = $follower; firstPromotionLine = $promotion; promotionLines = $promotions }
}

# 노드의 파티션 강등 계수 합. 긁지 못하면 $null 이다.
function Get-DemotionSum {
    param([int] $Port)
    try { $text = Invoke-RestMethod -Uri "http://localhost:$Port/actuator/prometheus" -TimeoutSec 5 } catch { return $null }
    $sum = 0.0
    foreach ($m in [regex]::Matches(($text | Out-String), '(?m)^exchange_partition_demotions_total\{[^}]*\}\s+([-0-9.eE+]+)')) { $sum += [double]$m.Groups[1].Value }
    return $sum
}

function Get-Seconds {
    param($From, $To)
    if ($null -eq $From -or $null -eq $To) { return $null }
    return [math]::Round(($To - $From).TotalSeconds, 3)
}

function Get-IsoOrNull {
    param($Time)
    if ($null -eq $Time) { return $null }
    return $Time.ToString('o')
}

$nodes = [ordered]@{}
$stopped = @()
try {
    Write-Section "COLD 시작 runId=$runId"

    Write-Section 'warmup'
    Set-BotOps $BaseOps
    Start-Sleep -Seconds $WarmupSec
    # 누적이 아니라 증분으로 본다. 부하 없이 멈추면 재기동 뒤 접수 재개를 가를 수 없다(E4 와 같은 술어).
    $tradesBase = Get-TradeCount
    $ok = Wait-For { (Get-TradeCount) - $tradesBase -ge $MinTradesBeforeStop } -TimeoutSec 120 -PollSec 1
    if (-not $ok) { throw "체결 증분 $MinTradesBeforeStop 건 미달. 봇 부하가 실리지 않았다. chaos-bots 상태와 BaseOps 를 확인한다." }

    # 멈춤 순서가 리더에 달려 있어 감지가 모호하면 주입하지 않는다.
    $leader = Get-LeaderContainer -Resource $leaseResource
    if (-not $leader) { throw '리더 감지 실패. 팔로워를 먼저 멈출 수 없어 주입하지 않는다.' }
    $follower = if ($leader -eq 'chaos-api-1') { 'chaos-api-2' } else { 'chaos-api-1' }
    Write-Host "리더 $leader, 팔로워 $follower"

    Write-Section 'ⓐ 사전 권위 run'
    $prePort = $ports[$leader]
    Reset-LoadtestAcks -Port $prePort
    $statusBefore = Get-HarnessStatus -Port $prePort -Resource $leaseResource
    $oldEpoch = [long]$statusBefore.epoch
    $oldHolder = $statusBefore.holder
    $pre = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'pre'
    Write-Host "사전 verdict=$($pre.verdict) 위반합=$($pre.totalViolations) old_epoch=$oldEpoch"

    # 리스 만료 구동 승격이 시계 스큐에 지연된다(#58). 두 노드를 멈추기 전에 거른다.
    if (Test-FailoverClockGuard -ExperimentId 'COLD' -RunId $runId) { exit 0 }

    $reDetected = Get-LeaderContainer -Resource $leaseResource
    if ($reDetected -ne $leader) {
        throw "주입 직전 리더 교체 감지(감지 $leader, 현재 $reDetected). 멈춤 순서가 틀어지므로 주입하지 않는다."
    }
    # 양성 대조. 기동 완료 신호를 멈추기 전에 두 노드에서 읽는다. 여기서 못 읽으면 기동 대기가 거짓 FAIL 을 낸다.
    foreach ($c in @($leader, $follower)) {
        $state = Get-CatchUpState -Port $ports[$c]
        if ($null -eq $state -or $state -eq 0) {
            throw "$c 의 exchange_follower_catchup_state 가 '$state' 다. 기동 완료 신호를 확인할 수 없어 주입하지 않는다."
        }
    }

    Write-Section "ⓑ 주입: $follower, $leader 순서로 docker stop -t $StopTimeoutSec"
    $leaseUntilBefore = ([datetimeoffset](Get-HarnessStatus -Port $prePort -Resource $leaseResource).leaseUntil).UtcDateTime
    $tradesAtStop = Get-TradeCount
    $t0 = (Get-Date).ToUniversalTime()
    # 리더를 먼저 멈추면 팔로워가 승격해 승격 재생이 이 칸에 섞인다.
    foreach ($c in @($follower, $leader)) {
        $from = (Get-Date).ToUniversalTime()
        $rc = Invoke-DockerQuiet "stop -t $StopTimeoutSec $c"
        $to = (Get-Date).ToUniversalTime()
        $nodes[$c] = [ordered]@{ role = $(if ($c -eq $leader) { 'leader' } else { 'follower' }); stopRc = $rc
            stopSeconds = (Get-Seconds $from $to); startRc = $null; bootSeconds = $null }
        if ($rc -ne 0) { throw "docker stop $c 실패(종료코드 $rc)" }
        $stopped += $c
    }
    Write-Host "T0=$($t0.ToString('o')) trades_at_stop=$tradesAtStop"

    Write-Section "ⓒ 기동: $leader 를 올려 새 epoch 리더와 접수 재개를 본 뒤 $follower"
    # 두 노드가 리스를 함께 다투는 창을 두지 않는다. 먼저 올린 노드가 리스를 얻고 나중 노드는 팔로워로 뜬다.
    $startedAt = (Get-Date).ToUniversalTime()
    $leaderSince = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $rc = Invoke-DockerQuiet "start $leader"
    $nodes[$leader]['startRc'] = $rc
    if ($rc -ne 0) { throw "docker start $leader 실패(종료코드 $rc)" }
    $stopped = @($stopped | Where-Object { $_ -ne $leader })
    $bootLeader = Wait-For { Test-BootComplete -Port $ports[$leader] } -TimeoutSec $BootTimeoutSec -PollSec 1
    $nodes[$leader]['bootSeconds'] = Get-Seconds $startedAt $bootLeader

    # Wait-For 의 조건 블록은 자식 범위라 변수 대입이 남지 않는다. 해시 표에 담는다.
    $seen = @{ epoch = $null; holder = $null }
    $t2 = Wait-For {
        try {
            $s = Get-HarnessStatus -Port $ports[$leader] -Resource $leaseResource
            if (($s.epoch -gt $oldEpoch) -and $s.leaderAlive) { $seen['epoch'] = [long]$s.epoch; $seen['holder'] = $s.holder; $true } else { $false }
        } catch { $false }
    } -TimeoutSec $ResumeTimeoutSec -PollSec 0.5

    $t0Iso = $t0.ToString('yyyy-MM-ddTHH:mm:ss.fff') + 'Z'
    # 새 epoch 로 적재된 명령 행이 곧 접수다. 크래시 창 재적재는 이벤트만 새 epoch 로 쓰고 명령 행을 만들지 않는다.
    $acceptSql = "SELECT min(created_at) AT TIME ZONE 'UTC' FROM command_journal " +
        "WHERE fencing_token > $oldEpoch AND created_at > '$t0Iso'"
    Wait-For { -not [string]::IsNullOrWhiteSpace((Invoke-Psql $acceptSql)) } -TimeoutSec $ResumeTimeoutSec -PollSec 0.5 | Out-Null
    $acceptRaw = Invoke-Psql $acceptSql
    $acceptTs = $null; $acceptAt = $null
    if (-not [string]::IsNullOrWhiteSpace($acceptRaw)) {
        $acceptTs = $acceptRaw.Replace(' ', 'T') + 'Z'
        $acceptAt = ([datetimeoffset]$acceptTs).UtcDateTime
    }

    $followerStartedAt = (Get-Date).ToUniversalTime()
    $followerSince = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $rc = Invoke-DockerQuiet "start $follower"
    $nodes[$follower]['startRc'] = $rc
    if ($rc -ne 0) { throw "docker start $follower 실패(종료코드 $rc)" }
    $stopped = @()
    $bootFollower = Wait-For { Test-BootComplete -Port $ports[$follower] } -TimeoutSec $BootTimeoutSec -PollSec 1
    $nodes[$follower]['bootSeconds'] = Get-Seconds $followerStartedAt $bootFollower
    Write-Host ("기동 완료: {0}={1}s, {2}={3}s, 새 epoch={4}, 접수 재개={5}" -f $leader, $nodes[$leader]['bootSeconds'],
        $follower, $nodes[$follower]['bootSeconds'], $seen['epoch'], $acceptTs)

    # 체결 재개(T5)와 나중 노드 합류 뒤 epoch 는 기록만 한다.
    $t5 = $null; $firstTradeTs = $null
    if ($acceptAt) { $t5 = Wait-FirstTradeAfter -OldEpoch $oldEpoch -T0 $t0 -TimeoutSec $ResumeTimeoutSec -Resource $leaseResource }
    if ($t5) { $firstTradeTs = $t5.ToString('o') }
    $epochAfterRejoin = Get-Scalar "SELECT epoch FROM leader_lease WHERE resource='$leaseResource'"

    Write-Section 'ⓓ 드레인 + 사후 권위 run'
    # C13a 가 부하 중 in-flight 를 유실로 세지 않도록 봇을 멈추고 드레인한 뒤 잰다(E4 와 같다).
    $botsStopped = Stop-BotLoad
    $drained = $false
    try {
        $requiredStreak = if ($botsStopped) { 1 } else { 2 }
        $drained = Wait-DrainZero -Sql $Global:CHAOS_DRAIN_SQL -TimeoutSec 90 -RequiredStreak $requiredStreak
        $postPort = Get-LiveNodePort -Resource $leaseResource
        $post = Invoke-InvariantRun -Port $postPort -RunId $runId -Window 'post'
    }
    finally {
        Restore-BotLoad -WasStopped $botsStopped -Ops $BaseOps
    }
    $drainValid = $botsStopped -and $drained

    # CRITICAL 위반은 5·6단계 게이팅 면제 목록을 뺀 CRITICAL 체크의 위반 행 합이다. 체크가 없거나 이름이 비면 판정 불능이다.
    $gated = @($post.checks | Where-Object { "$($_.severity)" -eq 'CRITICAL' -and -not (Test-GatingExempt -CheckName $_.checkName -Severity $_.severity) })
    $unnamed = @($post.checks | Where-Object { [string]::IsNullOrWhiteSpace($_.checkName) }).Count
    $criticalRows = [long]0
    $criticalFailed = @()
    foreach ($c in $gated) {
        $n = [math]::Max([long]0, [long]$c.violatedRows)
        $criticalRows += $n
        if ($n -gt 0) { $criticalFailed += "$($c.checkName)=$n" }
    }
    $criticalOk = ($gated.Count -gt 0) -and ($unnamed -eq 0) -and ($criticalRows -eq 0)

    $cmdGap = Get-CheckRows $post 'C05_CMD_GAPLESS'
    $evtGap = Get-CheckRows $post 'C06_EVT_GAPLESS'
    $ordersLost = Get-CheckRows $post 'C13A_ORDER_MATERIALIZED'
    $rpoRecords = [long]$cmdGap + [long]$evtGap + [long]$ordersLost

    $bootOk = ($null -ne $bootLeader) -and ($null -ne $bootFollower)
    $epochOk = ($null -ne $t2) -and ($null -ne $seen['epoch']) -and ($seen['epoch'] -gt $oldEpoch)
    # '기동은 늘 팔로워'(S4). 먼저 올린 노드는 팔로워 기동 문구 뒤에 승격 문구가 나오고, 나중 노드는 팔로워(상태 1)로 합류하며
    # 승격 문구와 강등 계수가 0 이다. -S4Boot 가 없으면 기록만 한다.
    $leaderOrder = Get-BootOrder -Container $leader -Since $leaderSince
    $followerOrder = Get-BootOrder -Container $follower -Since $followerSince
    $followerState = Get-CatchUpState -Port $ports[$follower]
    $followerDemotions = Get-DemotionSum -Port $ports[$follower]
    $s4BootOk = ($leaderOrder.followerBootLine -ge 0) -and ($leaderOrder.firstPromotionLine -gt $leaderOrder.followerBootLine) -and
        ($followerOrder.followerBootLine -ge 0) -and ($followerOrder.promotionLines -eq 0) -and ($followerState -eq 1) -and
        ($null -ne $followerDemotions) -and ($followerDemotions -eq 0)
    $verdict = 'FAIL'
    if ($epochOk -and $bootOk -and ($null -ne $acceptAt) -and $criticalOk -and (-not $S4Boot -or $s4BootOk)) { $verdict = 'PASS' }

    Write-Section 'ⓔ 판정·기록'
    $rto = Get-Seconds $t0 $t5
    $downtime = $null
    if ($t2) { $downtime = [math]::Round(($t2 - $leaseUntilBefore).TotalSeconds + (Get-LeaseTtlSec), 3) }
    $tradesAfter = Get-TradeCount
    $rec = Record-Failover -Port $postPort -Resource $leaseResource -Body @{
        experimentId = 'COLD'; oldLeader = $oldHolder; newLeader = $seen['holder']
        oldEpoch = $oldEpoch; newEpoch = $seen['epoch']; firstTradeTs = $firstTradeTs
        rtoSeconds = $rto; downtimeSeconds = $downtime
        rpoRecords = $rpoRecords; verdict = $verdict
        details = @{ runId = $runId; acceptResumedAt = "$acceptTs"; tradesBefore = "$tradesAtStop"
                     tradesAfter = "$tradesAfter"; drainValid = "$drainValid" }
    }

    Write-Host ''
    Write-Host '── COLD 증거 ───────────────────────────' -ForegroundColor Green
    Write-Host ("verdict             : {0}" -f $verdict)
    Write-Host ("멈춤·기동 순서      : {0} -> {1} / {1} -> {0}" -f $follower, $leader)
    Write-Host ("epoch               : {0} -> {1} (합류 뒤 {2})" -f $oldEpoch, $seen['epoch'], $epochAfterRejoin)
    Write-Host ("기동 완료(초)       : {0}={1}, {2}={3}" -f $leader, $nodes[$leader]['bootSeconds'], $follower, $nodes[$follower]['bootSeconds'])
    Write-Host ("접수 재개(초)       : {0}" -f (Get-Seconds $t0 $acceptAt))
    Write-Host ("체결 재개(초)       : {0}" -f $rto)
    Write-Host ("CRITICAL 위반       : 체크 {0}, 위반 행 {1} {2}" -f $gated.Count, $criticalRows, ($criticalFailed -join ', '))
    Write-Host ("드레인 성립         : {0}" -f $drainValid)
    Write-Host ("failover_run_id     : {0}" -f $rec.failover_run_id)
    Write-Host '────────────────────────────────────────'

    Save-VerdictFile -ExperimentId 'COLD' -RunId $runId -Verdict $verdict -Metrics @{
        stopOrder = @($follower, $leader); startOrder = @($leader, $follower); nodes = $nodes
        t0 = $t0.ToString('o'); newLeaderAt = (Get-IsoOrNull $t2); newLeaderSeconds = (Get-Seconds $t0 $t2)
        acceptResumedAt = $acceptTs; acceptSeconds = (Get-Seconds $t0 $acceptAt); firstTradeSeconds = $rto
        epochAtNewLeader = $seen['epoch']; epochAfterRejoin = $epochAfterRejoin
        postCritical = [ordered]@{ checks = $gated.Count; violations = $criticalRows; failed = $criticalFailed; unnamedChecks = $unnamed }
        postCriticalClean = [bool]$post.criticalClean; postIntegrity = (Test-ExperimentIntegrity $post)
        drainValid = $drainValid
        s4Boot = [ordered]@{ gated = [bool]$S4Boot; ok = $s4BootOk; leader = $leaderOrder; follower = $followerOrder
            followerCatchUpState = $followerState; followerDemotions = $followerDemotions }
    }
}
finally {
    # 예외로 끝나도 멈춘 노드를 남기지 않는다. 절차의 사후 채집이 두 노드 로그와 지표를 뜬다.
    foreach ($c in $stopped) {
        $code = Invoke-DockerQuiet "start $c"
        Write-Host "종료 훅: docker start $c 종료코드 $code" -ForegroundColor Yellow
    }
}

if ($verdict -ne 'PASS') { Write-Host 'COLD FAIL.' -ForegroundColor Red; exit 1 }
Write-Host 'COLD PASS.' -ForegroundColor Green
# 명시 종료코드. 앞선 네이티브 호출의 종료코드가 새면 PASS 가 exit 1 이 된다.
exit 0
