# docs §3 E3 Redis 장애. 전제: 리더십 권위=DB leader_lease(§6.6), Redisson은 후보 조율 보조(부록 A #6).
# 현 격리 스택(docker-compose.chaos.yml)에는 chaos-redis 서비스도 redis 토식 프록시도 없다. 부재 시
# 스크립트는 SKIP을 내고, DB-권위 리더십이 Redis 없이 성립함(=E3 전제의 실증)을 증거로 남긴다.
# compose에 redis를 무단 추가하지 않는다(최소 개입). 라이브 실행에 필요한 배선은 verdict에 보고한다.
# 파일명 유의: 레거시 e3-spike-failover.ps1(=docs E6)과 별개다. docs 번호로 명명.
param([string] $ConfigPath = (Join-Path $PSScriptRoot '..\config\e3-redis-outage.env'))

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
$cfg = Read-EnvFile $ConfigPath
$runId = "E3-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
$redis = $cfg['REDIS_CONTAINER']
$redisProxy = $cfg['REDIS_PROXY']

function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades") }

Write-Section "E3(Redis 장애) 시작 runId=$runId redis=$redis"

# ── 전제 검사: redis 토폴로지 존재 여부 ────────────────────────────────
$redisPresent = Test-ContainerExists $redis
$redisProxyPresent = Test-ToxiproxyProxy $redisProxy

if (-not $redisPresent) {
    Write-Section 'SKIP: Redis 토폴로지 부재'
    # E3 전제(DB-권위 리더십)의 실증: Redis 없이도 leader_lease가 리더를 배타 선출·유지한다.
    $prePort = Get-LiveNodePort
    $s0 = Get-HarnessStatus -Port $prePort
    $leaseHolder = Invoke-Psql "SELECT holder FROM leader_lease WHERE resource='matching-engine'"
    $leaseAlive = Invoke-Psql "SELECT lease_until > now() FROM leader_lease WHERE resource='matching-engine'"
    $tradesNow = Get-TradeCount
    # Redis 부재로도 무결성이 성립하는지 권위 run 1회.
    $run = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'skip'
    $reason = "compose에 $redis 서비스가 없다. redis 토폴로지(서비스 + toxiproxy $redisProxy 프록시 + Redisson 배선)가 있어야 라이브 실행 가능."

    Write-Verdict -ExperimentId 'E3' -Verdict 'SKIP' -Metrics @{
        reason              = $reason
        redisContainer      = $redis
        redisPresent        = $redisPresent
        redisProxyPresent   = $redisProxyPresent
        # 전제 실증: DB-권위 리더십이 Redis 없이 성립.
        dbAuthorityLeaseHolder = $leaseHolder
        dbAuthorityLeaseAlive  = $leaseAlive
        harnessEpoch        = [long]$s0.epoch
        harnessLeaderAlive  = [bool]$s0.leaderAlive
        integrityCriticalClean = [bool]$run.criticalClean
        integrityViolations = [long]$run.totalViolations
        tradesObserved      = $tradesNow
        requiredForLive     = @('chaos-redis service (docker-compose.chaos.yml)',
                                'redis toxiproxy proxy (toxiproxy/populate.json)',
                                'Redisson auxiliary wiring (app)')
    }
    Write-Host ''
    Write-Host 'E3 SKIP: Redis 부재. DB-권위 리더십 전제는 위 증거로 성립(무결성 criticalClean).' -ForegroundColor Yellow
    Write-Host '라이브 실행 대기: compose에 redis 추가 필요(무단 추가 금지, 보고만).'
    exit 0
}

# ── 라이브 경로(redis 존재 시): kill -> 무결성/무-split-brain/복귀 판정 ────
# 현 스택에서는 위 SKIP으로 빠져 도달하지 않는다. redis 추가 후 재현용.
Write-Section '라이브 경로: redis 존재'
try {
    Start-Sleep -Seconds ([int]$cfg['WARMUP_SEC'])
    $ok = Wait-For { (Get-TradeCount) -ge [long]$cfg['MIN_TRADES_BEFORE_INJECT'] } -TimeoutSec 120 -PollSec 1
    if (-not $ok) { throw '체결 미달, 봇 상태 확인' }

    Write-Section 'ⓐ 사전 권위 run'
    $prePort = Get-LiveNodePort
    $s0 = Get-HarnessStatus -Port $prePort
    $oldEpoch = [long]$s0.epoch
    $pre = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'pre'
    Write-Host "사전 verdict=$($pre.verdict) old_epoch=$oldEpoch"

    Write-Section "ⓑ 주입: docker kill $redis (restart 정책 재기동)"
    $t0 = (Get-Date).ToUniversalTime()
    & docker kill $redis | Out-Null
    Write-Host "T0=$($t0.ToString('o'))"

    # redis 죽는 동안 무결성 유지(온디맨드 권위 run) 관측.
    Write-Section 'ⓒ Redis 부재 중 무결성 관측'
    $injRun = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'inject'
    $injScans = Get-Scalar "SELECT count(*) FROM invariant_checks WHERE details->>'run_id'='$runId' AND details->>'window'='inject'"
    if ($injScans -lt 1) { throw 'inject 창 내 권위 run 미적재(공허 통과 방지 가드)' }

    # redis 복귀 대기.
    $recovered = Wait-For { Test-ContainerRunning $redis } -TimeoutSec ([int]$cfg['RECOVERY_SEC']) -PollSec 1
    Start-Sleep -Seconds ([int]$cfg['POST_OBSERVE_SEC'])

    Write-Section 'ⓓ 사후 권위 run'
    $postPort = Get-LiveNodePort
    $post = Invoke-InvariantRun -Port $postPort -RunId $runId -Window 'post'
    $s1 = Get-HarnessStatus -Port $postPort
    $newEpoch = [long]$s1.epoch
    # split-brain 0: DB-권위 모델이라 redis 장애로 epoch가 변하면 안 된다(승격은 리스 만료로만).
    $noSplitBrain = ($newEpoch -eq $oldEpoch)

    $injClean = Test-ExperimentIntegrity $injRun
    $postClean = Test-ExperimentIntegrity $post
    $verdict = 'FAIL'
    if ($injClean -and $postClean -and $noSplitBrain -and $recovered) { $verdict = 'PASS' }

    Write-Verdict -ExperimentId 'E3' -Verdict $verdict -Metrics @{
        epochBefore       = $oldEpoch
        epochAfter        = $newEpoch
        noSplitBrain      = $noSplitBrain
        redisRecovered    = [bool]$recovered
        injectAlwaysClean = $injClean
        postAlwaysClean   = $postClean
    }
}
finally {
    Write-Section '종료 훅: redis 상태 확인'
    Write-Host "redis running: $(Test-ContainerRunning $redis)"
}

if ($verdict -ne 'PASS') { Write-Host 'E3 FAIL.' -ForegroundColor Red; exit 1 }
Write-Host 'E3 PASS.' -ForegroundColor Green
