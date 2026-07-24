# docs §3 E7 그레이 장애(행/무응답). docker pause chaos-api-1(freezer cgroup, TCP 생존) -> 45s -> unpause.
# crash와 달리 재기동 없음. lease 갱신 중단 -> 만료 -> 승격. unpause된 좀비 리더의 첫 heartbeat/append는
# 구 epoch라 fencing 0행 -> self-shutdown. 판정: 승격 정상, 좀비 stale 쓰기 0, epoch 정확히 +1, RTO < 30s.
# 종료 훅에서 docker unpause를 무조건 실행한다(pause는 restart 정책으로 복구 안 됨, §3).
param([string] $ConfigPath = (Join-Path $PSScriptRoot '..\config\e7-gray-pause.env'))

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
$cfg = Read-EnvFile $ConfigPath
$runId = "E7-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
$target = $cfg['TARGET_NODE']
$targetPort = [int]$cfg['TARGET_PORT']
$standbyPort = [int]$cfg['STANDBY_PORT']
$rtoTarget = [int]$cfg['RTO_TARGET_SEC']
$observeSec = [int]$cfg['POST_OBSERVE_SEC']

function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades") }
$paused = $false

Write-Section "E7(그레이 장애/pause) 시작 runId=$runId"
try {
    Start-Sleep -Seconds ([int]$cfg['WARMUP_SEC'])
    $ok = Wait-For { (Get-TradeCount) -ge [long]$cfg['MIN_TRADES_BEFORE_INJECT'] } -TimeoutSec 120 -PollSec 1
    if (-not $ok) { throw '체결 미달, 봇 상태 확인' }

    # 실 리더를 대상 삼는다. 팔로워를 pause하면 페일오버가 없어 공허 FAIL 난다(README 리허설).
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

    # 시계 동기 전제조건(#58): 스큐면 pause 없이 SKIP. 미동결이라 종료 훅 unpause는 무해.
    if (Test-FailoverClockGuard -ExperimentId 'E7') { exit 0 }

    Write-Section "ⓑ 주입: docker pause $target (프로세스 동결)"
    $tradesAtInject = Get-TradeCount
    & docker pause $target | Out-Null
    $paused = $true
    $t0 = (Get-Date).ToUniversalTime()
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

    # 동결 유지: PAUSE_SEC까지(T0 기준).
    $held = ((Get-Date).ToUniversalTime() - $t0).TotalSeconds
    $remain = [int]$cfg['PAUSE_SEC'] - [int]$held
    if ($remain -gt 0) { Start-Sleep -Seconds $remain }

    Write-Section "ⓓ 해제: docker unpause $target (좀비 리더 깨어남)"
    & docker unpause $target | Out-Null
    $paused = $false
    # 좀비 관찰(soft): unpause된 구 리더는 구 epoch heartbeat/append -> fencing 0행 -> self-shutdown.
    # 재승격 없음(리스 보유자=신 리더, epoch 불변)을 leader_lease 직조회로 확인.
    $zombieOk = Wait-For {
        try {
            $holder = Invoke-Psql "SELECT holder FROM leader_lease WHERE resource='matching-engine'"
            $epochNow = [long](Get-Scalar "SELECT epoch FROM leader_lease WHERE resource='matching-engine'")
            ($holder -eq $newHolder) -and ($epochNow -eq $newEpoch)
        } catch { $false }
    } -TimeoutSec ([int]$cfg['ZOMBIE_OBSERVE_SEC']) -PollSec 1

    Write-Section 'ⓔ 사후 권위 run + 좀비 stale 쓰기 판정'
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
    $rtoOk = ($null -ne $rto -and $rto -lt $rtoTarget)
    $postClean = Test-ExperimentIntegrity $post
    # §3 E7 합격 기준: 승격 정상 + 좀비 stale 쓰기 0 + RTO<30s. 무결성은 ALWAYS 스코프로 본다.
    $verdict = 'FAIL'
    if (($staleWrites -eq 0) -and $epochOk -and $postClean -and $rtoOk) { $verdict = 'PASS' }

    $rtoRounded = $null; if ($null -ne $rto) { $rtoRounded = [math]::Round($rto, 3) }
    $downtimeRounded = $null; if ($null -ne $downtime) { $downtimeRounded = [math]::Round($downtime, 3) }
    $firstTradeVal = $null; if ($t5) { $firstTradeVal = $t5.ToString('o') }
    $rtoEvid = 'n/a'; if ($null -ne $rtoRounded) { $rtoEvid = $rtoRounded }
    $tradesAfter = Get-TradeCount

    $rec = Record-Failover -Port $postPort -Body @{
        experimentId = 'E7'; oldLeader = $oldHolder; newLeader = $newHolder
        oldEpoch = $oldEpoch; newEpoch = $newEpoch; firstTradeTs = $firstTradeVal
        rtoSeconds = $rtoRounded; downtimeSeconds = $downtimeRounded
        rpoRecords = $rpo; verdict = $verdict
        details = @{ runId = $runId; staleWrites = "$staleWrites"; finalEpoch = "$finalEpoch";
                     zombieShutdown = "$([bool]$zombieOk)"; assetSum = "$assetSum"; cmdGap = "$cmdGap";
                     evtGap = "$evtGap"; tradesBefore = "$tradesAtInject"; tradesAfter = "$tradesAfter" }
    }

    Write-Host ''
    Write-Host '── E7 증거(그레이 장애) ─────────────────' -ForegroundColor Green
    Write-Host ("verdict            : {0}" -f $verdict)
    Write-Host ("좀비 stale 쓰기    : {0}  (구 epoch={1} 워터마크 이후 저널 행)" -f $staleWrites, $oldEpoch)
    Write-Host ("epoch              : {0} -> {1} (final={2}, 정확히 +1={3})" -f $oldEpoch, $newEpoch, $finalEpoch, $epochOk)
    Write-Host ("RTO(체결 재개)     : {0} s (목표 < {1})" -f $rtoEvid, $rtoTarget)
    Write-Host ("무결성(ALWAYS 스코프): {0}" -f $postClean)
    Write-Host ("자산합(C01)        : {0}" -f $assetSum)
    Write-Host ("좀비 강등 관찰     : {0} (핵심 판정 비게이팅)" -f ([bool]$zombieOk))
    Write-Host ("failover_run_id    : {0}" -f $rec.failover_run_id)
    Write-Host '────────────────────────────────────────'
    Write-Host "invariant_checks 사후 위반 요약(run_id=$runId):"
    Invoke-Psql "SELECT check_name, ok, violated_rows FROM invariant_checks WHERE details->>'run_id'='$runId' AND details->>'window'='post' ORDER BY ok, check_name"
}
finally {
    # 종료 훅: unpause 무조건 보장. pause 잔존 시 컨테이너가 영구 동결된다.
    if ($paused) {
        Write-Section '종료 훅: docker unpause'
        try { & docker unpause $target | Out-Null } catch { }
    }
}

if ($verdict -ne 'PASS') { Write-Host 'E7 FAIL.' -ForegroundColor Red; exit 1 }
Write-Host 'E7 PASS.' -ForegroundColor Green
