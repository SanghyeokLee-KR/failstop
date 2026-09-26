# E6 폭주 + 리더 kill 복합(최종 증명, 검증설계 §3). 스파이크 plateau 중 리더를 kill한다.
# 판정은 E4 판정 + 스파이크 중 불변식 유지다. 런타임 OPS 변경 API가 없어 봇 노드를 높은
# target-ops로 재기동해 스파이크를 재현한다. 정본 개방루프 스파이크는 order_flow.js 소관이다.
# -Resource 는 볼 리스 자원이다(S4 설계 4-18, S7 설계 4-13). P1·P2 는 matching-engine 이고 P3(파티션 리더 모드)에서는 필수다.
# 모든 상태·포트 조회와 리더 감지, 페일오버 공용 경로(Complete-FailoverExperiment)가 그 자원을 쓴다.
param([string] $ConfigPath = (Join-Path $PSScriptRoot '..\config\e6.env'), [string] $Resource)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
$cfg = Read-EnvFile $ConfigPath
$leaseResource = Get-LeaseResource -Resource $Resource
$runId = "E6-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
$target = $cfg['TARGET_NODE']

function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades" -Required) }

Write-Section "E6 시작 runId=$runId resource=$leaseResource"
# 기저 부하를 명시로 세운다. 앞선 실험이 남긴 값을 이월받으면 스파이크 배수가 실행마다
# 달라져 §2-4 캘리브레이션 전제가 깨진다.
if ($cfg['BASE_OPS']) { Set-BotOps ([int]$cfg['BASE_OPS']) }
Start-Sleep -Seconds ([int]$cfg['WARMUP_SEC'])

# 실 리더를 kill 대상 삼는다. config 기본값(chaos-api-1)은 갓 세운 스택에서만 맞고, 앞선 실험이
# 리더를 api-2로 남겼으면 팔로워를 죽이게 된다. 그때는 승격이 없어 epoch 불변·RTO n/a로 확정
# FAIL이 나는데 그 FAIL은 코드 결함이 아니라 판정 결함이다(실측: E2 직후 E6가 이 경로로 FAIL).
$detected = Get-LeaderContainer -Resource $leaseResource
if (-not $detected) {
    throw '리더 감지 실패. 실 리더를 특정하지 못한 채 kill하면 판정이 무의미하다. leader_lease holder와 컨테이너 로그를 대조해 확인한 뒤 재실행한다.'
}
$target = $detected
Write-Host "리더 감지: $target (config 기본과 무관하게 실 리더 대상)"
$standbyPort = if ($detected -eq 'chaos-api-1') { 28080 } else { 18080 }

Write-Section 'ⓐ 사전 권위 run'
$prePort = Get-LiveNodePort -Resource $leaseResource
Reset-LoadtestAcks -Port $prePort
$s0 = Get-HarnessStatus -Port $prePort -Resource $leaseResource
$oldEpoch = [long]$s0.epoch; $oldHolder = $s0.holder
$leaseUntilBefore = ([datetimeoffset]$s0.leaseUntil).UtcDateTime
$pre = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'pre'
Write-Host "사전 verdict=$($pre.verdict) old_epoch=$oldEpoch"

# 스파이크 적용부터 복원까지를 try로 감싼다. 조기 종료 경로(시계가드 SKIP·스캔 미적재 throw)로
# 나가면 봇이 SPIKE_OPS 400으로 남고, 자기 부하를 세우지 않는 E3·E5·E7·E8·E9가 그 폭주 부하를
# 이월받아 §2-4 캘리브레이션 전제가 깨진다. 정상 경로는 Complete-FailoverExperiment가 이미
# BASE_OPS로 되돌리므로 플래그로 이중 재기동을 막는다.
$spikeApplied = $false
try {
    Write-Section "스파이크: 봇 OPS $($cfg['BASE_OPS']) -> $($cfg['SPIKE_OPS']) (봇 노드 재기동)"
    # 인라인 compose 호출은 재기동된 컨테이너가 상속한 출력 핸들 때문에 파이프가 EOF에 닿지 않아
    # -d인데도 반환하지 않고 매달린다(실측). 같은 우회를 담은 공용 Set-BotOps를 쓴다.
    Set-BotOps ([int]$cfg['SPIKE_OPS']) -SettleSec 0
    $spikeApplied = $true
    Start-Sleep -Seconds ([int]$cfg['KILL_AFTER_SPIKE_SEC'])

    # 스파이크 중 불변식 유지 확인. 상시 스캔(fixedDelay 5분)에만 의존하면 60s plateau 창에
    # 스캔이 0회일 수 있어 위반 0이 무결이 아니라 미실행을 뜻할 수 있다. plateau 중 온디맨드
    # 권위 run을 강제 호출해 창 내 실제 스캔 1회를 보장하고, 그 run의 위반 0을 판정 근거로 삼는다.
    Write-Section '스파이크 중 불변식 관측(plateau 온디맨드 권위 run)'
    $spikePort = Get-LiveNodePort -Resource $leaseResource
    $spikeRun = Invoke-InvariantRun -Port $spikePort -RunId $runId -Window 'spike'
    # 스파이크 창은 부하 중 측정이 목적이라 드레인할 수 없다. C13a·C19는 드레인 전제라 부하
    # 중에는 in-flight를 위반으로 세고, C16은 스테일 loadtest_acks 오탐이 배제 사유다.
    # 제외 술어는 lib.ps1 공용 상수 하나를 쓴다. 인라인 사본은 WARN 제외를 어긋나게 한다.
    $spikeClean = Test-ExperimentIntegrity $spikeRun
    $spikeViol = Get-GatedViolationSum $spikeRun
    # 창 내 실제 스캔 존재 증거: invariant_checks에 window='spike' 적재 확인(공허 통과 차단).
    $spikeScans = Get-Scalar "SELECT count(*) FROM invariant_checks WHERE details->>'run_id'='$runId' AND details->>'window'='spike'" -Required
    Write-Host "스파이크 창 권위 run verdict=$($spikeRun.verdict) 위반합=$spikeViol 스캔체크수=$spikeScans"
    if ($spikeScans -lt 1) { throw '스파이크 창 내 권위 run 미적재(공허 통과 방지 가드)' }

    # 시계 동기 전제조건(#58): 스큐면 주입 없이 SKIP(거짓 FAIL 방지). 스파이크는 이미 형성됨.
    if (Test-FailoverClockGuard -ExperimentId 'E6' -RunId $runId) { exit 0 }

    Write-Section 'ⓑ 주입: docker kill (스파이크 plateau 중)'
    # T1(마지막 heartbeat) 역산 기준을 주입 직전 값으로 다시 잡는다(E5·E7과 동형). ⓐ 시점
    # 스냅샷을 쓰면 그 사이의 갱신분이 그대로 다운타임에 더해진다. E6은 ⓐ와 kill 사이에
    # 스파이크 적용 + KILL_AFTER_SPIKE_SEC(30s) + 스파이크 권위 run + 시계가드가 들어가 그
    # 편향이 가장 크다(실측: RTO 0.086s인 run이 downtime 38.777s로 보고됐다).
    $leaseUntilBefore = ([datetimeoffset](Get-HarnessStatus -Port $prePort -Resource $leaseResource).leaseUntil).UtcDateTime
    $t0 = (Get-Date).ToUniversalTime()
    $tradesAtInject = Get-TradeCount
    & docker kill $target | Out-Null
    Write-Host "T0=$($t0.ToUniversalTime().ToString('o')) trades_at_inject=$tradesAtInject"

    $verdict = Complete-FailoverExperiment -ExperimentId 'E6' -RunId $runId `
        -OldEpoch $oldEpoch -OldHolder $oldHolder -LeaseUntilBefore $leaseUntilBefore `
        -TradesAtInject $tradesAtInject -T0 $t0 `
        -ObserveSec ([int]$cfg['POST_KILL_OBSERVE_SEC']) -RtoTarget ([int]$cfg['RTO_TARGET_SEC']) `
        -StandbyPort $standbyPort -RestoreOps ([int]$cfg['BASE_OPS']) -Resource $leaseResource
    # Complete-FailoverExperiment가 이미 BASE_OPS로 되돌렸다. 이중 재기동 방지.
    $spikeApplied = $false
}
finally {
    if ($spikeApplied -and $cfg['BASE_OPS']) {
        # 복원 실패가 판정·종료코드를 덮지 않게 한다(Set-BotOps는 throw한다).
        try { Set-BotOps ([int]$cfg['BASE_OPS']) -SettleSec 0 }
        catch { Write-Host "경고: 스파이크 부하 복원 실패($($_.Exception.Message)). 다음 실험 전 CHAOS_BOTS_OPS 확인" -ForegroundColor Yellow }
    }
}

Write-Host "스파이크 중 불변식 위반 합(게이팅 대상, WARN·조건부 스코프 체크 제외): $spikeViol (0 + 창 내 스캔 $spikeScans 회여야 폭주 중 무결 성립)"
# 게이트는 $spikeClean 단독이다. $spikeViol은 같은 면제 술어에서 나온 표시값이라 조건으로 더해도
# 정상 페이로드에서는 판정이 바뀌지 않는다. 빈 응답만 두 값이 갈리는데 그 경로는 $spikeClean이
# 공허 가드로 이미 거짓을 낸다(lib.ps1 Test-ExperimentIntegrity).
# 스파이크 창 판정이 failover_runs 적재 뒤에 붙으므로 파일에는 둘을 합친 값을 쓴다.
$finalVerdict = if ($verdict -eq 'PASS' -and $spikeClean) { 'PASS' } else { 'FAIL' }
Save-VerdictFile -ExperimentId 'E6' -RunId $runId -Verdict $finalVerdict -Metrics @{
    failoverVerdict = $verdict; spikeClean = $spikeClean
    spikeViolations = $spikeViol; spikeScans = $spikeScans; resource = $leaseResource
}
if ($finalVerdict -ne 'PASS') { exit 1 }
Write-Host 'E6 PASS.' -ForegroundColor Green
# 명시 종료코드. 앞선 네이티브 호출($LASTEXITCODE)이 프로세스 종료코드로 새면 PASS가 exit 1이 된다.
exit 0
