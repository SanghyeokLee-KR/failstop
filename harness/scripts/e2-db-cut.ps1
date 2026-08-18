# E2 DB 단절. 리더의 pg 프록시에 blackhole 주입 → 리스 갱신 실패 → 강등 → failover → 복구.
# 판정: 유실 0(gapless+물질화) · 중복 0 · 자산합 0 · RTO < 30s. 종료 훅에서 토식 무조건 해제.
param([string] $ConfigPath = (Join-Path $PSScriptRoot '..\config\e2.env'))

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
$cfg = Read-EnvFile $ConfigPath
$runId = "E2-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
$proxy = $cfg['LEADER_PROXY']

function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades" -Required) }

Write-Section "E2 시작 runId=$runId"
try {
    # 자기 부하를 명시로 세운다. 생략하면 앞선 실험이 남긴 ops를 이월받는다(E6 직후면 400,
    # 드레인 직후면 정지 상태). 부하가 무엇인지 모르는 채 주입하면 판정 조건이 실행마다 달라진다.
    if ($cfg['BASE_OPS']) { Set-BotOps ([int]$cfg['BASE_OPS']) }
    Start-Sleep -Seconds ([int]$cfg['WARMUP_SEC'])

    # 실 리더의 pg 프록시를 차단 대상 삼는다. config 기본값(pg-api1)은 갓 세운 스택에서만 맞고,
    # 앞선 실험이 리더를 api-2로 남겼으면 팔로워의 DB 경로를 끊게 된다. 그때는 리스 상실이 없어
    # 승격도 없고 확정 FAIL이 나는데 그 FAIL은 판정 결함이다.
    $detected = Get-LeaderContainer
    if (-not $detected) {
        throw '리더 감지 실패. 실 리더를 특정하지 못한 채 차단하면 판정이 무의미하다. leader_lease holder와 컨테이너 로그를 대조해 확인한 뒤 재실행한다.'
    }
    $proxy = if ($detected -eq 'chaos-api-1') { 'pg-api1' } else { 'pg-api2' }
    Write-Host "리더 감지: $detected -> 차단 프록시 $proxy"
    $standbyPort = if ($detected -eq 'chaos-api-1') { 28080 } else { 18080 }
    # 누적이 아니라 증분으로 본다. 누적이면 앞선 실험이 남긴 체결만으로 즉시 통과해, 봇이 죽었거나
    # 정지된 상태에서도 주입한다. 부하 없이 주입하면 승격 후 재개를 관측할 수 없고 그 FAIL은
    # 코드 결함이 아니라 판정 결함이다(E5와 같은 술어).
    $tradesBase = Get-TradeCount
    $ok = Wait-For { (Get-TradeCount) - $tradesBase -ge [long]$cfg['MIN_TRADES_BEFORE_INJECT'] } -TimeoutSec 120 -PollSec 1
    if (-not $ok) { throw "체결 증분 미달(기준 $($cfg['MIN_TRADES_BEFORE_INJECT'])건). 봇 부하가 실리지 않았다. chaos-bots 상태와 BASE_OPS를 확인한다." }

    Write-Section 'ⓐ 사전 권위 run'
    $prePort = Get-LiveNodePort
    Reset-LoadtestAcks -Port $prePort
    $s0 = Get-HarnessStatus -Port $prePort
    $oldEpoch = [long]$s0.epoch; $oldHolder = $s0.holder
    $leaseUntilBefore = ([datetimeoffset]$s0.leaseUntil).UtcDateTime
    $pre = Invoke-InvariantRun -Port $prePort -RunId $runId -Window 'pre'
    Write-Host "사전 verdict=$($pre.verdict) old_epoch=$oldEpoch"

    # 회복 가능 반납은 프로세스 수명 누계 카운터다. 사후 절대값으로 게이팅하면 앞선 실험이 남긴
    # 반납까지 세어 이번 페일오버의 반납 횟수를 과대 계상한다. 주입 전 기저를 잡아 델타로 판정한다.
    # 여기서 -Required인 이유: 관측 경로가 끊긴 채로 주입하면 이 축을 판정할 수 없다. 주입 전에 멈춘다.
    $sdPattern = 'exchange_leader_recoverable_standdown_total(?:\{[^}]*\})?\s+([0-9.eE+-]+)'
    $sdBaseRaw = Get-AppMetric -Port $standbyPort -Pattern $sdPattern -Required
    $sdBase = 0.0; if ($null -ne $sdBaseRaw) { $sdBase = [double]$sdBaseRaw }
    $sdBaseDisp = 'n/a(시계열 부재)'; if ($null -ne $sdBaseRaw) { $sdBaseDisp = $sdBaseRaw }
    Write-Host "회복 가능 반납 기저(승격 예정 노드): $sdBaseDisp"

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
        -ObserveSec ([int]$cfg['POST_OBSERVE_SEC']) -RtoTarget ([int]$cfg['RTO_TARGET_SEC']) `
        -StandbyPort $standbyPort -RestoreOps ([int]$cfg['BASE_OPS'])

    # 승격 안정성 게이트. E2 라이브에서 두 노드가 123ms 만에 모두 강등해 클러스터가 리더 없이
    # 영구 정지한 적이 있다(DB 설계 v2.23 §5.4). Complete-FailoverExperiment의 verdict는 승격
    # 시점만 보므로 그 직후 재강등을 잡지 못한다. 승격한 리더가 창 내내 리스를 유지하는지 본다.
    # 회복 가능 반납으로 epoch가 더 오르는 것은 정상이므로(같은 절) 단조 증가만 요구하고,
    # 리스가 죽어 있는 표본이 하나라도 있으면 실패로 본다(리더 없는 상태 = 이 실험이 막으려는 것).
    Write-Section 'ⓔ 승격 안정성 관측'
    $stableSec = 30; $stableGapSec = 2
    # boolean을 문자열로 연결하면 psql 표시 형식 t/f가 아니라 true/false가 나온다. 명시적으로
    # 't'/'f'를 만들어 표시 형식 가정에 의존하지 않게 한다(실측: ||는 '2|true'를 낸다).
    $leaseSql = "SELECT epoch||'|'||CASE WHEN lease_until > now() THEN 't' ELSE 'f' END " +
        "FROM leader_lease WHERE resource='matching-engine'"
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

    # 회복 가능 반납 축(CICD §5-2: E2 페일오버는 0~1). DB v2.24가 신설했으나 판정에 배선되지
    # 않아 수동 판독 지침으로만 남아 있었다. 2회 이상이면 승격과 반납을 되풀이한 것이고, 그때는
    # 마지막 승격만 살아 있어도 위 안정성 게이트를 통과하므로 여기서 따로 잡아야 한다.
    $sdScrapeOk = $true
    $sdAfterRaw = $null
    try { $sdAfterRaw = Get-AppMetric -Port $standbyPort -Pattern $sdPattern -Required }
    catch {
        $sdScrapeOk = $false
        Write-Host "회복 가능 반납: 스크레이프 실패($($_.Exception.Message)). 관측 경로 단절은 판정 제외 사유가 아니라 실패다" -ForegroundColor Red
        $verdict = 'FAIL'
    }
    if ($sdScrapeOk) {
        if ($null -eq $sdAfterRaw) {
            Write-Host '회복 가능 반납: n/a(스크레이프는 성공, 시계열 부재). 서버 미등록 축이라 판정에서 제외한다.'
        } else {
            $sdDelta = [double]$sdAfterRaw - $sdBase
            # 승격 노드가 재기동되면 카운터가 0부터 다시 시작해 델타가 음수가 된다. 그때는 사후
            # 절대값 자체가 이번 사건 이후의 반납 횟수다.
            if ($sdDelta -lt 0) {
                Write-Host "회복 가능 반납: 델타 음수(기저 $sdBase -> 사후 $sdAfterRaw). 노드 재기동으로 보고 사후 절대값으로 클램프" -ForegroundColor Yellow
                $sdDelta = [double]$sdAfterRaw
            }
            Write-Host "회복 가능 반납 델타(이번 페일오버): $sdDelta (기저 $sdBase, 사후 $sdAfterRaw, 정상 상한 1)"
            if ($sdDelta -gt 1) { $verdict = 'FAIL' }
        }
    }
}
finally {
    # 종료 훅: 토식 무조건 해제(§3 공통 전제).
    Write-Section '종료 훅: 토식 해제'
    Remove-AllToxics -Proxy $proxy
}

if ($verdict -ne 'PASS') { exit 1 }
Write-Host 'E2 PASS.' -ForegroundColor Green
# 명시 종료코드. 앞선 네이티브 호출($LASTEXITCODE)이 프로세스 종료코드로 새면 PASS가 exit 1이 된다.
exit 0
