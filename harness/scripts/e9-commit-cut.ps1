# docs §3 E9 커밋 응답 절단(멱등성 증명). 리더 pg 프록시에 reset_peer(또는 limit_data) 주입해
# app<->pg 커밋 응답을 절단한다(in-doubt commit). 클라이언트는 UNKNOWN -> 동일 client_order_id
# 재조회/재전송 -> uq_orders_client 멱등 replay. 판정: 이중 접수 0(C07/C07') · gapless · 자산합 0 ·
# 클라 수렴(해제 후 체결 진행). 페일오버 아님. 창은 리스 TTL 미만이라 승격을 유발하지 않으나, 만약
# 승격이 나도 멱등 판정은 dup=0으로 독립적이라 verdict를 epoch 변화에 게이팅하지 않는다.
param([string] $ConfigPath = (Join-Path $PSScriptRoot '..\config\e9-commit-cut.env'))

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
$cfg = Read-EnvFile $ConfigPath
$runId = "E9-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
$proxy = $cfg['LEADER_PROXY']
$port = [int]$cfg['LEADER_PORT']
$toxicType = $cfg['TOXIC_TYPE']

function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades" -Required) }

Write-Section "E9(커밋 응답 절단) 시작 runId=$runId proxy=$proxy type=$toxicType"
try {
    # 자기 부하를 명시로 세운다. 생략하면 앞선 실험이 남긴 ops를 이월받는다(E6 직후면 400,
    # 드레인 직후면 정지 상태). 커밋 응답 절단은 쓰기가 실려 있어야 in-doubt commit을 만든다.
    if ($cfg['BASE_OPS']) { Set-BotOps ([int]$cfg['BASE_OPS']) }
    Start-Sleep -Seconds ([int]$cfg['WARMUP_SEC'])
    # 누적이 아니라 증분으로 본다. 누적이면 앞선 실험이 남긴 체결만으로 즉시 통과해 무부하로
    # 주입하게 된다. 그러면 dup=0은 멱등 기제의 증거가 아니라 무활동이고, converged도 봇이
    # 겨우 흘리는 체결로 성립해 공허 통과한다(E2·E5와 같은 술어).
    $tradesBase = Get-TradeCount
    $ok = Wait-For { (Get-TradeCount) - $tradesBase -ge [long]$cfg['MIN_TRADES_BEFORE_INJECT'] } -TimeoutSec 120 -PollSec 1
    if (-not $ok) { throw "체결 증분 미달(기준 $($cfg['MIN_TRADES_BEFORE_INJECT'])건). 봇 부하가 실리지 않았다. chaos-bots 상태와 BASE_OPS를 확인한다." }

    # 리더의 pg 프록시에 주입해야 커밋 경로가 실제 절단된다. 팔로워 프록시면 dup=0가 자명 성립해
    # 멱등 기제를 시험하지 않고 공허 통과한다(§3 E9 주입점=리더 pg 프록시).
    $detected = Get-LeaderContainer
    if ($detected) {
        if ($detected -eq 'chaos-api-1') { $proxy = 'pg-api1'; $port = 18080 }
        else { $proxy = 'pg-api2'; $port = 28080 }
        Write-Host "리더 감지: $detected -> proxy=$proxy port=$port"
    } else {
        Write-Host "리더 감지 실패. config LEADER_PROXY=$proxy 사용(팔로워면 커밋 경로 미절단 주의)."
    }

    Write-Section 'ⓐ 사전 권위 run'
    $prePort = Get-LiveNodePort
    Reset-LoadtestAcks -Port $prePort
    $s0 = Get-HarnessStatus -Port $prePort
    $oldEpoch = [long]$s0.epoch
    $pre = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'pre'
    # X-Idempotent-Replay 계수는 옵션 지표. 앱 메트릭에 멱등 replay 카운터가 있으면 델타를 잡는다.
    $replayBefore = Get-AppMetric -Port $port -Pattern 'exchange_idempotent_replay_total(?:\{[^}]*\})?\s+([0-9.eE+]+)'
    $tradesAtInject = Get-TradeCount
    Write-Host "사전 verdict=$($pre.verdict) old_epoch=$oldEpoch replay_before=$replayBefore"

    Write-Section "ⓑ 주입: $toxicType on $proxy (커밋 응답 절단 창)"
    $t0 = (Get-Date).ToUniversalTime()
    if ($toxicType -eq 'limit_data') {
        Add-Toxic -Proxy $proxy -Name 'e9-cut' -Type 'limit_data' -Attributes @{ bytes = [int]$cfg['LIMIT_DATA_BYTES'] }
    } else {
        # reset_peer timeout=0 = 데이터 전송 시 즉시 RST. 커밋 직후 응답 소켓을 끊어 in-doubt commit 재현.
        Add-Toxic -Proxy $proxy -Name 'e9-cut' -Type 'reset_peer' -Attributes @{ timeout = 0 }
    }
    Write-Host "T0=$($t0.ToString('o'))"
    Start-Sleep -Seconds ([int]$cfg['TOXIC_WINDOW_SEC'])

    Write-Section 'ⓒ 해제 + 클라 수렴 관찰'
    Remove-AllToxics -Proxy $proxy
    Start-Sleep -Seconds ([int]$cfg['POST_OBSERVE_SEC'])

    Write-Section 'ⓓ 사후 권위 run + 멱등 판정'
    $postPort = Get-LiveNodePort
    $post = Invoke-InvariantRun -Port $postPort -RunId $runId -Window 'post'
    $s1 = Get-HarnessStatus -Port $postPort
    $newEpoch = [long]$s1.epoch
    $replayAfter = Get-AppMetric -Port $port -Pattern 'exchange_idempotent_replay_total(?:\{[^}]*\})?\s+([0-9.eE+]+)'

    $rows = @{}
    foreach ($c in $post.checks) { $rows[$c.checkName] = [long]$c.violatedRows }
    $dupOrderProj = $rows['C07_DUP_ORDER']
    $dupOrderJrnl = $rows['C07P_DUP_ORDER_JOURNAL']
    $dupOrder = [long]$dupOrderProj + [long]$dupOrderJrnl
    $assetSum = $rows['C01_ASSET_CONSERVATION']
    $cmdGap = $rows['C05_CMD_GAPLESS']; $evtGap = $rows['C06_EVT_GAPLESS']

    # 클라 수렴: 해제 후 체결이 다시 진행(무한 UNKNOWN 없음).
    $tradesAfter = Get-TradeCount
    $converged = ($tradesAfter -gt $tradesAtInject)

    $replayDelta = 'n/a'
    if ($null -ne $replayBefore -and $null -ne $replayAfter) {
        $replayDelta = [double]$replayAfter - [double]$replayBefore
    }

    $postClean = Test-ExperimentIntegrity $post
    $verdict = 'FAIL'
    if ($postClean -and ($dupOrder -eq 0) -and ([long]$cmdGap -eq 0) -and ([long]$evtGap -eq 0) `
        -and ([long]$assetSum -eq 0) -and $converged) {
        $verdict = 'PASS'
    }

    Write-Verdict -ExperimentId 'E9' -RunId $runId -Verdict $verdict -Metrics @{
        dupOrderProjection = [long]$dupOrderProj
        dupOrderJournal    = [long]$dupOrderJrnl
        dupOrderTotal      = $dupOrder
        assetSum           = [long]$assetSum
        cmdGap             = [long]$cmdGap
        evtGap             = [long]$evtGap
        converged          = $converged
        tradesBefore       = $tradesAtInject
        tradesAfter        = $tradesAfter
        idempotentReplayDelta = $replayDelta
        epochBefore        = $oldEpoch
        epochAfter         = $newEpoch
        postAlwaysClean    = $postClean
    }
    Write-Host "invariant_checks 사후 위반 요약(run_id=$runId):"
    Invoke-Psql "SELECT check_name, ok, violated_rows FROM invariant_checks WHERE details->>'run_id'='$runId' AND details->>'window'='post' ORDER BY ok, check_name"
}
finally {
    Write-Section '종료 훅: 토식 해제'
    Remove-AllToxics -Proxy $proxy
}

if ($verdict -ne 'PASS') { Write-Host 'E9(커밋 응답 절단) FAIL.' -ForegroundColor Red; exit 1 }
Write-Host 'E9(커밋 응답 절단) PASS.' -ForegroundColor Green
