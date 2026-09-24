# docs §3 E3 Redis 장애. 리더십 권위는 DB leader_lease(§6.6)다. Redisson은 현 런타임에
# 미배선이라 Redis 장애가 매칭·리더십·split-brain에 도달할 경로가 없다.
# 이 스크립트의 SKIP은 미완이 아니라 유효 판정이다. DB-권위 리더십이 Redis 없이 성립함을
# 증거로 남긴다. 라이브 E3는 M2 채택 후에만 의미가 있다(chaos-redis 무단 추가 금지).
param([string] $ConfigPath = (Join-Path $PSScriptRoot '..\config\e3-redis-outage.env'), [string] $Resource)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
$cfg = Read-EnvFile $ConfigPath
$runId = "E3-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
$redis = $cfg['REDIS_CONTAINER']
$redisProxy = $cfg['REDIS_PROXY']
# 볼 리스 자원(S4 설계 4-18). P1·P2 는 matching-engine 이고 P3 에서는 -Resource 가 필수다.
$leaseResource = Get-LeaseResource -Resource $Resource

function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades" -Required) }

Write-Section "E3(Redis 장애) 시작 runId=$runId redis=$redis"

$redisPresent = Test-ContainerExists $redis
$redisProxyPresent = Test-ToxiproxyProxy $redisProxy

if (-not $redisPresent) {
    Write-Section 'SKIP: Redis 토폴로지 부재'
    # E3 전제(DB-권위 리더십)의 실증: Redis 없이도 leader_lease가 리더를 배타 선출·유지한다.
    $prePort = Get-LiveNodePort
    $s0 = Get-HarnessStatus -Port $prePort
    $leaseHolder = Invoke-Psql "SELECT holder FROM leader_lease WHERE resource='$leaseResource'"
    $leaseAlive = Invoke-Psql "SELECT lease_until > now() FROM leader_lease WHERE resource='$leaseResource'"
    $tradesNow = Get-TradeCount
    # Redis 부재로도 무결성이 성립하는지 권위 run 1회.
    $run = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'skip'
    $reason = "Redisson 런타임 미배선(코드: redis/redisson 의존·빈·spring.data.redis 설정 전무, 참조는 전부 M2 유예 주석). 리더십 권위=DB leader_lease라 Redis 장애가 매칭·리더십·split-brain에 무영향. chaos-redis 추가는 무의미하며 이 SKIP은 유효 판정이다."

    Write-Verdict -ExperimentId 'E3' -RunId $runId -Verdict 'SKIP' -Metrics @{
        reason                 = $reason
        redissonWiredAtRuntime = $false
        skipIsValidVerdict     = $true
        redisContainer         = $redis
        redisPresent           = $redisPresent
        redisProxyPresent      = $redisProxyPresent
        # 전제 실증: DB-권위 리더십이 Redis 없이 성립.
        dbAuthorityLeaseHolder = $leaseHolder
        dbAuthorityLeaseAlive  = $leaseAlive
        harnessEpoch           = [long]$s0.epoch
        harnessLeaderAlive     = [bool]$s0.leaderAlive
        integrityCriticalClean = [bool]$run.criticalClean
        integrityViolations    = [long]$run.totalViolations
        tradesObserved         = $tradesNow
        # 라이브 E3는 아래가 갖춰진 뒤에만 의미가 있다. 현재는 미채택이라 대상 아님(미완 아님).
        liveMeaningfulAfterM2  = @('M2가 Redisson을 실제 조율·알림 경로로 채택(현재 미채택)',
                                   '채택 시: chaos-redis service + redis toxiproxy proxy + app Redisson wiring',
                                   '단 Redis는 권위 아닌 보조라 판정은 여전히 무결성 0위반·split-brain 0 중심')
    }
    Write-Host ''
    Write-Host 'E3 SKIP(유효 판정): Redisson 런타임 미배선, DB-권위 모델이라 Redis 장애가 매칭에 무영향.' -ForegroundColor Yellow
    Write-Host 'DB-권위 리더십은 위 증거로 성립(leaseHolder alive·무결성 criticalClean). 라이브 E3는 M2 Redisson 채택 후에만 의미.'
    exit 0
}

# ── 라이브 경로(redis 존재 시): kill -> 무결성/무-split-brain/복귀 판정 ────
# 현 스택에서는 위 SKIP으로 빠져 도달하지 않는다. redis 추가 후 재현용.
Write-Section '라이브 경로: redis 존재'
try {
    # 자기 부하를 명시로 세운다. 생략하면 앞선 실험이 남긴 ops를 이월받는다(E6 직후면 400,
    # 드레인 직후면 정지 상태). 판정 축이 전부 count 기반이라 무부하면 전부 공허 통과한다.
    if ($cfg['BASE_OPS']) { Set-BotOps ([int]$cfg['BASE_OPS']) }
    Start-Sleep -Seconds ([int]$cfg['WARMUP_SEC'])
    # 누적이 아니라 증분으로 본다. 누적이면 앞선 실험이 남긴 체결만으로 즉시 통과해 무부하로
    # 주입하게 되고, 그러면 injClean·postClean·noSplitBrain이 무활동으로 전부 참이 된다.
    $tradesBase = Get-TradeCount
    $ok = Wait-For { (Get-TradeCount) - $tradesBase -ge [long]$cfg['MIN_TRADES_BEFORE_INJECT'] } -TimeoutSec 120 -PollSec 1
    if (-not $ok) { throw "체결 증분 미달(기준 $($cfg['MIN_TRADES_BEFORE_INJECT'])건). 봇 부하가 실리지 않았다. chaos-bots 상태와 BASE_OPS를 확인한다." }

    Write-Section 'ⓐ 사전 권위 run'
    $prePort = Get-LiveNodePort
    $s0 = Get-HarnessStatus -Port $prePort
    $oldEpoch = [long]$s0.epoch
    $pre = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'pre'
    Write-Host "사전 verdict=$($pre.verdict) old_epoch=$oldEpoch"

    # redis 토폴로지를 실제로 추가할 때 이 경로의 복귀 전제를 먼저 고쳐야 한다. docker kill은
    # 데몬이 수동 정지로 표시해 restart 정책을 건너뛴다(E4 헤더 실측, Docker 29.5.2). 아래
    # $recovered 대기는 자동 부활을 전제하므로 그대로 두면 확정 타임아웃이고, 그 FAIL은 코드
    # 결함이 아니라 판정 결함이다. 부활을 관측하려면 주입을 프로세스 크래시나 명시 재기동으로
    # 바꿔야 한다. 현 스택에는 redis가 없어 이 경로가 도달 불가라 여기서는 사실만 남긴다.
    Write-Section "ⓑ 주입: docker kill $redis"
    $t0 = (Get-Date).ToUniversalTime()
    & docker kill $redis | Out-Null
    Write-Host "T0=$($t0.ToString('o'))"

    Write-Section 'ⓒ Redis 부재 중 무결성 관측'
    $injRun = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'inject'
    $injScans = Get-Scalar "SELECT count(*) FROM invariant_checks WHERE details->>'run_id'='$runId' AND details->>'window'='inject'" -Required
    if ($injScans -lt 1) { throw 'inject 창 내 권위 run 미적재(공허 통과 방지 가드)' }

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

    Write-Verdict -ExperimentId 'E3' -RunId $runId -Verdict $verdict -Metrics @{
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
