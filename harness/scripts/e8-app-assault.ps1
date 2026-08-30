# docs §3 E8 앱 내부 지연/예외 폭풍(Chaos Monkey 3.3.0 actuator assault).
# 판정: Resilience4j CB open -> fail-fast 503, 매칭 코어 무결성 비전파, 해제 후 CB 자동 복귀.
# 전제: 서빙 노드가 chaos-monkey 프로파일로 기동해 /actuator/chaosmonkey를 노출하고, CB 프로브 평면
# (/internal/chaos/probe, resilience4j chaosProbe)이 배선돼 있어야 한다. 미노출이면 SKIP(전제 미충족).
# assault는 chaos.monkey.watcher.beans=chaosProbeDownstream 단일 빈에 국한돼 주문·원장·엔진 write
# 경로에 닿지 않는다. 무결성 비전파는 이 격리로 구조적으로 성립하며, 권위 run이 이를 기계 판정한다.
param([string] $ConfigPath = (Join-Path $PSScriptRoot '..\config\e8-app-assault.env'))

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
$cfg = Read-EnvFile $ConfigPath
$runId = "E8-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
$port = [int]$cfg['ASSAULT_PORT']
$cmBase = "http://localhost:$port/actuator/chaosmonkey"
$probeUrl = "http://localhost:$port/internal/chaos/probe"

function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades" -Required) }

# chaosProbe CB의 현재 상태. resilience4j micrometer가 state 라벨별로 현재=1을 낸다. 부재 시 $null.
# 라벨은 알파벳 정렬(application,name,state)이라 name 뒤 state 순으로 매칭한다.
function Get-CbState {
    try { $text = Invoke-RestMethod -Uri "http://localhost:$port/actuator/prometheus" -TimeoutSec 5 } catch { return $null }
    $s = ($text | Out-String)
    foreach ($st in @('open', 'half_open', 'closed')) {
        $pat = 'resilience4j_circuitbreaker_state\{[^}]*name="chaosProbe"[^}]*state="' + $st + '"[^}]*\}\s+1(?:\.0)?(?:\s|$)'
        if ([regex]::Match($s, $pat).Success) { return $st }
    }
    return $null
}
function Test-CircuitOpen { return ((Get-CbState) -eq 'open') }

# 프로브 1회. 200=ok, 503=unavailable(fail-fast 또는 downstream 실패), 그 외=error. 503은
# Invoke-WebRequest에서 예외로 오므로 응답 StatusCode로 분류한다. 지연 assault(<=3s) 대비 여유 타임아웃.
function Invoke-ProbeOnce {
    param([int] $TimeoutSec = 6)
    try {
        $resp = Invoke-WebRequest -Uri $probeUrl -TimeoutSec $TimeoutSec -UseBasicParsing
        if ([int]$resp.StatusCode -eq 200) { return 'ok' } else { return 'unavailable' }
    } catch {
        $sc = 0
        if ($_.Exception.Response) { try { $sc = [int]$_.Exception.Response.StatusCode } catch { $sc = 0 } }
        if ($sc -eq 503) { return 'unavailable' } else { return 'error' }
    }
}

Write-Section "E8(앱 assault) 시작 runId=$runId port=$port type=$($cfg['ASSAULT_TYPE'])"

# ── 전제 검사: Chaos Monkey actuator 존재 ──────────────────────────────
$cmAvailable = $false
try {
    Invoke-RestMethod -Uri $cmBase -TimeoutSec 5 | Out-Null
    $cmAvailable = $true
} catch { $cmAvailable = $false }

if (-not $cmAvailable) {
    Write-Section 'SKIP: Chaos Monkey actuator 부재'
    $prePort = Get-LiveNodePort
    $run = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'skip'
    Write-Verdict -ExperimentId 'E8' -Verdict 'SKIP' -Metrics @{
        reason           = "/actuator/chaosmonkey 미노출. 서빙 노드를 chaos-monkey 프로파일로 기동해야 한다."
        chaosMonkeyBase  = $cmBase
        integrityCriticalClean = [bool]$run.criticalClean
        requiredForLive  = @('de.codecentric:chaos-monkey-spring-boot:3.3.0 dependency',
                            'SPRING_PROFILES_ACTIVE 에 chaos-monkey 포함(serving node)',
                            'management exposure 에 chaosmonkey + resilience4j chaosProbe CB')
    }
    Write-Host ''
    Write-Host 'E8 SKIP: Chaos Monkey 미배선. 라이브 실행 대기(배선 후 재현).' -ForegroundColor Yellow
    exit 0
}

# ── 라이브 경로(actuator 존재 시) ──────────────────────────────────────
try {
    # 자기 부하를 명시로 세운다. 생략하면 앞선 실험이 남긴 ops를 이월받는다(E6 직후면 400,
    # 드레인 직후면 정지 상태). 부하가 무엇인지 모르는 채 assault하면 판정 조건이 실행마다 달라진다.
    if ($cfg['BASE_OPS']) { Set-BotOps ([int]$cfg['BASE_OPS']) }
    Start-Sleep -Seconds ([int]$cfg['WARMUP_SEC'])
    # 매칭 경로에 쓰기가 실려 있는지 증분으로 확인한다. E8의 지배 주장은 "assault가 매칭 코어로
    # 전파되지 않음"인데, 무부하면 injClean·postClean이 무활동으로 참이 되어 그 주장이 공허해진다.
    # 누적 비교로는 앞선 실험이 남긴 체결만으로 통과하므로 증분으로 본다(E2·E5와 같은 술어).
    $tradesBase = Get-TradeCount
    $ok = Wait-For { (Get-TradeCount) - $tradesBase -ge [long]$cfg['MIN_TRADES_BEFORE_INJECT'] } -TimeoutSec 120 -PollSec 1
    if (-not $ok) { throw "체결 증분 미달(기준 $($cfg['MIN_TRADES_BEFORE_INJECT'])건). 봇 부하가 실리지 않았다. chaos-bots 상태와 BASE_OPS를 확인한다." }

    Write-Section 'ⓐ 사전 권위 run'
    $prePort = Get-LiveNodePort
    $pre = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'pre'
    Write-Host "사전 verdict=$($pre.verdict)"
    $preProbe = Invoke-ProbeOnce
    Write-Host "사전 프로브=$preProbe CB=$(Get-CbState)"

    Write-Section "ⓑ 주입: $($cfg['ASSAULT_TYPE']) assault"
    Invoke-RestMethod -Uri "$cmBase/enable" -Method Post -TimeoutSec 10 | Out-Null
    if ($cfg['ASSAULT_TYPE'] -eq 'exception') {
        $assault = @{ level = 1; exceptionsActive = $true; latencyActive = $false } | ConvertTo-Json
    } else {
        $assault = @{ level = 1; latencyActive = $true;
                      latencyRangeStart = [int]$cfg['LATENCY_RANGE_START_MS'];
                      latencyRangeEnd = [int]$cfg['LATENCY_RANGE_END_MS'] } | ConvertTo-Json
    }
    Invoke-RestMethod -Uri "$cmBase/assaults" -Method Post -ContentType 'application/json' -Body $assault -TimeoutSec 10 | Out-Null
    $t0 = (Get-Date).ToUniversalTime()
    Write-Host "T0=$($t0.ToString('o'))"

    Write-Section 'ⓒ assault 중 관측(프로브 부하 -> CB open -> fail-fast 503 + 무결성 비전파)'
    # 프로브를 반복 호출해 assault를 CB에 전달한다. 지연 assault는 slow-call, 예외 assault는 failure로
    # 집계돼 임계 초과 시 CB가 open된다. open 후 호출은 CallNotPermittedException으로 fail-fast 503.
    $injectDeadline = (Get-Date).AddSeconds([int]$cfg['INJECT_DURATION_SEC'])
    $probe = @{ total = 0; ok = 0; unavailable = 0; error = 0 }
    $cbOpenedAt = $null
    $failFastAfterOpen = 0
    while ((Get-Date) -lt $injectDeadline) {
        $one = Invoke-ProbeOnce
        $probe.total++
        switch ($one) { 'ok' { $probe.ok++ } 'unavailable' { $probe.unavailable++ } default { $probe.error++ } }
        if (-not $cbOpenedAt) {
            if (Test-CircuitOpen) { $cbOpenedAt = (Get-Date).ToUniversalTime(); Write-Host "CB open 확인 t=$($cbOpenedAt.ToString('o'))" }
        } else {
            # 안정 확인 뒤 종료해 불필요한 지속 부하를 만들지 않는다.
            if ($one -eq 'unavailable') { $failFastAfterOpen++ }
            if ($failFastAfterOpen -ge 5) { break }
        }
        Start-Sleep -Milliseconds 200
    }
    $cbOpened = [bool]$cbOpenedAt

    # assault 활성 상태에서 권위 run. watcher.beans 격리로 매칭 경로는 assault를 받지 않아야 한다.
    $injRun = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'inject'
    $injScans = Get-Scalar "SELECT count(*) FROM invariant_checks WHERE details->>'run_id'='$runId' AND details->>'window'='inject'" -Required
    if ($injScans -lt 1) { throw 'inject 창 내 권위 run 미적재(공허 통과 방지 가드)' }

    Write-Section 'ⓓ 해제 + CB 복귀(half-open -> closed)'
    $noAssault = @{ latencyActive = $false; exceptionsActive = $false } | ConvertTo-Json
    Invoke-RestMethod -Uri "$cmBase/assaults" -Method Post -ContentType 'application/json' -Body $noAssault -TimeoutSec 10 | Out-Null
    Invoke-RestMethod -Uri "$cmBase/disable" -Method Post -TimeoutSec 10 | Out-Null
    # 복귀는 프로브 트래픽이 있어야 완결된다. wait-duration 후 자동 half-open으로 전이하고, half-open
    # 허용 호출(3건)이 성공해야 closed로 닫힌다. 프로브를 계속 흘려 그 전이를 구동·관측한다.
    $recoveryDeadline = (Get-Date).AddSeconds([int]$cfg['CB_RECOVERY_SEC'])
    $recovery = @{ total = 0; ok = 0 }
    $cbClosed = $false
    while ((Get-Date) -lt $recoveryDeadline) {
        $one = Invoke-ProbeOnce
        $recovery.total++
        if ($one -eq 'ok') { $recovery.ok++ }
        if ((Get-CbState) -eq 'closed' -and $recovery.ok -ge 1) { $cbClosed = $true; break }
        Start-Sleep -Milliseconds 300
    }
    Write-Host "복귀 상태 CB=$(Get-CbState) recoveryOk=$($recovery.ok)/$($recovery.total)"

    Write-Section 'ⓔ 사후 권위 run'
    $postPort = Get-LiveNodePort
    $post = Invoke-InvariantRun -Port $postPort -RunId $runId -Window 'post'

    $injClean = Test-ExperimentIntegrity $injRun
    $postClean = Test-ExperimentIntegrity $post
    $verdict = 'FAIL'
    if ($injClean -and $postClean -and $cbOpened -and $cbClosed) { $verdict = 'PASS' }

    Write-Verdict -ExperimentId 'E8' -Verdict $verdict -Metrics @{
        assaultType           = $cfg['ASSAULT_TYPE']
        cbOpened              = [bool]$cbOpened
        cbRecovered           = [bool]$cbClosed
        injectAlwaysClean     = $injClean
        postAlwaysClean       = $postClean
        injectTotalViolations = [long]$injRun.totalViolations
        probeTotal            = [long]$probe.total
        probeUnavailable503   = [long]$probe.unavailable
        probeOk               = [long]$probe.ok
        recoveryOk            = [long]$recovery.ok
    }
}
finally {
    Write-Section '종료 훅: assault 해제'
    try {
        $off = @{ latencyActive = $false; exceptionsActive = $false } | ConvertTo-Json
        Invoke-RestMethod -Uri "$cmBase/assaults" -Method Post -ContentType 'application/json' -Body $off -TimeoutSec 10 | Out-Null
        Invoke-RestMethod -Uri "$cmBase/disable" -Method Post -TimeoutSec 10 | Out-Null
    } catch { }
}

if ($verdict -ne 'PASS') { Write-Host 'E8 FAIL.' -ForegroundColor Red; exit 1 }
Write-Host 'E8 PASS.' -ForegroundColor Green
