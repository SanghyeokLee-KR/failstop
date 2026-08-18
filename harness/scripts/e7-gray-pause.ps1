# docs §3 E7 그레이 장애(행/무응답). docker pause chaos-api-1(freezer cgroup, TCP 생존) -> 45s -> unpause.
# crash와 달리 재기동 없음. lease 갱신 중단 -> 만료 -> 승격. unpause된 좀비 리더의 첫 heartbeat/append는
# 구 epoch라 fencing 0행 -> self-shutdown. 판정: 승격 정상(epoch 단조 상승 + holder 교체),
# 좀비 stale 쓰기 0, RTO < 30s.
# 종료 훅에서 docker unpause를 무조건 실행한다(pause는 restart 정책으로 복구 안 됨, §3).
#
# 실행 전제: 승격 가능한 스탠바이가 실재해야 한다. 종신 강등은 프로세스 내 플래그이고 서버에
# 자기 종료 경로가 없어(restart 정책도 발동하지 않는다) 한 번 태운 노드는 컨테이너를 다시
# 세우기 전까지 후보로 돌아오지 않는다. E5 직후 이 실험을 돌리면 승격 후보가 0이라 확정
# 위양성 FAIL이 난다. 실험 사이에 down -v + up으로 스택을 새로 세운다.
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

function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades" -Required) }
$paused = $false

Write-Section "E7(그레이 장애/pause) 시작 runId=$runId"
try {
    # 자기 부하를 명시로 세운다. 생략하면 앞선 실험이 남긴 ops를 이월받는다(E6 직후면 400,
    # 드레인 직후면 정지 상태). 부하가 무엇인지 모르는 채 동결하면 판정 조건이 실행마다 달라진다.
    if ($cfg['BASE_OPS']) { Set-BotOps ([int]$cfg['BASE_OPS']) }
    Start-Sleep -Seconds ([int]$cfg['WARMUP_SEC'])
    # 누적 count가 아니라 증분으로 본다. 누적이면 앞선 실험이 남긴 체결만으로 즉시 통과해
    # 봇이 죽었거나 1 ops로 파킹된 상태에서도 주입한다. 부하 없이 주입하면 Wait-FirstTradeAfter가
    # 타임아웃해 RTO n/a로 위양성 FAIL이 난다(E5는 같은 조건에서 공허 PASS로 기운다).
    $tradesBase = Get-TradeCount
    $ok = Wait-For { (Get-TradeCount) - $tradesBase -ge [long]$cfg['MIN_TRADES_BEFORE_INJECT'] } -TimeoutSec 120 -PollSec 1
    if (-not $ok) { throw "체결 증분 미달(기준 $($cfg['MIN_TRADES_BEFORE_INJECT'])건). 봇 부하가 실리지 않았다. chaos-bots 상태와 BASE_OPS를 확인한다." }

    # 실 리더를 대상 삼는다. 팔로워를 pause하면 페일오버가 없어 공허 FAIL 난다(README 리허설).
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

    # 시계 동기 전제조건(#58): 스큐면 pause 없이 SKIP. 미동결이라 종료 훅 unpause는 무해.
    if (Test-FailoverClockGuard -ExperimentId 'E7') { exit 0 }

    Write-Section "ⓑ 주입: docker pause $target (프로세스 동결)"
    $tradesAtInject = Get-TradeCount
    # T1(마지막 heartbeat) 역산 기준을 주입 직전 값으로 다시 잡는다. ⓐ 시점 스냅샷을 쓰면
    # 사전 권위 run과 시계 가드가 도는 동안의 갱신분이 그대로 다운타임에 더해져 과대 산출된다.
    $leaseUntilBefore = ([datetimeoffset](Get-HarnessStatus -Port $prePort).leaseUntil).UtcDateTime
    & docker pause $target | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker pause 실패(종료코드 $LASTEXITCODE). 유령 주입으로 진행하면 확정 위양성이다." }
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
    $t5 = Wait-FirstTradeAfter -OldEpoch $oldEpoch -T0 $t0 -TimeoutSec $observeSec
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
    # 성공을 확인한 뒤에만 플래그를 내린다. 먼저 내리면 실패 시 종료 훅이 재시도하지 않아
    # 컨테이너가 영구 동결로 남고 이후 전 실험이 무관한 이유로 FAIL한다.
    if ($LASTEXITCODE -eq 0) { $paused = $false } else { Write-Host "docker unpause 실패(종료코드 $LASTEXITCODE). 종료 훅에서 재시도한다." -ForegroundColor Yellow }
    # 좀비 관찰(soft): unpause된 구 리더는 구 epoch heartbeat/append -> fencing 0행 -> self-shutdown.
    # 재승격 없음(리스 보유자=신 리더, epoch 불변)을 leader_lease 직조회로 확인.
    $zombieOk = Wait-For {
        try {
            $holder = Invoke-Psql "SELECT holder FROM leader_lease WHERE resource='matching-engine'"
            # 리스 행 부재·psql 무응답은 $null이다. 캐스팅해 0으로 비교하지 않고 미확인으로 둔다.
            $epochNow = Get-Scalar "SELECT epoch FROM leader_lease WHERE resource='matching-engine'"
            ($holder -eq $newHolder) -and ($null -ne $epochNow) -and ([long]$epochNow -eq $newEpoch)
        } catch { $false }
    } -TimeoutSec ([int]$cfg['ZOMBIE_OBSERVE_SEC']) -PollSec 1

    Write-Section 'ⓔ 사후 권위 run + 좀비 stale 쓰기 판정'
    Start-Sleep -Seconds 3
    $postPort = Get-LiveNodePort
    $post = Invoke-InvariantRun -Port $postPort -RunId $runId -Window 'post'
    $staleWrites = Get-StaleWriteCount -CmdWatermark $wCmd -EvtWatermark $wEvt -OldEpoch $oldEpoch -CmdHasFencing $cmdHasFencing
    # 리스 행 부재는 리더 부재라 정상 상태가 아니지만 질의로는 0행이 나올 수 있다. 0으로 폴백하면
    # final=0이 되어 아래 단조 상승 판정이 우연히 거짓이 되는 것에 기대게 된다. 명시로 가른다.
    $finalEpochRaw = Get-Scalar "SELECT epoch FROM leader_lease WHERE resource='matching-engine'"
    $finalEpoch = $null; if ($null -ne $finalEpochRaw) { $finalEpoch = [long]$finalEpochRaw }
    $finalEpochDisp = 'n/a(리스 행 부재)'; if ($null -ne $finalEpoch) { $finalEpochDisp = $finalEpoch }

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
    $epochOk = ($newEpoch -ge $oldEpoch + 1) -and ($null -ne $finalEpoch) -and ($finalEpoch -ge $newEpoch)
    # epoch 상승만으로는 "다른 노드가 받았다"가 증명되지 않는다. [반납 -> 만료 -> 같은 노드
    # 재승격]도 epoch를 올리고, 승격과 종신 강등이 경합하면 아무도 서빙하지 않는데 epoch만 오른다.
    $holderChanged = (-not [string]::IsNullOrWhiteSpace($newHolder)) -and ($newHolder -ne $oldHolder)
    $rtoOk = ($null -ne $rto -and $rto -lt $rtoTarget)
    $postClean = Test-ExperimentIntegrity $post
    # §3 E7 합격 기준: 승격 정상 + 좀비 stale 쓰기 0 + RTO<30s. 무결성은 ALWAYS 스코프로 본다.
    # rtoOk가 t5 실재를 요구하므로 서비스 재개는 이미 게이팅된다(E5와 달리 별도 항이 불필요).
    $verdict = 'FAIL'
    if (($staleWrites -eq 0) -and $epochOk -and $holderChanged -and $postClean -and $rtoOk) {
        $verdict = 'PASS'
    }

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
        details = @{ runId = $runId; staleWrites = "$staleWrites"; finalEpoch = "$finalEpochDisp";
                     zombieShutdown = "$([bool]$zombieOk)"; assetSum = "$assetSum"; cmdGap = "$cmdGap";
                     evtGap = "$evtGap"; tradesBefore = "$tradesAtInject"; tradesAfter = "$tradesAfter" }
    }

    Write-Host ''
    Write-Host '── E7 증거(그레이 장애) ─────────────────' -ForegroundColor Green
    Write-Host ("verdict            : {0}" -f $verdict)
    Write-Host ("좀비 stale 쓰기    : {0}  (구 epoch={1} 워터마크 이후 저널 행)" -f $staleWrites, $oldEpoch)
    Write-Host ("epoch              : {0} -> {1} (final={2}, 단조 상승={3})" -f $oldEpoch, $newEpoch, $finalEpochDisp, $epochOk)
    Write-Host ("리더 교체          : {0}  ({1} -> {2})" -f $holderChanged, $oldHolder, $newHolder)
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
    # 종료 훅: unpause를 조건 없이 시도한다. $paused 플래그를 신뢰하면 플래그가 먼저 내려간
    # 실패 경로에서 컨테이너가 영구 동결로 남는다(정본 §3 공통 전제: unpause 무조건 실행).
    # 이미 실행 중이면 docker가 비영 종료코드를 내지만 무해하므로 결과를 보고만 한다.
    Write-Section '종료 훅: docker unpause(무조건)'
    try {
        & docker unpause $target 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Host "unpause 종료코드 $LASTEXITCODE (이미 실행 중이면 정상)" -ForegroundColor DarkGray }
    } catch { Write-Host "unpause 시도 중 예외: $($_.Exception.Message)" -ForegroundColor Yellow }
}

if ($verdict -ne 'PASS') { Write-Host 'E7 FAIL.' -ForegroundColor Red; exit 1 }
Write-Host 'E7 PASS.' -ForegroundColor Green
# 명시 exit 0. PowerShell은 스크립트 종료코드로 마지막 네이티브 명령의 $LASTEXITCODE를
# 쓴다. 종료 훅의 docker unpause는 이미 실행 중인 정상 경우에도 비영을 내므로, 그대로
# 두면 판정이 PASS인데 종료코드가 1로 새어나간다(실측).
exit 0
