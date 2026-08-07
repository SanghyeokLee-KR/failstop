# docs §3 E5 네트워크 분할(split-brain 반증, crown jewel).
# docker network disconnect chaos-net chaos-api-1(프로세스 생존, 네트워크만 격리) -> 60s -> connect.
# 정본 §3 주입명은 데모 컨테이너(exchange-demo_default/api-1-1)라 격리 스택 실명으로 정정한다.
# 판정(§3 E5 합격 기준, 안전성 중심): 이중 쓰기 0(워터마크 이후 구 epoch fencing 저널 행 0) +
# 무결성 0. 여기에 공허 통과 차단용으로 [신 리더 실제 승격(epoch 단조 상승 + holder 교체)]과
# [서비스 재개(체결 증가)]를 더해, split-brain 시나리오가 실제 성립하고 서비스가 살아난
# 경우에만 PASS로 본다. 쓰기가 0이면 "이중 쓰기 0"은 증거가 아니라 무활동이기 때문이다.
# RTO<30s는 E4/E6 소관이라 임계 게이팅에서 빼고 증거로만 보고한다(재개 여부는 게이팅).
# 복귀 노드 팔로워 재합류도 재기동 타이밍 변동이 커 증거로만 보고한다(fencing 증명이 지배 증거).
#
# 실행 전제: 승격 가능한 스탠바이가 실재해야 한다. 종신 강등은 프로세스 내 플래그이고 서버에
# 자기 종료 경로가 없어(restart 정책도 발동하지 않는다) 한 번 태운 노드는 컨테이너를 다시
# 세우기 전까지 후보로 돌아오지 않는다. 페일오버 실험을 연달아 돌릴 때는 실험 사이에
# down -v + up으로 스택을 새로 세운다. 생략하면 다음 실험이 승격 후보 0으로 확정 FAIL한다.
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
    # 누적 count가 아니라 증분으로 본다. 누적이면 앞선 실험이 남긴 체결만으로 즉시 통과해
    # 봇이 죽었거나 1 ops로 파킹된 상태에서도 주입한다. 부하 없이 주입하면 승격 후 재개를
    # 관측할 수 없고, 그 FAIL은 코드 결함이 아니라 판정 결함이다.
    $tradesBase = Get-TradeCount
    $ok = Wait-For { (Get-TradeCount) - $tradesBase -ge [long]$cfg['MIN_TRADES_BEFORE_INJECT'] } -TimeoutSec 120 -PollSec 1
    if (-not $ok) { throw "체결 증분 미달(기준 $($cfg['MIN_TRADES_BEFORE_INJECT'])건). 봇 부하가 실리지 않았다. chaos-bots 상태와 CHAOS_BOTS_OPS를 확인한다." }

    # 실 리더를 대상 삼는다. 리더가 아닌 노드를 분할하면 페일오버가 없어 공허 FAIL 난다(README 리허설).
    $detected = Get-LeaderContainer
    if ($detected) {
        $target = $detected
        if ($target -eq 'chaos-api-1') { $targetPort = 18080; $standbyPort = 28080 }
        else { $targetPort = 28080; $standbyPort = 18080 }
        Write-Host "리더 감지: $target (config 기본과 무관하게 실 리더 대상). standby=$standbyPort"
    } else {
        # config 기본값으로 강행하면 팔로워를 대상 삼을 수 있고, 그때는 승격이 없어 확정 FAIL이
        # 난다. 그 FAIL은 코드 결함이 아니라 판정 결함이므로 실행 자체를 막는다.
        throw '리더 감지 실패. 실 리더를 특정하지 못한 채 주입하면 판정이 무의미하다. leader_lease holder와 컨테이너 로그를 대조해 리더를 확인한 뒤 재실행한다.'
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
    # T1(마지막 heartbeat) 역산 기준을 주입 직전 값으로 다시 잡는다. ⓐ 시점 스냅샷을 쓰면
    # 사전 권위 run과 시계 가드가 도는 동안의 갱신분이 그대로 다운타임에 더해져 과대 산출된다.
    $leaseUntilBefore = ([datetimeoffset](Get-HarnessStatus -Port $prePort).leaseUntil).UtcDateTime
    & docker network disconnect $net $target | Out-Null
    # E7 ⓑ와 동형의 유령 주입 가드. 분할이 실제로 걸리지 않았는데 진행하면 승격이 없어
    # 확정 FAIL이 나고, 그 FAIL의 원인은 코드가 아니라 주입 실패다.
    if ($LASTEXITCODE -ne 0) { throw "docker network disconnect 실패(종료코드 $LASTEXITCODE). 유령 주입으로 진행하면 확정 위양성이다." }
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
    $t5 = Wait-FirstTradeAfter -OldEpoch $oldEpoch -T0 $t0 -TimeoutSec $observeSec
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
    # 성공을 확인한 뒤에만 플래그를 올린다. 먼저 올리면 실패 시 종료 훅이 재시도하지 않아
    # 대상이 chaos-net에서 영구 이탈하고 이후 전 실험이 무관한 이유로 FAIL한다(E7 ⓓ와 동형).
    if ($LASTEXITCODE -eq 0) { $connected = $true }
    else { Write-Host "docker network connect 실패(종료코드 $LASTEXITCODE). 종료 훅에서 재시도한다." -ForegroundColor Yellow }
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
    # Get-LiveNodePort는 18080을 선착순으로 잡으므로 방금 재연결된 구 리더가 사후 run·적재 대상이
    # 될 수 있다. 구 리더는 강등 상태라 판정 대상으로 부적절하다. 승격을 관찰한 스탠바이로 고정한다.
    $postPort = $standbyPort
    $post = Invoke-InvariantRun -Port $postPort -RunId $runId -Window 'post'
    $staleWrites = Get-StaleWriteCount -CmdWatermark $wCmd -EvtWatermark $wEvt -OldEpoch $oldEpoch -CmdHasFencing $cmdHasFencing
    $finalEpoch = [long](Get-Scalar "SELECT epoch FROM leader_lease WHERE resource='matching-engine'")

    $rows = @{}
    foreach ($c in $post.checks) { $rows[$c.checkName] = [long]$c.violatedRows }
    $assetSum = $rows['C01_ASSET_CONSERVATION']
    $cmdGap = $rows['C05_CMD_GAPLESS']; $evtGap = $rows['C06_EVT_GAPLESS']
    $ordersLost = $rows['C13A_ORDER_MATERIALIZED']
    $rpo = [long]$cmdGap + [long]$evtGap + [long]$ordersLost

    # 승격 재생 실패는 회복 가능 반납이라 한 페일오버 사건에서 [반납 -> 만료 -> 재승격]이
    # 반복될 수 있고 그때 epoch가 +2 이상 오른다(DB 설계 §5.4). 정확히 +1을 요구하면 그 정상
    # 동작을 split-brain 흔적으로 오판한다. split-brain 판정은 아래 staleWrites(구 epoch fencing
    # 축 저널 행 0)가 전담하므로 epoch는 단조 증가만 본다.
    $epochOk = ($newEpoch -ge $oldEpoch + 1) -and ($finalEpoch -ge $newEpoch)
    # epoch 상승만으로는 "다른 노드가 받았다"가 증명되지 않는다. [반납 -> 만료 -> 같은 노드
    # 재승격]도 epoch를 올리고, 승격과 종신 강등이 경합하면 아무도 서빙하지 않는데 epoch만 오른다.
    $holderChanged = (-not [string]::IsNullOrWhiteSpace($newHolder)) -and ($newHolder -ne $oldHolder)
    $postClean = Test-ExperimentIntegrity $post
    $tradesAfter = Get-TradeCount
    # 서비스 재개는 안전성 판정의 전제다. 쓰기가 0이면 "이중 쓰기 0"은 증거가 아니라 무활동이다.
    # 종전 게이트는 [stale 0 + epoch 상승 + 무결성 0]뿐이라 페일오버 후 매칭이 영영 재개되지
    # 않아도 전부 참이 됐다. 무결성 체크가 전부 count 기반이라 빈 결과도 0위반이기 때문이다.
    # RTO 임계는 E4/E6 소관이라 여전히 빼되, 재개 자체는 존재 명제로 요구한다.
    $serviceResumed = ($null -ne $t5) -and ($tradesAfter -gt $tradesAtInject)
    # §3 E5 합격 기준: 이중 쓰기 0 + 무결성 0. 여기에 실제 승격(epoch·holder)과 서비스 재개를
    # 더해 공허 통과를 막는다. RTO 임계·재합류는 게이팅 제외, 증거로만 보고한다.
    $verdict = 'FAIL'
    if (($staleWrites -eq 0) -and $epochOk -and $holderChanged -and $postClean -and $serviceResumed) {
        $verdict = 'PASS'
    }

    $rtoRounded = $null; if ($null -ne $rto) { $rtoRounded = [math]::Round($rto, 3) }
    $downtimeRounded = $null; if ($null -ne $downtime) { $downtimeRounded = [math]::Round($downtime, 3) }
    $firstTradeVal = $null; if ($t5) { $firstTradeVal = $t5.ToString('o') }
    $rtoEvid = 'n/a'; if ($null -ne $rtoRounded) { $rtoEvid = $rtoRounded }

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
    Write-Host ("epoch              : {0} -> {1} (final={2}, 단조 상승={3})" -f $oldEpoch, $newEpoch, $finalEpoch, $epochOk)
    Write-Host ("리더 교체          : {0}  ({1} -> {2})" -f $holderChanged, $oldHolder, $newHolder)
    Write-Host ("서비스 재개        : {0}  (체결 {1} -> {2})" -f $serviceResumed, $tradesAtInject, $tradesAfter)
    Write-Host ("RTO(체결 재개)     : {0} s (목표 < {1}, 증거만)" -f $rtoEvid, $rtoTarget)
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
    # 종료 훅: 복귀를 조건 없이 시도한다. $connected를 신뢰하면 플래그가 먼저 올라간 실패
    # 경로에서 분할이 잔존하고, 빈 catch는 그 실패를 무음으로 삼킨다(E7 종료 훅과 동형).
    Write-Section '종료 훅: 네트워크 복귀(무조건)'
    if (-not $connected) { Write-Host '본문 복귀 미완료. 종료 훅이 복구한다.' -ForegroundColor Yellow }
    try {
        & docker network connect $net $target 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Host "network connect 종료코드 $LASTEXITCODE (이미 연결돼 있으면 정상)" -ForegroundColor DarkGray }
    } catch { Write-Host "network connect 시도 중 예외: $($_.Exception.Message)" -ForegroundColor Yellow }
}

if ($verdict -ne 'PASS') { Write-Host 'E5 FAIL.' -ForegroundColor Red; exit 1 }
Write-Host 'E5 PASS.' -ForegroundColor Green
# 명시 exit 0. PowerShell은 스크립트 종료코드로 마지막 네이티브 명령의 $LASTEXITCODE를
# 쓴다. 종료 훅의 docker unpause는 이미 실행 중인 정상 경우에도 비영을 내므로, 그대로
# 두면 판정이 PASS인데 종료코드가 1로 새어나간다(실측).
exit 0
