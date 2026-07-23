# E1 리더 kill. 실험 판정 프로토콜(사전 권위 run → 주입 → 무개입 관찰 → 사후 권위 run).
# 판정: 유실 0 · 중복 0 · 자산합 0 · epoch 정확히 +1 · RTO < 30s.
# 개입 금지 구간(kill 이후)에서 스크립트는 관찰·기록만 한다.
param([string] $ConfigPath = (Join-Path $PSScriptRoot '..\config\e1.env'))

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
$cfg = Read-EnvFile $ConfigPath

$runId = "E1-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
$target = $cfg['TARGET_NODE']
$rtoTarget = [int] $cfg['RTO_TARGET_SEC']

function Get-CheckRows { param($Run, [string] $Name)
    ($Run.checks | Where-Object { $_.checkName -eq $Name } | Select-Object -First 1).violatedRows
}
function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades") }

Write-Section "E1 시작 runId=$runId target=$target"

# ── 워밍업: 정상 부하로 체결 축적 ──────────────────────────────────────
Write-Section 'warmup'
Start-Sleep -Seconds ([int]$cfg['WARMUP_SEC'])
$minTrades = [long]$cfg['MIN_TRADES_BEFORE_KILL']
$ok = Wait-For { (Get-TradeCount) -ge $minTrades } -TimeoutSec 120 -PollSec 1
if (-not $ok) { throw "체결 $minTrades 건 미달, 봇 플릿 상태 확인" }
Write-Host "누적 체결: $(Get-TradeCount)"

# ── ⓐ 사전 권위 run ───────────────────────────────────────────────────
Write-Section 'ⓐ 사전 권위 run'
$prePort = Get-LiveNodePort
Reset-LoadtestAcks -Port $prePort
$statusBefore = Get-HarnessStatus -Port $prePort
$oldEpoch = [long]$statusBefore.epoch
$oldHolder = $statusBefore.holder
$leaseUntilBefore = ([datetimeoffset]$statusBefore.leaseUntil).UtcDateTime
$tradesBefore = Get-TradeCount
$pre = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'pre'
Write-Host "사전 verdict=$($pre.verdict) 위반합=$($pre.totalViolations) old_epoch=$oldEpoch"
if ($pre.verdict -ne 'PASS') { Write-Host '경고: 사전 run이 PASS가 아님. 배경 부하 안정 후 재실행 권장' -ForegroundColor Yellow }

# ── ⓑ 주입: 리더 kill(T0) ──────────────────────────────────────────────
Write-Section 'ⓑ 주입: docker kill'
$t0 = (Get-Date).ToUniversalTime()
$tradesAtKill = Get-TradeCount
& docker kill $target | Out-Null
Write-Host "T0=$($t0.ToUniversalTime().ToString('o')) killed=$target trades_at_kill=$tradesAtKill"

# ── ⓒ 무개입 관찰: 승격(epoch+1) → 체결 재개 ───────────────────────────
Write-Section 'ⓒ 관찰(개입 금지)'
$standbyPort = $Global:CHAOS.Api2Port
$t2 = Wait-For {
    try { $s = Get-HarnessStatus -Port $standbyPort; ($s.epoch -gt $oldEpoch) -and $s.leaderAlive }
    catch { $false }
} -TimeoutSec ([int]$cfg['POST_KILL_OBSERVE_SEC']) -PollSec 0.5
if (-not $t2) { Write-Host '경고: 승격 미관찰(타임아웃)' -ForegroundColor Yellow }
$statusAfter = Get-HarnessStatus -Port $standbyPort
$newEpoch = [long]$statusAfter.epoch
$newHolder = $statusAfter.holder
Write-Host "T2(승격)=$(if($t2){$t2.ToUniversalTime().ToString('o')}else{'n/a'}) new_epoch=$newEpoch"

# T5 = 페일오버 후 최초 신규 TRADE의 engine_ts(§5-1 정의, T0 이후·신 epoch). created_at은
# 엔진 처리 시각이라 catch-up 배치의 커밋 가시화 지연과 무관한 재개 시점을 가리킨다.
$t0Iso = $t0.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fff') + 'Z'
$firstTradeSql = "SELECT min(created_at) AT TIME ZONE 'UTC' " +
    "FROM event_journal WHERE event_type='TRADE' AND fencing_epoch=$newEpoch AND created_at > '$t0Iso'"
$obsResume = Wait-For {
    -not [string]::IsNullOrWhiteSpace((Invoke-Psql $firstTradeSql))
} -TimeoutSec ([int]$cfg['POST_KILL_OBSERVE_SEC']) -PollSec 0.5
$firstTradeRaw = Invoke-Psql $firstTradeSql
$t5 = $null; $firstTradeTs = $null
if ($firstTradeRaw) {
    $firstTradeTs = $firstTradeRaw.Replace(' ', 'T') + 'Z'
    $t5 = ([datetimeoffset]$firstTradeTs).UtcDateTime
}
$t5Disp = 'n/a'; if ($t5) { $t5Disp = $t5.ToString('o') }
Write-Host "T5(체결 재개)=$t5Disp first_trade_at=$firstTradeTs"

# ── ⓓ 사후 권위 run + 측정 ─────────────────────────────────────────────
Write-Section 'ⓓ 사후 권위 run'
# 저널 안정(in-flight 소진) 대기
Start-Sleep -Seconds 3
$postPort = Get-LiveNodePort
$post = Invoke-InvariantRun -Port $postPort -RunId $runId -Window 'post'

$assetSum = Get-CheckRows $post 'C01_ASSET_CONSERVATION'
$cmdGap   = Get-CheckRows $post 'C05_CMD_GAPLESS'
$evtGap   = Get-CheckRows $post 'C06_EVT_GAPLESS'
$dupOrder = [long](Get-CheckRows $post 'C07_DUP_ORDER') + [long](Get-CheckRows $post 'C07P_DUP_ORDER_JOURNAL')
$dupFill  = Get-CheckRows $post 'C08_DUP_FILL'
$ordersLost = Get-CheckRows $post 'C13A_ORDER_MATERIALIZED'
# 컨테이너 E1의 RPO는 저널 무결(gapless + 물질화)로 판정. C16 외부 ACK 파이프라인은 미구축.
$rpoRecords = [long]$cmdGap + [long]$evtGap + [long]$ordersLost

$rto = $null; if ($t5) { $rto = ($t5 - $t0).TotalSeconds }
# §5-1 downtime=T2-T1, T1=구 lease_until - 리스 TTL(마지막 heartbeat 역산). lease_until을 T1로
# 직접 쓰면 한 TTL 과소 보고라 TTL을 더한다. lib.ps1 공용 경로(E2·E3)와 동일 공식.
$downtime = $null; if ($t2) { $downtime = ($t2 - $leaseUntilBefore).TotalSeconds + (Get-LeaseTtlSec) }
$rtoRounded = $null; if ($null -ne $rto) { $rtoRounded = [math]::Round($rto, 3) }
$downtimeRounded = $null; if ($null -ne $downtime) { $downtimeRounded = [math]::Round($downtime, 3) }
$firstTradeVal = $null; if ($firstTradeTs) { $firstTradeVal = $firstTradeTs }
$rtoDisplay = 'n/a'; if ($null -ne $rtoRounded) { $rtoDisplay = $rtoRounded }

$verdict = 'FAIL'
if ($post.criticalClean -and $t5 -and ($newEpoch -eq $oldEpoch + 1) -and ($null -ne $rto) -and ($rto -lt $rtoTarget)) {
    $verdict = 'PASS'
}

# ── ⓔ 기록 + 증거 출력 ─────────────────────────────────────────────────
Write-Section 'ⓔ 판정·기록'
$tradesAfter = Get-TradeCount
$body = @{
    experimentId = 'E1'; oldLeader = $oldHolder; newLeader = $newHolder
    oldEpoch = $oldEpoch; newEpoch = $newEpoch
    firstTradeTs = $firstTradeVal
    rtoSeconds = $rtoRounded
    downtimeSeconds = $downtimeRounded
    rpoRecords = $rpoRecords; verdict = $verdict
    details = @{ runId = $runId; assetSum = "$assetSum"; dupOrder = "$dupOrder"; dupFill = "$dupFill";
                 cmdGap = "$cmdGap"; evtGap = "$evtGap"; tradesBefore = "$tradesBefore";
                 tradesAfter = "$tradesAfter" }
}
$rec = Record-Failover -Port $postPort -Body $body

Write-Host ''
Write-Host '── E1 증거 ─────────────────────────────' -ForegroundColor Green
Write-Host ("verdict            : {0}" -f $verdict)
Write-Host ("RTO(체결 재개)     : {0} s (목표 < {1})" -f $rtoDisplay, $rtoTarget)
Write-Host ("epoch              : {0} -> {1}" -f $oldEpoch, $newEpoch)
Write-Host ("자산합 위반(C01)   : {0}" -f $assetSum)
Write-Host ("중복 접수(C07/C07')  : {0}" -f $dupOrder)
Write-Host ("중복 체결(C08)     : {0}" -f $dupFill)
Write-Host ("저널 gap(C05/C06)  : {0} / {1}" -f $cmdGap, $evtGap)
Write-Host ("주문 유실(C13a)    : {0}" -f $ordersLost)
Write-Host ("RPO records        : {0}" -f $rpoRecords)
Write-Host ("failover_run_id    : {0}" -f $rec.failover_run_id)
Write-Host '────────────────────────────────────────'
# 한정 표기: 컨테이너 E1의 RPO 판정은 저널 무결(C05·C06·C13a) 프록시다. C16(ACK 원장 대조)과
# C17(재생 결정론)은 봇 ACK COPY·스냅샷 재생 지문 배선이 M4 이월이라 이 스택에서 빈 테이블
# 대조로 공허 통과한다. C16 실데이터 교차대조는 CI InvariantVerifierIntegrationTest가,
# 검증기 반증(위반 주입 검출)은 동 테스트 verifierCatchesInjectedViolations가 담당한다.
$c16rows = Get-Scalar "SELECT count(*) FROM harness.loadtest_acks"
$c17rows = Get-Scalar "SELECT count(*) FROM recovery_runs"
Write-Host ''
Write-Host '증거 한정(컨테이너 E1):' -ForegroundColor Yellow
Write-Host ("  C16 loadtest_acks 행수 : {0} ({1})" -f $c16rows, $(if ([decimal]$c16rows -eq 0) { '공허 통과, ACK 파이프라인 M4 이월' } else { '실데이터 대조' }))
Write-Host ("  C17 recovery_runs 행수 : {0} ({1})" -f $c17rows, $(if ([decimal]$c17rows -eq 0) { '공허 통과, 재생 지문 M4 이월' } else { '실데이터 대조' }))
Write-Host '  RPO=0 판정 근거는 C05·C06·C13a 저널 무결 프록시. C16 실증은 CI 통합테스트 참조.'
Write-Host ''
Write-Host 'harness.failover_runs 최근 행:'
Invoke-Psql "SELECT id, experiment_id, old_epoch, new_epoch, rto_seconds, rpo_records, verdict FROM harness.failover_runs ORDER BY id DESC LIMIT 3"
Write-Host ''
Write-Host "invariant_checks 사후 run 위반 요약(run_id=$runId):"
Invoke-Psql "SELECT check_name, ok, violated_rows FROM invariant_checks WHERE details->>'run_id'='$runId' AND details->>'window'='post' ORDER BY ok, check_name"

if ($verdict -ne 'PASS') {
    Write-Host ''
    Write-Host 'E1 FAIL. 위 증거로 원인 귀속 후 재실행.' -ForegroundColor Red
    exit 1
}
Write-Host ''
Write-Host 'E1 PASS.' -ForegroundColor Green
