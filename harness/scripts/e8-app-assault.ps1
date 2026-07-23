# docs §3 E8 앱 내부 지연/예외 폭풍(Chaos Monkey 3.3.0 actuator assault).
# 판정: Resilience4j CB open -> fail-fast 503, 매칭 코어 무결성 비전파, 해제 후 CB 자동 복귀.
# 전제: 앱 이미지에 chaos-monkey 의존·프로파일·actuator 노출이 있어야 한다. 현 dev 프로파일 이미지에
# /actuator/chaosmonkey가 없으면 SKIP(전제 미충족)을 내고 필요한 배선을 보고한다. 무단 추가 금지.
param([string] $ConfigPath = (Join-Path $PSScriptRoot '..\config\e8-app-assault.env'))

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
$cfg = Read-EnvFile $ConfigPath
$runId = "E8-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
$port = [int]$cfg['ASSAULT_PORT']
$cmBase = "http://localhost:$port/actuator/chaosmonkey"

function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades") }

# CB open 표본: resilience4j 게이지에서 state="open" 값이 1인 항목 존재 여부. 부재 시 $null.
function Test-CircuitOpen {
    $v = Get-AppMetric -Port $port -Pattern 'resilience4j_circuitbreaker_state\{[^}]*state="open"[^}]*\}\s+1(?:\.0)?'
    # 위 패턴은 캡처그룹이 없어 Get-AppMetric이 $null을 준다. 존재 판정은 별도 스캔.
    try { $text = Invoke-RestMethod -Uri "http://localhost:$port/actuator/prometheus" -TimeoutSec 5 } catch { return $false }
    return ([regex]::Match(($text | Out-String), 'resilience4j_circuitbreaker_state\{[^}]*state="open"[^}]*\}\s+1(?:\.0)?')).Success
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
        reason           = "/actuator/chaosmonkey 미노출. 앱 이미지에 chaos-monkey 배선 필요."
        chaosMonkeyBase  = $cmBase
        integrityCriticalClean = [bool]$run.criticalClean
        requiredForLive  = @('chaos-monkey-spring-boot-starter 3.3.0 dependency',
                            'spring.profiles.active += chaos-monkey (or chaos.monkey.enabled=true)',
                            'management.endpoint.chaosmonkey.enabled=true + expose actuator')
    }
    Write-Host ''
    Write-Host 'E8 SKIP: Chaos Monkey 미배선. 라이브 실행 대기(배선 후 재현).' -ForegroundColor Yellow
    exit 0
}

# ── 라이브 경로(actuator 존재 시) ──────────────────────────────────────
try {
    Start-Sleep -Seconds ([int]$cfg['WARMUP_SEC'])

    Write-Section 'ⓐ 사전 권위 run'
    $prePort = Get-LiveNodePort
    $pre = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'pre'
    Write-Host "사전 verdict=$($pre.verdict)"

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

    Write-Section 'ⓒ assault 중 관측(CB open + 무결성 비전파)'
    $cbOpened = Wait-For { Test-CircuitOpen } -TimeoutSec ([int]$cfg['INJECT_DURATION_SEC']) -PollSec 1
    $injRun = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'inject'
    $injScans = Get-Scalar "SELECT count(*) FROM invariant_checks WHERE details->>'run_id'='$runId' AND details->>'window'='inject'"
    if ($injScans -lt 1) { throw 'inject 창 내 권위 run 미적재(공허 통과 방지 가드)' }
    $held = ((Get-Date).ToUniversalTime() - $t0).TotalSeconds
    $remain = [int]$cfg['INJECT_DURATION_SEC'] - [int]$held
    if ($remain -gt 0) { Start-Sleep -Seconds $remain }

    Write-Section 'ⓓ 해제 + CB 복귀'
    $noAssault = @{ latencyActive = $false; exceptionsActive = $false } | ConvertTo-Json
    Invoke-RestMethod -Uri "$cmBase/assaults" -Method Post -ContentType 'application/json' -Body $noAssault -TimeoutSec 10 | Out-Null
    Invoke-RestMethod -Uri "$cmBase/disable" -Method Post -TimeoutSec 10 | Out-Null
    $cbClosed = Wait-For { -not (Test-CircuitOpen) } -TimeoutSec ([int]$cfg['CB_RECOVERY_SEC']) -PollSec 1

    Write-Section 'ⓔ 사후 권위 run'
    $postPort = Get-LiveNodePort
    $post = Invoke-InvariantRun -Port $postPort -RunId $runId -Window 'post'

    $injClean = Test-ExperimentIntegrity $injRun
    $postClean = Test-ExperimentIntegrity $post
    $verdict = 'FAIL'
    if ($injClean -and $postClean -and $cbOpened -and $cbClosed) { $verdict = 'PASS' }

    Write-Verdict -ExperimentId 'E8' -Verdict $verdict -Metrics @{
        cbOpened          = [bool]$cbOpened
        cbRecovered       = [bool]$cbClosed
        injectAlwaysClean = $injClean
        postAlwaysClean   = $postClean
        injectTotalViolations = [long]$injRun.totalViolations
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
