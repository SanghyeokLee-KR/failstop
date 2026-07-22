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
}
finally {
    # 종료 훅: 토식 무조건 해제(§3 공통 전제).
    Write-Section '종료 훅: 토식 해제'
    Remove-AllToxics -Proxy $proxy
}

if ($verdict -ne 'PASS') { exit 1 }
Write-Host 'E2 PASS.' -ForegroundColor Green
