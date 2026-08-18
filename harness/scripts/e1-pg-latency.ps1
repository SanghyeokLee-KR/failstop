# docs §3 E1 PG 지연/지터. 리더 pg 프록시에 latency=200/jitter=100(downstream) 주입, 120s 유지.
# 페일오버 아님. 판정은 정본 §3 E1 합격 기준의 1차·2차 구조를 그대로 따른다.
#   1차(필수): 무결성 위반 0 · epoch 불변 · exchange_leader_standdown_total 증가 0 ·
#              주입 중·해제 후 컨테이너 unhealthy 표본 0 · 체결 정지 0(느려지되 멈추지 않음) ·
#              해제 후 자력 회복(체결 재개 + 60s 내 p99 기저 복귀)
#   2차: 오류율 < 0.1%. 백프레셔 429는 정상 동작이라 이 축은 경고 보고로만 낸다.
# 지연 중에도 사전/사후 권위 run으로 무결성을 확인하고, status(DB 경유)를 반복 GET해 p99·오류율을
# 표본화한다. 개입 금지 구간은 없다(지연은 자동 해제 후 복귀만 관찰).
# 파일명 유의: 리더 kill 실험은 e4-leader-kill.ps1(구 e1-leader-kill, M4 §0-3 개명)이다.
param([string] $ConfigPath = (Join-Path $PSScriptRoot '..\config\e1-pg-latency.env'))

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
$cfg = Read-EnvFile $ConfigPath
$runId = "E1-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
$proxy = $cfg['LEADER_PROXY']
$port = [int]$cfg['LEADER_PORT']
$sampleN = [int]$cfg['LATENCY_SAMPLE_COUNT']
$gapMs = [int]$cfg['SAMPLE_GAP_MS']
$errMax = [double]$cfg['ERROR_RATE_MAX']
$recSec = [int]$cfg['P99_RECOVERY_SEC']
$recMargin = [double]$cfg['P99_RECOVERY_MARGIN']

function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades") }

Write-Section "E1(PG 지연) 시작 runId=$runId proxy=$proxy port=$port"
try {
    # 실험별 봇 부하 적용(Tier 1). E1은 normal 부하라 저부하로 재기동해 지연 하 요청 풀 포화를 막는다.
    if ($cfg['BASE_OPS']) { Set-BotOps ([int]$cfg['BASE_OPS']) }
    Start-Sleep -Seconds ([int]$cfg['WARMUP_SEC'])
    # 누적이 아니라 증분으로 본다. 누적이면 앞선 실험이 남긴 체결만으로 즉시 통과해 무부하로
    # 주입하게 되고, 그러면 아래 1차 게이트인 체결 흐름($tradesFlowing)이 부하 부재를 장애로
    # 오인해 FAIL을 낸다. 전제조건과 판정이 같은 술어를 써야 한다(E2·E5와 동형).
    $tradesBase = Get-TradeCount
    $ok = Wait-For { (Get-TradeCount) - $tradesBase -ge [long]$cfg['MIN_TRADES_BEFORE_INJECT'] } -TimeoutSec 120 -PollSec 1
    if (-not $ok) { throw "체결 증분 미달(기준 $($cfg['MIN_TRADES_BEFORE_INJECT'])건). 봇 부하가 실리지 않았다. chaos-bots 상태와 BASE_OPS를 확인한다." }

    # 리더의 pg 프록시·노드를 대상 삼는다. HikariCP 대기(§3 E1 기대 동작)는 매칭 리더 경로에서
    # 관찰해야 정본에 충실하다. 판정 자체는 리더 여부에 무관하나 주입·표본을 같은 노드로 일치시킨다.
    # 감지 실패 시 config 추정으로 강행하지 않는다. 신설 1차 축(컨테이너 헬스 표본, 강등 델타,
    # status 오류율)이 전부 노드 지정에 종속되는데, 추정 노드에서 표본을 뜨면 지연이 걸리지 않은
    # 팔로워를 재고 PASS를 내게 된다. 증거가 무효인 실행은 하지 않는다(E2·E5와 같은 규약).
    $detected = Get-LeaderContainer
    if (-not $detected) {
        throw '리더 감지 실패. 리더를 특정하지 못하면 주입 대상과 표본 대상이 어긋나 판정이 무의미하다. leader_lease holder와 컨테이너 로그를 대조해 확인한 뒤 재실행한다.'
    }
    if ($detected -eq 'chaos-api-1') { $proxy = 'pg-api1'; $port = 18080; $standbyPort = 28080 }
    else { $proxy = 'pg-api2'; $port = 28080; $standbyPort = 18080 }
    $leaderContainer = $detected
    Write-Host "리더 감지: $detected -> proxy=$proxy port=$port standby=$standbyPort"

    Write-Section 'ⓐ 사전 권위 run + baseline 지연'
    $prePort = Get-LiveNodePort
    $s0 = Get-HarnessStatus -Port $prePort
    $oldEpoch = [long]$s0.epoch
    $pre = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'pre'
    Write-Host "사전 verdict=$($pre.verdict) old_epoch=$oldEpoch"
    $base = Measure-Latency -Port $port -Count $sampleN -GapMs $gapMs
    $baseP99 = Get-Percentile -Values $base.Samples -P 99
    Write-Host ("baseline p99={0}ms 실패={1}/{2}" -f ([math]::Round($baseP99, 1)), $base.Errors, $base.Total)

    # 1차 게이트 기저. 자발적 강등 카운터는 주입 전후 델타 0이어야 한다(정본 §3 E1). 여기서는
    # -Required로 잡는다. 주입 전에 리더 스크레이프가 안 되면 이 축을 아예 판정할 수 없다.
    $standdownPattern = 'exchange_leader_standdown_total(?:\{[^}]*\})?\s+([0-9.eE+-]+)'
    $standdownBaseRaw = Get-AppMetric -Port $port -Pattern $standdownPattern -Required
    $standdownBase = 0.0; if ($null -ne $standdownBaseRaw) { $standdownBase = [double]$standdownBaseRaw }
    $tradesBeforeInject = Get-TradeCount

    Write-Section "ⓑ 주입: latency=$($cfg['TOXIC_LATENCY_MS'])/jitter=$($cfg['TOXIC_JITTER_MS']) on $proxy"
    Add-Toxic -Proxy $proxy -Name 'e1-latency' -Type 'latency' `
        -Attributes @{ latency = [int]$cfg['TOXIC_LATENCY_MS']; jitter = [int]$cfg['TOXIC_JITTER_MS'] }
    $t0 = (Get-Date).ToUniversalTime()
    Write-Host "T0=$($t0.ToString('o'))"
    # 헬스 표본은 주입 직후부터 모은다. 창 끝에서만 재면 권위 run과 지연 표본이 창을 다 쓴 경우
    # 표본이 1개로 끝나 "창 전체 관측"이 성립하지 않는다.
    $injHealth = New-Object System.Collections.Generic.List[string]
    $injHealth.Add((Get-ContainerHealth $leaderContainer))

    # 지연 중 무결성: 상시 스캔 주기와 무관하게 창 내 실제 스캔 1회를 강제(공허 통과 차단). 단 이
    # 권위 run은 지연이 걸린 리더 DB 경로가 아니라 스탠바이 노드로 돌린다. 무결성은 공유 chaos-pg의
    # 상태 속성이라 어느 노드에서 조회해도 리더가 만든 위반을 잡는다. 리더로 돌리면 지연 하에서 run이
    # 60s를 초과할 뿐 아니라 그 부하가 리더 리스 하트비트를 굶겨 비페일오버 전제(epoch 불변)를 스스로
    # 깬다(실측). 지연 표본은 아래에서 리더 경로로 채취해 열화를 측정한다. 스탠바이 미검출 시 리더 폴백.
    $injRunPort = if ($standbyPort) { $standbyPort } else { $prePort }
    Write-Section 'ⓒ 지연 중 관측(온디맨드 권위 run[스탠바이 경로] + 지연 표본[리더 경로])'
    $injRun = Invoke-InvariantRun -Port $injRunPort -RunId $runId -Window 'inject'
    $injHealth.Add((Get-ContainerHealth $leaderContainer))
    $injScans = Get-Scalar "SELECT count(*) FROM invariant_checks WHERE details->>'run_id'='$runId' AND details->>'window'='inject'"
    if ($injScans -lt 1) { throw '지연 창 내 권위 run 미적재(공허 통과 방지 가드)' }
    $inj = Measure-Latency -Port $port -Count $sampleN -GapMs $gapMs
    $injHealth.Add((Get-ContainerHealth $leaderContainer))
    $injP99 = Get-Percentile -Values $inj.Samples -P 99
    $errRate = if ($inj.Total -gt 0) { [double]$inj.Errors / $inj.Total } else { 0 }
    Write-Host ("지연 중 p99={0}ms 오류율={1} 무결성위반={2}" -f ([math]::Round($injP99, 1)), $errRate, $injRun.totalViolations)
    # 잔여 창 소진. 단순 대기 대신 헬스를 표본화해 1차 게이트(주입 중 unhealthy 0)의 증거를 창
    # 전체에서 모은다. 종전에는 이 구간이 그냥 Start-Sleep이라 지연 중 노드 상태를 아무도 안 봤다.
    $elapsed = ((Get-Date).ToUniversalTime() - $t0).TotalSeconds
    $remain = [int]$cfg['INJECT_DURATION_SEC'] - [int]$elapsed
    while ($remain -gt 0) {
        $step = [math]::Min(5, $remain)
        Start-Sleep -Seconds $step
        $remain -= $step
        $injHealth.Add((Get-ContainerHealth $leaderContainer))
    }
    # 'unknown'(도커 조회 실패)도 불량 표본으로 센다. 상태를 모르는 것은 healthy의 증거가 아니다.
    $injUnhealthy = @($injHealth | Where-Object { $_ -eq 'unhealthy' -or $_ -eq 'unknown' }).Count
    $tradesAfterInject = Get-TradeCount
    $tradeDeltaInject = $tradesAfterInject - $tradesBeforeInject
    Write-Host ("주입 창 헬스 표본 {0}개 중 불량 {1}, 체결 증분 {2}" -f $injHealth.Count, $injUnhealthy, $tradeDeltaInject)

    Write-Section 'ⓓ 해제 + p99 복귀 측정'
    Remove-AllToxics -Proxy $proxy
    $tRelease = (Get-Date).ToUniversalTime()
    # 해제 후 창도 시작점부터 표본화한다. 복귀 루프(최대 60s)와 사후 run 뒤에서만 재면
    # 정본이 요구하는 "해제 후 전 구간"과 창이 어긋난다.
    $postHealth = New-Object System.Collections.Generic.List[string]
    $postHealth.Add((Get-ContainerHealth $leaderContainer))
    $recThreshold = [math]::Max($baseP99 * $recMargin, $baseP99 + 50)
    $recoverySec = $null
    $deadline = (Get-Date).AddSeconds($recSec)
    while ((Get-Date) -lt $deadline) {
        $batch = Measure-Latency -Port $port -Count 30 -GapMs $gapMs
        $p = Get-Percentile -Values $batch.Samples -P 99
        if ($null -ne $p -and $p -le $recThreshold) {
            $recoverySec = ((Get-Date).ToUniversalTime() - $tRelease).TotalSeconds
            break
        }
    }
    $recDisp = if ($null -ne $recoverySec) { [math]::Round($recoverySec, 1) } else { 'n/a(>60s)' }
    Write-Host ("복귀 임계 p99<={0}ms 복귀시간={1}s" -f ([math]::Round($recThreshold, 1)), $recDisp)

    Write-Section 'ⓔ 사후 권위 run'
    $postPort = Get-LiveNodePort
    $post = Invoke-InvariantRun -Port $postPort -RunId $runId -Window 'post'
    $s1 = Get-HarnessStatus -Port $postPort
    $newEpoch = [long]$s1.epoch

    # 해제 후 창 마무리 표본. 자력 회복은 p99 복귀만이 아니라 체결 재개까지다(정본 §3 E1 1차).
    for ($i = 0; $i -lt 6; $i++) {
        $postHealth.Add((Get-ContainerHealth $leaderContainer))
        Start-Sleep -Seconds 2
    }
    $postUnhealthy = @($postHealth | Where-Object { $_ -eq 'unhealthy' -or $_ -eq 'unknown' }).Count
    $tradeDeltaAfter = (Get-TradeCount) - $tradesAfterInject
    # 스크레이프 실패는 시계열 부재와 다르다. 해제 후에도 리더가 응답하지 못하면 그 자체가 1차
    # 게이트 위반이므로 델타를 알 수 없는 것으로 두지 않고 강등 발생과 같은 급으로 처리한다.
    $standdownAfterRaw = $null; $standdownScrapeOk = $true
    try { $standdownAfterRaw = Get-AppMetric -Port $port -Pattern $standdownPattern -Required }
    catch {
        $standdownScrapeOk = $false
        Write-Host "해제 후 리더 스크레이프 실패($($_.Exception.Message)). 1차 게이트 위반으로 계상" -ForegroundColor Red
    }
    $standdownAfter = 0.0; if ($null -ne $standdownAfterRaw) { $standdownAfter = [double]$standdownAfterRaw }
    $standdownDelta = $standdownAfter - $standdownBase
    $standdownOk = $standdownScrapeOk -and ($standdownDelta -eq 0)

    # 정본이 신설한 관측 축. 판정에는 넣지 않고 표본만 남긴다(백프레셔는 정상 동작이라 임계가 없다).
    $admissionRejected = Get-AppMetric -Port $port -Pattern 'exchange_admission_rejected_total\{[^}]*scope="order"[^}]*\}\s+([0-9.eE+-]+)'
    $nodeServiceable = Get-AppMetric -Port $port -Pattern 'exchange_node_serviceable(?:\{[^}]*\})?\s+([0-9.eE+-]+)'

    $epochStable = ($newEpoch -eq $oldEpoch)
    $recovered = ($null -ne $recoverySec -and $recoverySec -le $recSec)
    # 무결성은 조건부 스코프 체크를 뺀 나머지로 본다. 지연 중 C13a(비동기 물질화)는 순간 위반 정상(§4-2 ④).
    $injClean = Test-ExperimentIntegrity $injRun
    $postClean = Test-ExperimentIntegrity $post
    $healthOk = (($injUnhealthy + $postUnhealthy) -eq 0)
    # "느려지되 멈추지 않음". 주입 창과 해제 후 창 각각에서 체결이 늘어야 한다. 누적이 아니라
    # 증분으로 보는 이유는 앞선 실험이 남긴 체결이 정지를 가려주기 때문이다.
    $tradesFlowing = ($tradeDeltaInject -gt 0) -and ($tradeDeltaAfter -gt 0)

    # $errRate는 1차 축이다. 2차로 강등하지 않는다. 표본원이 Measure-Latency의
    # '/harness/v1/status'이고 그 경로는 수용 제한 면제라 429가 오지 않는다. 정본 1차의
    # "주입 중·해제 후 전 구간 status·readiness 응답 유지 / 하네스 status가 계속 응답해야 한다"를
    # 재는 유일한 축이 이것이다. 정본이 2차로 내린 오류율은 429가 섞이는 k6 주문 경로 축이고,
    # 이 스크립트는 k6를 돌리지 않아 그 축을 아예 측정하지 않는다. 둘을 같은 이름으로 뭉개
    # 강등하면 1차 요구가 조용히 사라진다.
    $statusRespOk = ($errRate -lt $errMax)

    # 정본 §3 E1 합격 기준 1차. 종전 판정은 1차 중 강등 0·체결 정지 0·unhealthy 0을 하나도
    # 평가하지 않아, 리더가 좀비가 되어 체결이 멈추고 컨테이너가 unhealthy여도 PASS가 나올 수 있었다.
    # 컨테이너 헬스는 단독으로는 둔감하다(healthcheck interval 3s x retries 30 = 90s 연속 실패라야
    # unhealthy, start_period 40s는 실패해도 starting). 그래서 $statusRespOk와 체결 흐름이 함께 있어야
    # 좀비 구간이 잡힌다. 셋을 모두 1차에 두는 이유다.
    $primaryOk = $injClean -and $postClean -and $epochStable -and $standdownOk -and `
                 $healthOk -and $tradesFlowing -and $recovered -and $statusRespOk
    $verdict = 'FAIL'
    if ($primaryOk) { $verdict = 'PASS' }

    Write-Verdict -ExperimentId 'E1' -Verdict $verdict -Metrics @{
        baselineP99Ms = [math]::Round($baseP99, 1)
        injectP99Ms   = [math]::Round($injP99, 1)
        errorRate     = $errRate
        errorRateMax  = $errMax
        recoverySec   = $recoverySec
        recoveryMaxSec = $recSec
        epochBefore   = $oldEpoch
        epochAfter    = $newEpoch
        epochStable   = $epochStable
        injectTotalViolations = [long]$injRun.totalViolations
        injectGatedClean = $injClean
        postGatedClean   = $postClean
        standdownDelta   = $standdownDelta
        standdownScrapeOk = $standdownScrapeOk
        standdownOk      = $standdownOk
        injectUnhealthySamples = $injUnhealthy
        postUnhealthySamples   = $postUnhealthy
        healthOk          = $healthOk
        tradeDeltaInject  = $tradeDeltaInject
        tradeDeltaAfter   = $tradeDeltaAfter
        tradesFlowing     = $tradesFlowing
        primaryOk         = $primaryOk
        statusRespOk      = $statusRespOk
        admissionRejectedOrder = $admissionRejected
        nodeServiceable        = $nodeServiceable
    }
}
finally {
    Write-Section '종료 훅: 토식 해제'
    Remove-AllToxics -Proxy $proxy
}

if ($verdict -ne 'PASS') { Write-Host 'E1(PG 지연) FAIL.' -ForegroundColor Red; exit 1 }
Write-Host 'E1(PG 지연) PASS.' -ForegroundColor Green
# 명시 종료코드. 앞선 네이티브 호출($LASTEXITCODE)이 프로세스 종료코드로 새면 PASS가 exit 1이 된다.
exit 0
