# docs §3 E1 PG 지연/지터. 리더 pg 프록시에 latency/jitter 주입. 페일오버가 아니다.
# 판정은 정본 §3 E1 합격 기준의 1차·2차 구조를 그대로 따른다. 2차 오류율은 백프레셔 429가
# 정상 동작이라 경고 보고로만 낸다. 개입 금지 구간은 없다.
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
# 리스 행 잠금 축 임계. 키가 빠지면 [int]$null이 0이 되어 양성 축 하한이 공허해진다.
foreach ($k in 'LEASE_SAMPLE_GAP_MS', 'LEASE_SAMPLE_MIN', 'LEASE_ROWSHARE_MIN', 'RENEW_AGE_SAMPLE_GAP_MS',
               'RENEW_AGE_SAMPLE_MIN', 'RENEW_AGE_MAX_SEC', 'RENEW_OK_MIN', 'INJECT_P99_FACTOR_MIN') {
    if ([string]::IsNullOrWhiteSpace($cfg[$k])) { throw "설정 키 누락: $k ($ConfigPath)" }
}
$leaseGapMs = [int]$cfg['LEASE_SAMPLE_GAP_MS']
$leaseSampleMin = [int]$cfg['LEASE_SAMPLE_MIN']
$rowShareMin = [int]$cfg['LEASE_ROWSHARE_MIN']
$ageGapMs = [int]$cfg['RENEW_AGE_SAMPLE_GAP_MS']
$ageSampleMin = [int]$cfg['RENEW_AGE_SAMPLE_MIN']
$ageMaxSec = [double]$cfg['RENEW_AGE_MAX_SEC']
$renewOkMin = [int]$cfg['RENEW_OK_MIN']
$p99FactorMin = [double]$cfg['INJECT_P99_FACTOR_MIN']

function Get-TradeCount { [long](Get-Scalar "SELECT count(*) FROM trades" -Required) }

# 리스 갱신 결과 카운터(MeteredLeaderLeaseStore의 outcome 태그). 시계열이 하나라도 없으면 $null이다.
# 스크레이프 도달 실패는 -Required라 throw한다. 호출부가 두 경우를 가른다.
function Get-RenewCounts {
    param([int] $Port)
    $counts = @{}
    foreach ($outcome in 'ok', 'rejected', 'error') {
        $pattern = 'exchange_leader_lease_renew_total\{[^}]*outcome="' + $outcome + '"[^}]*\}\s+([0-9.eE+-]+)'
        $raw = Get-AppMetric -Port $Port -Pattern $pattern -Required
        if ($null -eq $raw) { return $null }
        $counts[$outcome] = [double]$raw
    }
    return $counts
}

Write-Section "E1(PG 지연) 시작 runId=$runId proxy=$proxy port=$port"
$samplerStop = [hashtable]::Synchronized(@{ Stop = $false })
$samplerJobs = @()
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
    # 갱신 카운터 기저. 주입 전에 못 읽으면 갱신 rejected·error 증가 0 축을 판정할 수 없다.
    $renewBase = Get-RenewCounts -Port $port
    if ($null -eq $renewBase) { throw '리더 스크레이프에 exchange_leader_lease_renew_total 시계열이 없다. 갱신 축을 판정할 수 없다.' }
    # V41 잠금 모드(키 열 인덱스와 FOR KEY SHARE 함수)가 깔린 스택인지 본다. 인덱스가 없어도 갱신은 막히지 않아
    # 이 실험은 PASS 할 수 있고, 그 스택은 승격이 쓰기를 기다리지 않는 stale write 구멍을 안고 있다(ADR 0026).
    # 인덱스는 존재가 아니라 키 열이 되는 모양(유일, 부분·식 없음, 키 열 셋)으로 본다.
    $fencingKeyIndex = [long](Get-Scalar "SELECT count(*) FROM pg_index WHERE indexrelid = to_regclass('uq_leader_lease_fencing_key') AND indisunique AND indisvalid AND indpred IS NULL AND indexprs IS NULL AND indnkeyatts = 3" -Required)
    # 주석이 아니라 잠금 절 자체를 찾는다. 함수 본문 주석에도 같은 낱말이 있다.
    $lockFunctionKeyShare = [long](Get-Scalar "SELECT count(*) FROM pg_proc WHERE proname = 'assert_and_lock_leader' AND prosrc ~ 'resource = ''matching-engine''\s+FOR KEY SHARE;'" -Required)
    $fixDeployed = ($fencingKeyIndex -eq 1) -and ($lockFunctionKeyShare -eq 1)
    # 리스 보관소가 dynamodb 면 갱신이 PostgreSQL 을 타지 않아 갱신 잠금 대기 축이 늘 0 으로 공허해진다.
    $envDump = & cmd /c "docker inspect -f ""{{range .Config.Env}}{{println .}}{{end}}"" $leaderContainer 2>nul"
    $providerLine = @($envDump | Where-Object { $_ -like 'EXCHANGE_LEADER_LEASE_PROVIDER=*' }) | Select-Object -First 1
    $leaseStoreProvider = if ($providerLine) { $providerLine.Substring('EXCHANGE_LEADER_LEASE_PROVIDER='.Length).Trim() } else { $null }
    $stackPreconditionOk = $fixDeployed -and ($leaseStoreProvider -eq 'postgres')
    Write-Host "스택 전제: 키 열 인덱스 $fencingKeyIndex, FOR KEY SHARE 함수 $lockFunctionKeyShare, 리스 보관소 $leaseStoreProvider"

    Write-Section "ⓑ 주입: latency=$($cfg['TOXIC_LATENCY_MS'])/jitter=$($cfg['TOXIC_JITTER_MS']) on $proxy"
    Add-Toxic -Proxy $proxy -Name 'e1-latency' -Type 'latency' `
        -Attributes @{ latency = [int]$cfg['TOXIC_LATENCY_MS']; jitter = [int]$cfg['TOXIC_JITTER_MS'] }
    $t0 = (Get-Date).ToUniversalTime()
    Write-Host "T0=$($t0.ToString('o'))"
    # 리스 행 잠금 표본. 창 안의 권위 run과 지연 표본이 수십 초씩 막으므로 별도 스레드로 창 전체를 덮는다.
    # chaos-pg 직조회라 지연이 걸린 리더 프록시를 타지 않는다. 한 왕복에 두 표본을 담아 주기를 지킨다.
    $libPath = Join-Path $PSScriptRoot 'lib.ps1'
    $leaseSql = "SELECT (SELECT count(*) FROM pg_locks WHERE relation = 'leader_lease'::regclass " +
        "AND mode = 'RowShareLock' AND granted) || '|' || coalesce((SELECT string_agg(pid || ':' || " +
        "coalesce(wait_event, '') || ':' || round(extract(epoch FROM clock_timestamp() - query_start)::numeric, 3) " +
        "|| ':' || pg_blocking_pids(pid)::text, ';') FROM pg_stat_activity WHERE query ILIKE '%renew_leader_lease%' " +
        "AND state = 'active' AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()), '')"
    $leaseSampler = Start-ThreadJob -ArgumentList $libPath, $leaseSql, $leaseGapMs, $samplerStop -ScriptBlock {
        param($LibPath, $Sql, $GapMs, $Flag)
        . $LibPath
        $samples = 0; $failed = 0; $waitRows = 0; $rowShareMax = 0
        $waits = New-Object System.Collections.Generic.List[string]
        while (-not $Flag.Stop) {
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            # 조회 실패는 표본에서 빼고 따로 센다. 표본 수 하한이 관측 공백을 FAIL로 만든다.
            $raw = ''
            try { $raw = Invoke-Psql $Sql } catch { $raw = '' }
            $parts = @()
            if ($raw) { $parts = $raw.Split('|', 2) }
            if ($parts.Count -eq 2 -and $parts[0] -match '^\d+$') {
                $samples++
                $rowShareMax = [math]::Max($rowShareMax, [int]$parts[0])
                if ($parts[1]) {
                    foreach ($w in $parts[1].Split(';')) { $waitRows++; $waits.Add("$(Now-Iso)|$w") }
                }
            } else { $failed++ }
            $rest = $GapMs - [int]$sw.ElapsedMilliseconds
            if ($rest -gt 0) { Start-Sleep -Milliseconds $rest }
        }
        [pscustomobject]@{ Samples = $samples; Failed = $failed; WaitRows = $waitRows; RowShareMax = $rowShareMax; Waits = $waits.ToArray() }
    }
    # 갱신 경과 게이지는 리더 HTTP로 긁는다. 스크레이프가 늦어도 위 잠금 표본 주기를 밀지 않게 스레드를 나눈다.
    $ageSampler = Start-ThreadJob -ArgumentList $libPath, $port, $ageGapMs, $samplerStop -ScriptBlock {
        param($LibPath, $Port, $GapMs, $Flag)
        . $LibPath
        $ages = New-Object System.Collections.Generic.List[double]
        $missed = 0
        while (-not $Flag.Stop) {
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            $raw = Get-AppMetric -Port $Port -Pattern 'exchange_leader_lease_last_renew_age_seconds(?:\{[^}]*\})?\s+([0-9.eE+-]+)'
            # -1은 한 번도 갱신하지 않았다는 값이라 경과 표본이 아니다.
            if ($null -ne $raw -and [double]$raw -ge 0) { $ages.Add([double]$raw) } else { $missed++ }
            $rest = $GapMs - [int]$sw.ElapsedMilliseconds
            if ($rest -gt 0) { Start-Sleep -Milliseconds $rest }
        }
        [pscustomobject]@{ Ages = $ages.ToArray(); Missed = $missed }
    }
    $samplerJobs = @($leaseSampler, $ageSampler)
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
    $injScans = Get-Scalar "SELECT count(*) FROM invariant_checks WHERE details->>'run_id'='$runId' AND details->>'window'='inject'" -Required
    if ($injScans -lt 1) { throw '지연 창 내 권위 run 미적재(공허 통과 방지 가드)' }
    $inj = Measure-Latency -Port $port -Count $sampleN -GapMs $gapMs
    $injHealth.Add((Get-ContainerHealth $leaderContainer))
    $injP99 = Get-Percentile -Values $inj.Samples -P 99
    $errRate = if ($inj.Total -gt 0) { [double]$inj.Errors / $inj.Total } else { 0 }
    Write-Host ("지연 중 p99={0}ms 오류율={1} 무결성위반={2}" -f ([math]::Round($injP99, 1)), $errRate, $injRun.totalViolations)
    # 잔여 창 소진. 단순 대기 대신 헬스를 표본화해 1차 게이트(주입 중 unhealthy 0)의 증거를
    # 창 전체에서 모은다.
    $elapsed = ((Get-Date).ToUniversalTime() - $t0).TotalSeconds
    $remain = [int]$cfg['INJECT_DURATION_SEC'] - [int]$elapsed
    while ($remain -gt 0) {
        $step = [math]::Min(5, $remain)
        Start-Sleep -Seconds $step
        $remain -= $step
        $injHealth.Add((Get-ContainerHealth $leaderContainer))
    }
    $samplerStop.Stop = $true
    # 창 끝 카운터는 해제 전에 읽는다. 실패는 throw하지 않고 델타를 null로 둬 갱신 축 불합격으로 떨어뜨린다.
    $renewEnd = $null; $renewScrapeOk = $true
    try { $renewEnd = Get-RenewCounts -Port $port }
    catch {
        $renewScrapeOk = $false
        Write-Host "주입 창 끝 리더 스크레이프 실패($($_.Exception.Message)). 갱신 축 판정 불가로 계상" -ForegroundColor Red
    }
    $null = Wait-Job -Job $samplerJobs -Timeout 30
    # 표본 스레드가 예외로 끝났으면 Receive-Job 이 다시 던진다. 판정 파일이 남도록 잡고 결과를 비운다.
    $leaseRes = $null; $ageRes = $null
    try { $leaseRes = @(Receive-Job -Job $leaseSampler) | Select-Object -Last 1 }
    catch { Write-Host "리스 잠금 표본 스레드 실패($($_.Exception.Message)). 표본 0으로 계상" -ForegroundColor Red }
    try { $ageRes = @(Receive-Job -Job $ageSampler) | Select-Object -Last 1 }
    catch { Write-Host "갱신 경과 표본 스레드 실패($($_.Exception.Message)). 표본 0으로 계상" -ForegroundColor Red }

    # 표본 스레드가 끝나지 않았거나 실패했으면 결과가 비어 아래 null 검사와 표본 수 하한에서 불합격이 된다.
    $leaseSampleCount = [int]$leaseRes.Samples
    $leaseSampleFailed = $leaseRes.Failed
    $renewLockWaitSamples = $leaseRes.WaitRows
    $leaseRowShareMax = $leaseRes.RowShareMax
    $renewLockWaits = @()
    if ($leaseRes) { $renewLockWaits = $leaseRes.Waits }
    $renewAges = @()
    if ($ageRes) { $renewAges = @($ageRes.Ages) }
    $renewAgeSampleCount = $renewAges.Count
    $lastRenewAgeMaxSec = $null
    if ($renewAgeSampleCount -gt 0) {
        $lastRenewAgeMaxSec = [math]::Round(($renewAges | Measure-Object -Maximum).Maximum, 3)
    }
    $renewOkDeltaInject = $null; $renewRejectedDelta = $null; $renewErrorDelta = $null
    if ($null -ne $renewEnd) {
        $renewOkDeltaInject = $renewEnd['ok'] - $renewBase['ok']
        $renewRejectedDelta = $renewEnd['rejected'] - $renewBase['rejected']
        $renewErrorDelta = $renewEnd['error'] - $renewBase['error']
    }
    Write-Host ("리스 표본 {0}(실패 {1}) 갱신 잠금 대기 {2} RowShareLock 최대 {3} last_renew_age 최대 {4}s(표본 {5}) 갱신 증분 ok={6} rejected={7} error={8}" -f `
        $leaseSampleCount, $leaseSampleFailed, $renewLockWaitSamples, $leaseRowShareMax, $lastRenewAgeMaxSec,
        $renewAgeSampleCount, $renewOkDeltaInject, $renewRejectedDelta, $renewErrorDelta)
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

    # 리스 행 잠금 축(DB 설계 v2.43). 갱신이 쓰기 트랜잭션 잠금을 기다리지 않았고, 한 번도 늦지 않았고,
    # 거부나 왕복 실패가 없어야 한다. null은 관측 실패다. PowerShell에서 $null -lt 4.0은 참이라 먼저 막는다.
    $leaseGateOk = ($null -ne $renewLockWaitSamples -and $renewLockWaitSamples -eq 0) -and `
                   ($null -ne $lastRenewAgeMaxSec -and $lastRenewAgeMaxSec -lt $ageMaxSec) -and `
                   ($null -ne $renewRejectedDelta -and $renewRejectedDelta -eq 0) -and `
                   ($null -ne $renewErrorDelta -and $renewErrorDelta -eq 0)

    # 정본 §3 E1 합격 기준 1차. 컨테이너 헬스는 단독으로는 둔감하다(3s x 30회 연속 실패라야
    # unhealthy, start_period 40s는 실패해도 starting). $statusRespOk와 체결 흐름이 함께 있어야
    # 좀비 구간이 잡히므로 셋을 모두 1차에 둔다.
    $primaryOk = $injClean -and $postClean -and $epochStable -and $standdownOk -and `
                 $healthOk -and $tradesFlowing -and $recovered -and $statusRespOk -and $leaseGateOk

    # 양성 축(CLAUDE.md §22). 표본이 실제로 돌았고, 쓰기 잠금이 겹쳤고, 갱신이 돌았고, 지연이 PG 경로에
    # 닿은 회차에서만 위의 0이 판정이다. 하나라도 미달이면 판정 대상이 없는 회차라 PASS를 내지 않는다.
    $p99Reached = ($null -ne $baseP99 -and $null -ne $injP99) -and ($injP99 -ge $p99FactorMin * $baseP99)
    $positiveOk = ($leaseSampleCount -ge $leaseSampleMin) -and `
                  ($null -ne $leaseRowShareMax -and $leaseRowShareMax -ge $rowShareMin) -and `
                  ($renewAgeSampleCount -ge $ageSampleMin) -and `
                  ($null -ne $renewOkDeltaInject -and $renewOkDeltaInject -ge $renewOkMin) -and `
                  $p99Reached -and $stackPreconditionOk
    $verdict = 'FAIL'
    if ($primaryOk -and $positiveOk) { $verdict = 'PASS' }

    Write-Verdict -ExperimentId 'E1' -RunId $runId -Verdict $verdict -Metrics @{
        fencingKeyIndex        = $fencingKeyIndex
        lockFunctionKeyShare   = $lockFunctionKeyShare
        fixDeployed            = $fixDeployed
        leaseStoreProvider     = $leaseStoreProvider
        stackPreconditionOk    = $stackPreconditionOk
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
        renewLockWaitSamples   = $renewLockWaitSamples
        renewLockWaits         = $renewLockWaits
        leaseSampleCount       = $leaseSampleCount
        leaseSampleFailed      = $leaseSampleFailed
        leaseSampleMin         = $leaseSampleMin
        leaseRowShareMax       = $leaseRowShareMax
        leaseRowShareMin       = $rowShareMin
        lastRenewAgeMaxSec     = $lastRenewAgeMaxSec
        lastRenewAgeLimitSec   = $ageMaxSec
        renewAgeSampleCount    = $renewAgeSampleCount
        renewAgeSampleMissed   = $ageRes.Missed
        renewAgeSampleMin      = $ageSampleMin
        renewOkDeltaInject     = $renewOkDeltaInject
        renewOkMin             = $renewOkMin
        renewRejectedDelta     = $renewRejectedDelta
        renewErrorDelta        = $renewErrorDelta
        renewScrapeOk          = $renewScrapeOk
        injectP99FactorMin     = $p99FactorMin
        injectP99Reached       = $p99Reached
        leaseGateOk            = $leaseGateOk
        positiveOk             = $positiveOk
    }
}
finally {
    Write-Section '종료 훅: 토식 해제'
    Remove-AllToxics -Proxy $proxy
    # 예외로 창 중간에 빠져도 표본 스레드가 chaos-pg를 계속 두드리지 않게 멈춘다.
    $samplerStop.Stop = $true
    if ($samplerJobs.Count -gt 0) { Remove-Job -Job $samplerJobs -Force }
}

if ($verdict -ne 'PASS') { Write-Host 'E1(PG 지연) FAIL.' -ForegroundColor Red; exit 1 }
Write-Host 'E1(PG 지연) PASS.' -ForegroundColor Green
# 명시 종료코드. 앞선 네이티브 호출($LASTEXITCODE)이 프로세스 종료코드로 새면 PASS가 exit 1이 된다.
exit 0
