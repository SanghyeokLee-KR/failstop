# E2 DB 단절. 리더의 pg 프록시에 blackhole 주입 → 리스 갱신 실패 → 강등 → failover → 복구.
# 판정: 유실 0(gapless+물질화) · 중복 0 · 자산합 0 · RTO < 30s. 종료 훅에서 토식 무조건 해제.
param([string] $ConfigPath = (Join-Path $PSScriptRoot '..\config\e2.env'))

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
$cfg = Read-EnvFile $ConfigPath
$runId = "E2-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
$proxy = $cfg['LEADER_PROXY']

function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades") }

Write-Section "E2 시작 runId=$runId proxy=$proxy"
try {
    Start-Sleep -Seconds ([int]$cfg['WARMUP_SEC'])
    $ok = Wait-For { (Get-TradeCount) -ge [long]$cfg['MIN_TRADES_BEFORE_INJECT'] } -TimeoutSec 120 -PollSec 1
    if (-not $ok) { throw '체결 미달, 봇 상태 확인' }

    Write-Section 'ⓐ 사전 권위 run'
    $prePort = Get-LiveNodePort
    Reset-LoadtestAcks -Port $prePort
    $s0 = Get-HarnessStatus -Port $prePort
    $oldEpoch = [long]$s0.epoch; $oldHolder = $s0.holder
    $leaseUntilBefore = ([datetimeoffset]$s0.leaseUntil).UtcDateTime
    $pre = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'pre'
    Write-Host "사전 verdict=$($pre.verdict) old_epoch=$oldEpoch"

    # 시계 동기 전제조건(#58): 스큐면 주입 없이 SKIP. 토식 미주입이라 종료 훅 해제는 무해.
    if (Test-FailoverClockGuard -ExperimentId 'E2') { exit 0 }

    Write-Section "ⓑ 주입: $($cfg['TOXIC_TYPE']) blackhole on $proxy"
    $t0 = (Get-Date).ToUniversalTime()
    $tradesAtInject = Get-TradeCount
    # timeout=0: 연결 유지·무응답(소켓/풀 타임아웃 검증). 리더 DB 경로만 차단(노드 축 선택성).
    Add-Toxic -Proxy $proxy -Name 'e2-cut' -Type $cfg['TOXIC_TYPE'] -Attributes @{ timeout = 0 }
    Write-Host "T0=$($t0.ToUniversalTime().ToString('o')) trades_at_inject=$tradesAtInject"

    $verdict = Complete-FailoverExperiment -ExperimentId 'E2' -RunId $runId `
        -OldEpoch $oldEpoch -OldHolder $oldHolder -LeaseUntilBefore $leaseUntilBefore `
        -TradesAtInject $tradesAtInject -T0 $t0 `
        -ObserveSec ([int]$cfg['POST_OBSERVE_SEC']) -RtoTarget ([int]$cfg['RTO_TARGET_SEC'])

    # 승격 안정성 게이트. E2 라이브에서 두 노드가 123ms 만에 모두 강등해 클러스터가 리더 없이
    # 영구 정지한 적이 있다(DB 설계 v2.23 §5.4). Complete-FailoverExperiment의 verdict는 승격
    # 시점만 보므로 그 직후 재강등을 잡지 못한다. 승격한 리더가 창 내내 리스를 유지하는지 본다.
    # 회복 가능 반납으로 epoch가 더 오르는 것은 정상이므로(같은 절) 단조 증가만 요구하고,
    # 리스가 죽어 있는 표본이 하나라도 있으면 실패로 본다(리더 없는 상태 = 이 실험이 막으려는 것).
    Write-Section 'ⓔ 승격 안정성 관측'
    $stableSec = 30; $stableGapSec = 2
    $leaseSql = "SELECT epoch||'|'||(lease_until > now()) FROM leader_lease WHERE resource='matching-engine'"
    $prevEpoch = $null; $stable = $true; $deadSample = 0; $regressed = 0
    for ($i = 0; $i -lt [int]($stableSec / $stableGapSec); $i++) {
        $raw = (Invoke-Psql $leaseSql).Split('|')
        $e = [long]$raw[0]; $alive = ($raw[1] -eq 't')
        if (-not $alive) { $deadSample++ }
        if ($null -ne $prevEpoch -and $e -lt $prevEpoch) { $regressed++ }
        $prevEpoch = $e
        Start-Sleep -Seconds $stableGapSec
    }
    $stable = ($deadSample -eq 0) -and ($regressed -eq 0) -and ($prevEpoch -ge $oldEpoch + 1)
    Write-Host ("승격 안정성: 리스 사망 표본 {0} / epoch 역행 {1} / 최종 epoch {2} -> {3}" -f `
        $deadSample, $regressed, $prevEpoch, $(if ($stable) { 'PASS' } else { 'FAIL' }))
    if (-not $stable) { $verdict = 'FAIL' }
}
finally {
    # 종료 훅: 토식 무조건 해제(§3 공통 전제).
    Write-Section '종료 훅: 토식 해제'
    Remove-AllToxics -Proxy $proxy
}

if ($verdict -ne 'PASS') { exit 1 }
Write-Host 'E2 PASS.' -ForegroundColor Green
