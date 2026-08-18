# E4 매칭 리더 kill(핵심 데모, 검증설계 §3 정본 번호. 구 e1-leader-kill, M4 §0-3 개명).
# 실험 판정 프로토콜(사전 권위 run → 주입 → 무개입 관찰 → 사후 권위 run).
# 판정: 유실 0 · 중복 0 · 자산합 0 · epoch 정확히 +1 · RTO < 30s.
# 개입 금지 구간(kill 이후)에서 스크립트는 관찰·기록만 한다.
#
# 실행 전제: 실험 사이에 down -v + up으로 스택을 새로 세운다. compose가 api-2 기동을 api-1
# readiness에 종속시키므로 신선한 스택의 리더는 결정적으로 api-1이다. 전제를 생략하면 api-1이
# restart: unless-stopped라 kill한 노드가 팔로워로 부활하고, 그러면 다음 실행의 리더는 api-2다.
# 그때 config TARGET_NODE 기본값(chaos-api-1)을 그대로 믿으면 팔로워를 죽이게 되므로 아래에서
# 실 리더를 감지해 덮어쓴다. E5·E7이 남긴 종신 강등도 프로세스 내 플래그라 재기동 전까지
# 승격 후보로 돌아오지 않는다(E5 헤더와 같은 규약).
param([string] $ConfigPath = (Join-Path $PSScriptRoot '..\config\e4.env'))

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
$cfg = Read-EnvFile $ConfigPath

$runId = "E4-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
$target = $cfg['TARGET_NODE']
$rtoTarget = [int] $cfg['RTO_TARGET_SEC']

function Get-CheckRows { param($Run, [string] $Name)
    ($Run.checks | Where-Object { $_.checkName -eq $Name } | Select-Object -First 1).violatedRows
}
function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades" -Required) }

Write-Section "E4 시작 runId=$runId"

# ── 워밍업: 정상 부하로 체결 축적 ──────────────────────────────────────
Write-Section 'warmup'
# 자기 부하를 명시로 세운다. 생략하면 앞선 실험이 남긴 ops를 이월받는다(E6 직후면 400, 드레인
# 직후면 정지 상태). 부하가 무엇인지 모르는 채 kill하면 판정 조건이 실행마다 달라진다.
if ($cfg['BASE_OPS']) { Set-BotOps ([int]$cfg['BASE_OPS']) }
Start-Sleep -Seconds ([int]$cfg['WARMUP_SEC'])
$minTrades = [long]$cfg['MIN_TRADES_BEFORE_KILL']
# 누적이 아니라 증분으로 본다. 누적이면 앞선 실험이 남긴 체결만으로 즉시 통과해, 봇이 죽었거나
# 정지된 상태에서도 kill한다. 부하 없이 죽이면 승격 후 재개를 관측할 수 없고 그 FAIL은 코드
# 결함이 아니라 판정 결함이다(E5와 같은 술어).
$tradesBase = Get-TradeCount
$ok = Wait-For { (Get-TradeCount) - $tradesBase -ge $minTrades } -TimeoutSec 120 -PollSec 1
if (-not $ok) { throw "체결 증분 $minTrades 건 미달. 봇 부하가 실리지 않았다. chaos-bots 상태와 BASE_OPS를 확인한다." }
Write-Host "체결 증분: $((Get-TradeCount) - $tradesBase) (누적 $(Get-TradeCount))"

# ── 리더 감지: 실 리더를 kill 대상 삼는다 ──────────────────────────────
# config 기본값은 갓 세운 스택에서만 맞다. 앞선 실험이 리더를 api-2로 남겼으면 팔로워를 죽이게
# 되고, 그때는 승격이 없어 epoch 불변·RTO n/a로 확정 FAIL이 난다. 그 FAIL은 코드 결함이 아니라
# 판정 결함이다. Get-LeaderContainer는 다중 매치를 모호로 보고 $null을 돌려주므로 감지 실패는
# 강행하지 않고 멈춘다(E5·E6와 동형).
$detected = Get-LeaderContainer
if (-not $detected) {
    throw '리더 감지 실패. 실 리더를 특정하지 못한 채 kill하면 판정이 무의미하다. leader_lease holder와 컨테이너 로그를 대조해 확인한 뒤 재실행한다.'
}
$target = $detected
# 승격 관찰처는 감지된 리더의 반대 노드다. api-2로 고정하면 리더가 api-2일 때 방금 죽인 노드를
# 폴링하게 되어 승격을 영영 못 본다.
$standbyPort = if ($detected -eq 'chaos-api-1') { $Global:CHAOS.Api2Port } else { $Global:CHAOS.Api1Port }
Write-Host "리더 감지: $target (config TARGET_NODE와 무관하게 실 리더 대상). standby 포트=$standbyPort"

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

# ── 시계 동기 전제조건(#58): 스큐면 주입 없이 SKIP(거짓 FAIL 방지) ─────────
if (Test-FailoverClockGuard -ExperimentId 'E4') { exit 0 }

# ── 주입 직전 대상 재확인 ──────────────────────────────────────────────
# 감지(워밍업 직후)와 주입 사이에 사전 권위 run(최대 60s)·시계가드가 들어간다. 카오스 리스 TTL이
# 6s라 그 창에서 리더가 바뀔 수 있고, 그러면 감지값이 다시 팔로워를 가리켜 이번 실행이 막으려던
# 위양성이 그대로 재현된다.
$reDetected = Get-LeaderContainer
if ($reDetected -ne $target) {
    $now = if ($reDetected) { $reDetected } else { '판별 불가' }
    throw "주입 직전 리더 교체 감지(감지 $target -> 현재 $now). 대상이 팔로워가 되므로 주입하지 않는다."
}
# 스탠바이 도달성 전제. 승격 후보가 없는 상태에서 리더를 죽이면 두 노드가 모두 죽고, 관찰 루프
# 다음의 $statusAfter 조회가 예외로 터져 verdict·failover_runs 적재·증거 출력이 통째로 사라진다.
try { Get-HarnessStatus -Port $standbyPort | Out-Null }
catch { throw "스탠바이(포트 $standbyPort) 미응답. 승격 후보 없이 kill하면 클러스터가 전멸하고 증거도 남지 않는다. down -v + up으로 스택을 새로 세운 뒤 재실행한다." }

# ── ⓑ 주입: 리더 kill(T0) ──────────────────────────────────────────────
Write-Section 'ⓑ 주입: docker kill'
# T1(마지막 heartbeat) 역산 기준을 주입 직전 값으로 다시 잡는다(E5·E7과 동형). ⓐ 시점
# 스냅샷을 쓰면 사전 권위 run·시계가드·리더 재확인이 도는 동안의 갱신분이 그대로
# 다운타임에 더해져 과대 산출된다.
$leaseUntilBefore = ([datetimeoffset](Get-HarnessStatus -Port $prePort).leaseUntil).UtcDateTime
$t0 = (Get-Date).ToUniversalTime()
$tradesAtKill = Get-TradeCount
& docker kill $target | Out-Null
Write-Host "T0=$($t0.ToUniversalTime().ToString('o')) killed=$target trades_at_kill=$tradesAtKill"

# ── ⓒ 무개입 관찰: 승격(epoch+1) → 체결 재개 ───────────────────────────
Write-Section 'ⓒ 관찰(개입 금지)'
$t2 = Wait-For {
    try { $s = Get-HarnessStatus -Port $standbyPort; ($s.epoch -gt $oldEpoch) -and $s.leaderAlive }
    catch { $false }
} -TimeoutSec ([int]$cfg['POST_KILL_OBSERVE_SEC']) -PollSec 0.5
if (-not $t2) { Write-Host '경고: 승격 미관찰(타임아웃)' -ForegroundColor Yellow }
$statusAfter = Get-HarnessStatus -Port $standbyPort
$newEpoch = [long]$statusAfter.epoch
$newHolder = $statusAfter.holder
Write-Host "T2(승격)=$(if($t2){$t2.ToUniversalTime().ToString('o')}else{'n/a'}) new_epoch=$newEpoch"

# T5 = 페일오버 후 최초 신규 TRADE의 engine_ts(§5-1 정의). created_at은 엔진 처리 시각이라
# catch-up 배치의 커밋 가시화 지연과 무관한 재개 시점을 가리킨다. 술어는 lib.ps1 공용
# Get-FirstTradeSql(정본 §5-1 검산식, command_journal.fencing_token 축)을 쓴다. 종전 인라인
# 사본은 event_journal.fencing_epoch를 관측 epoch와 등가 비교해 두 가지로 틀렸다. 승격 재생
# 산출물을 재개로 오인해 RTO를 과소 보고했고(그 축의 결함), 승격 재생 반납으로 epoch가 더 오르면
# 재개를 아예 놓쳐 RTO n/a가 됐다(등가 비교의 결함).
$t0Iso = $t0.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fff') + 'Z'
$firstTradeSql = Get-FirstTradeSql -OldEpoch $oldEpoch -T0Iso $t0Iso
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

# ── ⓓ 드레인 + 사후 권위 run + 측정 ────────────────────────────────────
Write-Section 'ⓓ 드레인 + 사후 권위 run'
# 종전에는 3초만 쉬고 쟀다. E4 판정은 $post.criticalClean을 쓰는데 C13a가 CRITICAL이라, 부하가
# 도는 상태에서 재면 in-flight가 유실로 계상돼 위양성 FAIL이 난다(E2·E6에서 실측된 것과 같은
# 기제). 봇 쓰기를 끊고 물질화가 끝난 뒤 잰다. try 범위는 사후 run까지만 두고 이후 기록·출력은
# 봇이 돌아온 뒤에 한다(마지막 네이티브 명령이 docker start가 되면 종료코드가 샌다).
$botsStopped = Stop-BotLoad
$drained = $false
try {
    $requiredStreak = if ($botsStopped) { 1 } else { 2 }
    $drained = Wait-DrainZero -Sql $Global:CHAOS_DRAIN_SQL -TimeoutSec 90 -RequiredStreak $requiredStreak
    if (-not $drained) {
        $residual = Get-Scalar $Global:CHAOS_DRAIN_SQL
        $residualDisp = 'n/a(psql 무응답)'; if ($null -ne $residual) { $residualDisp = $residual }
        Write-Host "경고: 드레인 미완(잔여 $residualDisp 건). C13a가 in-flight를 포함할 수 있다" -ForegroundColor Yellow
    }
    $postPort = Get-LiveNodePort
    $post = Invoke-InvariantRun -Port $postPort -RunId $runId -Window 'post'
}
finally {
    $restoreOps = 0; if ($cfg['BASE_OPS']) { $restoreOps = [int]$cfg['BASE_OPS'] }
    Restore-BotLoad -WasStopped $botsStopped -Ops $restoreOps
}

$assetSum = Get-CheckRows $post 'C01_ASSET_CONSERVATION'
$cmdGap   = Get-CheckRows $post 'C05_CMD_GAPLESS'
$evtGap   = Get-CheckRows $post 'C06_EVT_GAPLESS'
$dupOrder = [long](Get-CheckRows $post 'C07_DUP_ORDER') + [long](Get-CheckRows $post 'C07P_DUP_ORDER_JOURNAL')
$dupFill  = Get-CheckRows $post 'C08_DUP_FILL'
$ordersLost = Get-CheckRows $post 'C13A_ORDER_MATERIALIZED'
# 컨테이너 E4의 RPO는 저널 무결(gapless + 물질화)로 판정. C16 외부 ACK 파이프라인은 미구축.
$rpoRecords = [long]$cmdGap + [long]$evtGap + [long]$ordersLost

$rto = $null; if ($t5) { $rto = ($t5 - $t0).TotalSeconds }
# §5-1 downtime=T2-T1, T1=구 lease_until - 리스 TTL(마지막 heartbeat 역산). lease_until을 T1로
# 직접 쓰면 한 TTL 과소 보고라 TTL을 더한다. lib.ps1 공용 경로(E2·E6)와 동일 공식.
$downtime = $null; if ($t2) { $downtime = ($t2 - $leaseUntilBefore).TotalSeconds + (Get-LeaseTtlSec) }
$rtoRounded = $null; if ($null -ne $rto) { $rtoRounded = [math]::Round($rto, 3) }
$downtimeRounded = $null; if ($null -ne $downtime) { $downtimeRounded = [math]::Round($downtime, 3) }
$firstTradeVal = $null; if ($firstTradeTs) { $firstTradeVal = $firstTradeTs }
$rtoDisplay = 'n/a'; if ($null -ne $rtoRounded) { $rtoDisplay = $rtoRounded }

# 드레인 성립 여부는 증거 품질 표기이지 판정 완화 사유가 아니다. Wait-DrainZero의 술어가 C13a와
# 같은 anti-join이라 "드레인 실패"는 곧 "C13a 위반"이고, 그때 C13a를 게이팅에서 빼면 진짜 주문
# 유실이 PASS로 뒤집힌다. 정본 PASS 식은 CRITICAL 전량 0행 ∧ rpo_records=0이다(§5-3 R7).
$drainValid = $botsStopped -and $drained
if (-not $drainValid) {
    Write-Host "경고: 드레인 미성립(정지=$botsStopped, 드레인=$drained). C13a=$ordersLost의 증거 품질이 낮다" -ForegroundColor Yellow
}
# 정지 실패 경로 최소 방어(lib 공용 경로와 동형). run 직후 같은 술어를 다시 재서 표본이 in-flight를
# 물었는지 가른다. 판정은 건드리지 않고 원인 귀속 근거로만 남긴다.
if (-not $botsStopped) {
    # 여기서 throw하면 아래 판정·기록·증거 출력이 통째로 사라진다. 재측정은 원인 귀속 보조라
    # 무응답을 별도로 알리고 넘어간다. 무응답을 0으로 읽어 "불일치 없음"으로 보고하지는 않는다.
    $c13aRecheckRaw = Get-Scalar $Global:CHAOS_DRAIN_SQL
    if ($null -eq $c13aRecheckRaw) {
        Write-Host '경고: C13a 재측정 실패(psql 무응답). run 표본이 in-flight를 물었는지 가르지 못했다' -ForegroundColor Yellow
    } elseif ([long]$c13aRecheckRaw -ne [long]$ordersLost) {
        Write-Host "경고: C13a 재측정 불일치(run $ordersLost -> 직후 $c13aRecheckRaw). 부하 중 표본이라 RPO 증거로 쓸 수 없다. 봇 정지 원인 해소 후 재실행" -ForegroundColor Yellow
    }
}

$verdict = 'FAIL'
if ($post.criticalClean -and $t5 -and ($newEpoch -eq $oldEpoch + 1) -and ($null -ne $rto) -and ($rto -lt $rtoTarget)) {
    $verdict = 'PASS'
}

# ── ⓔ 기록 + 증거 출력 ─────────────────────────────────────────────────
Write-Section 'ⓔ 판정·기록'
$tradesAfter = Get-TradeCount
$body = @{
    experimentId = 'E4'; oldLeader = $oldHolder; newLeader = $newHolder
    oldEpoch = $oldEpoch; newEpoch = $newEpoch
    firstTradeTs = $firstTradeVal
    rtoSeconds = $rtoRounded
    downtimeSeconds = $downtimeRounded
    rpoRecords = $rpoRecords; verdict = $verdict
    details = @{ runId = $runId; assetSum = "$assetSum"; dupOrder = "$dupOrder"; dupFill = "$dupFill";
                 cmdGap = "$cmdGap"; evtGap = "$evtGap"; tradesBefore = "$tradesBefore";
                 tradesAfter = "$tradesAfter"; drainValid = "$drainValid" }
}
$rec = Record-Failover -Port $postPort -Body $body

Write-Host ''
Write-Host '── E4 증거 ─────────────────────────────' -ForegroundColor Green
Write-Host ("verdict            : {0}" -f $verdict)
Write-Host ("RTO(체결 재개)     : {0} s (목표 < {1})" -f $rtoDisplay, $rtoTarget)
Write-Host ("epoch              : {0} -> {1}" -f $oldEpoch, $newEpoch)
Write-Host ("자산합 위반(C01)   : {0}" -f $assetSum)
Write-Host ("중복 접수(C07/C07')  : {0}" -f $dupOrder)
Write-Host ("중복 체결(C08)     : {0}" -f $dupFill)
Write-Host ("저널 gap(C05/C06)  : {0} / {1}" -f $cmdGap, $evtGap)
Write-Host ("주문 유실(C13a)    : {0} (드레인 성립={1})" -f $ordersLost, $drainValid)
Write-Host ("RPO records        : {0}" -f $rpoRecords)
Write-Host ("failover_run_id    : {0}" -f $rec.failover_run_id)
Write-Host '────────────────────────────────────────'
# 한정 표기: 컨테이너 E4의 RPO 판정은 저널 무결(C05·C06·C13a) 프록시다. C16(ACK 원장 대조)과
# C17(재생 결정론)은 봇 ACK COPY·스냅샷 재생 지문 배선이 M4 이월이라 이 스택에서 빈 테이블
# 대조로 공허 통과한다. C16 실데이터 교차대조는 CI InvariantVerifierIntegrationTest가,
# 검증기 반증(위반 주입 검출)은 동 테스트 verifierCatchesInjectedViolations가 담당한다.
# 증거 출력 전용이라 -Required를 붙이지 않는다. 이 지점은 판정 확정·failover_runs 적재 이후이고
# try/finally 밖이라, 여기서 throw하면 verdict가 PASS인 run이 미포획 종료로 exit 1이 된다.
# 판정 역전을 증거 한 줄 때문에 만들지 않는다. 무응답은 값 대신 표기로 드러낸다.
$c16rows = Get-Scalar "SELECT count(*) FROM harness.loadtest_acks"
$c17rows = Get-Scalar "SELECT count(*) FROM recovery_runs"
$c16disp = 'n/a'; $c16note = 'psql 무응답, 판정 불능'
if ($null -ne $c16rows) {
    $c16disp = $c16rows
    $c16note = if ($c16rows -eq 0) { '공허 통과, ACK 파이프라인 M4 이월' } else { '실데이터 대조' }
}
$c17disp = 'n/a'; $c17note = 'psql 무응답, 판정 불능'
if ($null -ne $c17rows) {
    $c17disp = $c17rows
    $c17note = if ($c17rows -eq 0) { '공허 통과, 재생 지문 M4 이월' } else { '실데이터 대조' }
}
Write-Host ''
Write-Host '증거 한정(컨테이너 E4):' -ForegroundColor Yellow
Write-Host ("  C16 loadtest_acks 행수 : {0} ({1})" -f $c16disp, $c16note)
Write-Host ("  C17 recovery_runs 행수 : {0} ({1})" -f $c17disp, $c17note)
Write-Host '  RPO=0 판정 근거는 C05·C06·C13a 저널 무결 프록시. C16 실증은 CI 통합테스트 참조.'
Write-Host ''
Write-Host 'harness.failover_runs 최근 행:'
Invoke-Psql "SELECT id, experiment_id, old_epoch, new_epoch, rto_seconds, rpo_records, verdict FROM harness.failover_runs ORDER BY id DESC LIMIT 3"
Write-Host ''
Write-Host "invariant_checks 사후 run 위반 요약(run_id=$runId):"
Invoke-Psql "SELECT check_name, ok, violated_rows FROM invariant_checks WHERE details->>'run_id'='$runId' AND details->>'window'='post' ORDER BY ok, check_name"

if ($verdict -ne 'PASS') {
    Write-Host ''
    Write-Host 'E4 FAIL. 위 증거로 원인 귀속 후 재실행.' -ForegroundColor Red
    exit 1
}
Write-Host ''
Write-Host 'E4 PASS.' -ForegroundColor Green
# 명시 종료코드. 앞선 네이티브 호출($LASTEXITCODE)이 프로세스 종료코드로 새면 PASS가 exit 1이 된다.
exit 0
