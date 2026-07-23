# docs §3 E1 PG 지연/지터. 리더 pg 프록시에 latency=200/jitter=100(downstream) 주입, 120s 유지.
# 페일오버 아님. 판정: 오류율 < 0.1% · 무결성 위반 0 · 해제 후 60s 내 p99 기저 복귀 · epoch 불변.
# 지연 중에도 사전/사후 권위 run으로 무결성을 확인하고, status(DB 경유)를 반복 GET해 p99·오류율을
# 표본화한다. 개입 금지 구간은 없다(지연은 자동 해제 후 복귀만 관찰).
# 파일명 유의: 레거시 e1-leader-kill.ps1(=docs E4)과 별개다. docs 번호로 명명.
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
    Start-Sleep -Seconds ([int]$cfg['WARMUP_SEC'])
    $ok = Wait-For { (Get-TradeCount) -ge [long]$cfg['MIN_TRADES_BEFORE_INJECT'] } -TimeoutSec 120 -PollSec 1
    if (-not $ok) { throw '체결 미달, 봇 상태 확인' }

    # 리더의 pg 프록시·노드를 대상 삼는다. HikariCP 대기(§3 E1 기대 동작)는 매칭 리더 경로에서
    # 관찰해야 정본에 충실하다. 판정 자체는 리더 여부에 무관하나 주입·표본을 같은 노드로 일치시킨다.
    $detected = Get-LeaderContainer
    if ($detected) {
        if ($detected -eq 'chaos-api-1') { $proxy = 'pg-api1'; $port = 18080 }
        else { $proxy = 'pg-api2'; $port = 28080 }
        Write-Host "리더 감지: $detected -> proxy=$proxy port=$port"
    } else {
        Write-Host "리더 감지 실패. config LEADER_PROXY=$proxy port=$port 사용."
    }

    Write-Section 'ⓐ 사전 권위 run + baseline 지연'
    $prePort = Get-LiveNodePort
    $s0 = Get-HarnessStatus -Port $prePort
    $oldEpoch = [long]$s0.epoch
    $pre = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'pre'
    Write-Host "사전 verdict=$($pre.verdict) old_epoch=$oldEpoch"
    $base = Measure-Latency -Port $port -Count $sampleN -GapMs $gapMs
    $baseP99 = Get-Percentile -Values $base.Samples -P 99
    Write-Host ("baseline p99={0}ms 실패={1}/{2}" -f ([math]::Round($baseP99, 1)), $base.Errors, $base.Total)

    Write-Section "ⓑ 주입: latency=$($cfg['TOXIC_LATENCY_MS'])/jitter=$($cfg['TOXIC_JITTER_MS']) on $proxy"
    Add-Toxic -Proxy $proxy -Name 'e1-latency' -Type 'latency' `
        -Attributes @{ latency = [int]$cfg['TOXIC_LATENCY_MS']; jitter = [int]$cfg['TOXIC_JITTER_MS'] }
    $t0 = (Get-Date).ToUniversalTime()
    Write-Host "T0=$($t0.ToString('o'))"

    # 지연 중 무결성: 상시 스캔 주기와 무관하게 창 내 실제 스캔 1회를 강제(공허 통과 차단).
    Write-Section 'ⓒ 지연 중 관측(온디맨드 권위 run + 지연 표본)'
    $injRun = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'inject'
    $injScans = Get-Scalar "SELECT count(*) FROM invariant_checks WHERE details->>'run_id'='$runId' AND details->>'window'='inject'"
    if ($injScans -lt 1) { throw '지연 창 내 권위 run 미적재(공허 통과 방지 가드)' }
    $inj = Measure-Latency -Port $port -Count $sampleN -GapMs $gapMs
    $injP99 = Get-Percentile -Values $inj.Samples -P 99
    $errRate = if ($inj.Total -gt 0) { [double]$inj.Errors / $inj.Total } else { 0 }
    Write-Host ("지연 중 p99={0}ms 오류율={1} 무결성위반={2}" -f ([math]::Round($injP99, 1)), $errRate, $injRun.totalViolations)
    # 잔여 창 소진(표본 채취가 창보다 짧으면).
    $elapsed = ((Get-Date).ToUniversalTime() - $t0).TotalSeconds
    $remain = [int]$cfg['INJECT_DURATION_SEC'] - [int]$elapsed
    if ($remain -gt 0) { Start-Sleep -Seconds $remain }

    Write-Section 'ⓓ 해제 + p99 복귀 측정'
    Remove-AllToxics -Proxy $proxy
    $tRelease = (Get-Date).ToUniversalTime()
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

    $epochStable = ($newEpoch -eq $oldEpoch)
    $recovered = ($null -ne $recoverySec -and $recoverySec -le $recSec)
    $errOk = ($errRate -lt $errMax)
    # 무결성은 ALWAYS 스코프만 본다. 지연 중 C13a(비동기 물질화)·C19는 순간 위반 정상(§4-2 ④).
    $injClean = Test-ExperimentIntegrity $injRun
    $postClean = Test-ExperimentIntegrity $post
    $verdict = 'FAIL'
    if ($injClean -and $postClean -and $errOk -and $recovered -and $epochStable) {
        $verdict = 'PASS'
    }

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
        injectAlwaysClean = $injClean
        postAlwaysClean   = $postClean
    }
}
finally {
    Write-Section '종료 훅: 토식 해제'
    Remove-AllToxics -Proxy $proxy
}

if ($verdict -ne 'PASS') { Write-Host 'E1(PG 지연) FAIL.' -ForegroundColor Red; exit 1 }
Write-Host 'E1(PG 지연) PASS.' -ForegroundColor Green
