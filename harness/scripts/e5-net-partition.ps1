# docs §3 E5 네트워크 분할(split-brain 반증, crown jewel).
# docker network disconnect chaos-net chaos-api-1(프로세스 생존, 네트워크만 격리) -> 60s -> connect.
# 정본 §3 주입명은 데모 컨테이너(exchange-demo_default/api-1-1)라 격리 스택 실명으로 정정한다.
# 판정(§3 E5 합격 기준, 안전성 중심): 이중 쓰기 0(워터마크 이후 구 epoch fencing 저널 행 0) +
# 무결성 0. 공허 통과 차단용으로 "신 리더 실제 승격(epoch 정확히 +1)"을 더해 split-brain 시나리오가
# 실제 성립했을 때만 PASS로 본다. RTO<30s는 E4/E6 소관이라 게이팅에서 빼고 증거로만 보고한다.
# 복귀 노드 팔로워 재합류도 재기동 타이밍 변동이 커 증거로만 보고한다(fencing 증명이 지배 증거).
param([string] $ConfigPath = (Join-Path $PSScriptRoot '..\config\e5-net-partition.env'))

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
$cfg = Read-EnvFile $ConfigPath
$runId = "E5-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
$target = $cfg['TARGET_NODE']
$net = $cfg['NETWORK']
$targetPort = [int]$cfg['TARGET_PORT']
$standbyPort = [int]$cfg['STANDBY_PORT']
$rtoTarget = [int]$cfg['RTO_TARGET_SEC']
$observeSec = [int]$cfg['POST_OBSERVE_SEC']

function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades") }
$connected = $true

Write-Section "E5(네트워크 분할) 시작 runId=$runId net=$net"
try {
    Start-Sleep -Seconds ([int]$cfg['WARMUP_SEC'])
    $ok = Wait-For { (Get-TradeCount) -ge [long]$cfg['MIN_TRADES_BEFORE_INJECT'] } -TimeoutSec 120 -PollSec 1
    if (-not $ok) { throw '체결 미달, 봇 상태 확인' }

    # 실 리더를 대상 삼는다. 리더가 아닌 노드를 분할하면 페일오버가 없어 공허 FAIL 난다(README 리허설).
    $detected = Get-LeaderContainer
    if ($detected) {
        $target = $detected
        if ($target -eq 'chaos-api-1') { $targetPort = 18080; $standbyPort = 28080 }
        else { $targetPort = 28080; $standbyPort = 18080 }
        Write-Host "리더 감지: $target (config 기본과 무관하게 실 리더 대상). standby=$standbyPort"
    } else {
        Write-Host "리더 감지 실패. config TARGET_NODE=$target 사용(팔로워면 페일오버 미발생 주의)."
    }

    Write-Section 'ⓐ 사전 권위 run'
    $prePort = Get-LiveNodePort
    Reset-LoadtestAcks -Port $prePort
    $s0 = Get-HarnessStatus -Port $prePort
    $oldEpoch = [long]$s0.epoch; $oldHolder = $s0.holder
    $leaseUntilBefore = ([datetimeoffset]$s0.leaseUntil).UtcDateTime
    $cmdHasFencing = Test-CommandFencingColumn
    $pre = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'pre'
    Write-Host "사전 verdict=$($pre.verdict) old_epoch=$oldEpoch cmd_fencing_col=$cmdHasFencing"

    # 시계 동기 전제조건(#58): 스큐면 분할 없이 SKIP. 미분할이라 종료 훅 복귀는 무해.
    if (Test-FailoverClockGuard -ExperimentId 'E5') { exit 0 }

    Write-Section "ⓑ 주입: docker network disconnect $net $target (프로세스 생존)"
    $tradesAtInject = Get-TradeCount
    & docker network disconnect $net $target | Out-Null
    $connected = $false
    $t0 = (Get-Date).ToUniversalTime()
    # 워터마크는 분할 완료 직후 채취한다. 이 이후 구 epoch로 append된 행 = 복귀 시 스테일 쓰기.
    $wCmd = Get-CommandWatermark
    $wEvt = Get-EventWatermark
    Write-Host "T0=$($t0.ToString('o')) w_cmd=$wCmd w_evt=$wEvt trades_at_inject=$tradesAtInject"

    Write-Section 'ⓒ 관찰(개입 금지): 신 리더 승격 + 체결 재개'
    $t2 = Wait-For {
        try { $s = Get-HarnessStatus -Port $standbyPort; ($s.epoch -gt $oldEpoch) -and $s.leaderAlive }
        catch { $false }
    } -TimeoutSec $observeSec -PollSec 0.5
    $sAfter = Get-HarnessStatus -Port $standbyPort
    $newEpoch = [long]$sAfter.epoch; $newHolder = $sAfter.holder
    $t5 = Wait-FirstTradeAfter -NewEpoch $newEpoch -T0 $t0 -TimeoutSec $observeSec
    $rto = $null; if ($t5) { $rto = ($t5 - $t0).TotalSeconds }
    $leaseTtl = Get-LeaseTtlSec
    $downtime = $null; if ($t2) { $downtime = ($t2 - $leaseUntilBefore).TotalSeconds + $leaseTtl }
    $t2Disp = 'n/a'; if ($t2) { $t2Disp = $t2.ToString('o') }
    $rtoDisp = 'n/a'; if ($null -ne $rto) { $rtoDisp = [math]::Round($rto, 3) }
    Write-Host ("T2(승격)={0} new_epoch={1} RTO={2}s" -f $t2Disp, $newEpoch, $rtoDisp)

    # 격리 유지: PARTITION_SEC까지(T0 기준). 이 창 동안 구 리더는 PG 도달 불가라 쓰기 0.
    $held = ((Get-Date).ToUniversalTime() - $t0).TotalSeconds
    $remain = [int]$cfg['PARTITION_SEC'] - [int]$held
    if ($remain -gt 0) { Start-Sleep -Seconds $remain }

    Write-Section "ⓓ 복귀: docker network connect $net $target"
    & docker network connect $net $target | Out-Null
    $connected = $true
    # 복귀 노드가 팔로워로 재합류하거나 self-shutdown 하는지 관찰(soft). 좀비 리더의 첫 append는
    # 구 epoch라 fencing 0행 -> 재승격 불가. 권위는 leader_lease 직조회.
    $rejoin = Wait-For {
        try {
            $reachable = $false
            try { Get-HarnessStatus -Port $targetPort | Out-Null; $reachable = $true } catch { $reachable = $false }
            $holder = Invoke-Psql "SELECT holder FROM leader_lease WHERE resource='matching-engine'"
            $epochNow = [long](Get-Scalar "SELECT epoch FROM leader_lease WHERE resource='matching-engine'")
            # 팔로워 재합류 = 대상 노드 도달 가능 + 리스 보유자가 신 리더(구 리더 아님) + epoch 불변.
            $reachable -and ($holder -eq $newHolder) -and ($epochNow -eq $newEpoch)
        } catch { $false }
    } -TimeoutSec ([int]$cfg['REJOIN_OBSERVE_SEC']) -PollSec 1

    Write-Section 'ⓔ 사후 권위 run + fencing 판정'
    Start-Sleep -Seconds 3
    $postPort = Get-LiveNodePort
    $post = Invoke-InvariantRun -Port $postPort -RunId $runId -Window 'post'
    $staleWrites = Get-StaleWriteCount -CmdWatermark $wCmd -EvtWatermark $wEvt -OldEpoch $oldEpoch -CmdHasFencing $cmdHasFencing
    $finalEpoch = [long](Get-Scalar "SELECT epoch FROM leader_lease WHERE resource='matching-engine'")

    $rows = @{}
    foreach ($c in $post.checks) { $rows[$c.checkName] = [long]$c.violatedRows }
    $assetSum = $rows['C01_ASSET_CONSERVATION']
    $cmdGap = $rows['C05_CMD_GAPLESS']; $evtGap = $rows['C06_EVT_GAPLESS']
    $ordersLost = $rows['C13A_ORDER_MATERIALIZED']
    $rpo = [long]$cmdGap + [long]$evtGap + [long]$ordersLost

    $epochOk = ($newEpoch -eq $oldEpoch + 1) -and ($finalEpoch -eq $oldEpoch + 1)
    $postClean = Test-ExperimentIntegrity $post
    # §3 E5 합격 기준: 이중 쓰기 0 + 무결성 0. epochOk(신 리더 실제 승격)로 무승격 공허 통과를 막는다.
    # RTO/재합류는 게이팅에서 제외하고 증거로 보고(§3에 RTO 기준 없음, E4/E6 소관).
    $verdict = 'FAIL'
    if (($staleWrites -eq 0) -and $epochOk -and $postClean) { $verdict = 'PASS' }

    $rtoRounded = $null; if ($null -ne $rto) { $rtoRounded = [math]::Round($rto, 3) }
    $downtimeRounded = $null; if ($null -ne $downtime) { $downtimeRounded = [math]::Round($downtime, 3) }
    $firstTradeVal = $null; if ($t5) { $firstTradeVal = $t5.ToString('o') }
    $rtoEvid = 'n/a'; if ($null -ne $rtoRounded) { $rtoEvid = $rtoRounded }
    $tradesAfter = Get-TradeCount

    # 페일오버 실험이라 failover_runs에 적재한다(E1/E9와 달리 epoch 변화가 있는 사건).
    $rec = Record-Failover -Port $postPort -Body @{
        experimentId = 'E5'; oldLeader = $oldHolder; newLeader = $newHolder
        oldEpoch = $oldEpoch; newEpoch = $newEpoch; firstTradeTs = $firstTradeVal
        rtoSeconds = $rtoRounded; downtimeSeconds = $downtimeRounded
        rpoRecords = $rpo; verdict = $verdict
        details = @{ runId = $runId; staleWrites = "$staleWrites"; finalEpoch = "$finalEpoch";
                     rejoin = "$([bool]$rejoin)"; assetSum = "$assetSum"; cmdGap = "$cmdGap";
                     evtGap = "$evtGap"; tradesBefore = "$tradesAtInject"; tradesAfter = "$tradesAfter" }
    }

    Write-Host ''
    Write-Host '── E5 증거(split-brain 반증) ────────────' -ForegroundColor Green
    Write-Host ("verdict            : {0}" -f $verdict)
    Write-Host ("이중 쓰기(stale)   : {0}  (구 epoch={1} 워터마크 이후 저널 행)" -f $staleWrites, $oldEpoch)
    Write-Host ("epoch              : {0} -> {1} (final={2}, 정확히 +1={3})" -f $oldEpoch, $newEpoch, $finalEpoch, $epochOk)
    Write-Host ("RTO(체결 재개)     : {0} s (목표 < {1})" -f $rtoEvid, $rtoTarget)
    Write-Host ("무결성(ALWAYS 스코프): {0}" -f $postClean)
    Write-Host ("자산합(C01)        : {0}" -f $assetSum)
    Write-Host ("저널 gap C05/C06   : {0} / {1}" -f $cmdGap, $evtGap)
    Write-Host ("복귀 팔로워 재합류 : {0} (관찰, 핵심 판정 비게이팅)" -f ([bool]$rejoin))
    Write-Host ("failover_run_id    : {0}" -f $rec.failover_run_id)
    Write-Host '────────────────────────────────────────'
    Write-Host "invariant_checks 사후 위반 요약(run_id=$runId):"
    Invoke-Psql "SELECT check_name, ok, violated_rows FROM invariant_checks WHERE details->>'run_id'='$runId' AND details->>'window'='post' ORDER BY ok, check_name"
}
finally {
    # 종료 훅: 네트워크 복귀 무조건 보장(분할 잔존 방지). 이미 연결돼 있으면 무시.
    if (-not $connected) {
        Write-Section '종료 훅: 네트워크 복귀'
        try { & docker network connect $net $target | Out-Null } catch { }
    }
}

if ($verdict -ne 'PASS') { Write-Host 'E5 FAIL.' -ForegroundColor Red; exit 1 }
Write-Host 'E5 PASS.' -ForegroundColor Green
