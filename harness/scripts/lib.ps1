# 카오스 실험 공용 유틸. 각 실험 스크립트가 dot-source 한다.
# 포트·컨테이너명은 docker-compose.chaos.yml과 1:1. 판정은 전부 SQL 0행/RTO 임계로 기계화한다.

$ErrorActionPreference = 'Stop'

$Global:CHAOS = @{
    ComposeFile = Join-Path $PSScriptRoot '..\docker-compose.chaos.yml'
    EnvFile     = Join-Path $PSScriptRoot '..\.env.chaos'
    Pg          = 'chaos-pg'
    Api1Port    = 18080
    Api2Port    = 28080
    LbPort      = 58080
    # 기본 58474. 호스트가 이 포트를 WinNAT 동적 예외로 예약해 게시 불가일 때(Windows) CHAOS_TOXI_URL로
    # 재지정(예: 포트 리맵 오버라이드). env 미설정 시 기존 동작 불변.
    ToxiUrl     = $(if ($env:CHAOS_TOXI_URL) { $env:CHAOS_TOXI_URL } else { 'http://localhost:58474' })
    PgUser      = 'exchange'
    PgDb        = 'exchange'
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)] $Args)
    & docker compose -f $Global:CHAOS.ComposeFile --env-file $Global:CHAOS.EnvFile @Args
}

# 리스 TTL(초). §5-1 T1 = 구 lease_until − 리스 TTL(마지막 heartbeat 역산). 카오스 스택은
# CHAOS_LEASE_TTL로 가속(6s), 값 부재 시 DB§5.4 기본 10s. downtime=T2-T1 산정에 쓴다.
function Get-LeaseTtlSec {
    try {
        if (Test-Path $Global:CHAOS.EnvFile) {
            $raw = (Read-EnvFile $Global:CHAOS.EnvFile)['CHAOS_LEASE_TTL']
            if ($raw -and ($raw -match '([0-9]+(\.[0-9]+)?)')) { return [double] $Matches[1] }
        }
    } catch { }
    return 10.0
}

function Read-EnvFile {
    param([string] $Path)
    $map = @{}
    # -Encoding UTF8 명시: config는 BOM 없는 UTF-8이다. 미지정 시 Windows PowerShell 5.1이 ANSI(cp949)로
    # 읽어 한글 인라인 주석의 더블바이트가 줄 경계를 삼켜 특정 키가 통째로 누락된다(pwsh는 무관).
    Get-Content $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
            $i = $line.IndexOf('=')
            $key = $line.Substring(0, $i).Trim()
            $val = $line.Substring($i + 1)
            # 인라인 주석 제거(값에 # 미포함 전제, config/*.env 스타일).
            $h = $val.IndexOf('#')
            if ($h -ge 0) { $val = $val.Substring(0, $h) }
            $map[$key] = $val.Trim()
        }
    }
    return $map
}

# DB 직조회(권위 판정). -t -A로 헤더·정렬 제거한 raw 값만 반환한다.
function Invoke-Psql {
    param([string] $Sql)
    $out = & docker exec $Global:CHAOS.Pg psql -U $Global:CHAOS.PgUser -d $Global:CHAOS.PgDb -t -A -c $Sql 2>$null
    return ($out | Out-String).Trim()
}

function Get-Scalar {
    param([string] $Sql)
    $v = Invoke-Psql $Sql
    if ([string]::IsNullOrWhiteSpace($v)) { return 0 }
    return [decimal] $v
}

function Get-LiveNodePort {
    foreach ($p in @($Global:CHAOS.Api1Port, $Global:CHAOS.Api2Port)) {
        try {
            Invoke-RestMethod -Uri "http://localhost:$p/harness/v1/status" -TimeoutSec 3 | Out-Null
            return $p
        } catch { }
    }
    throw '살아있는 api 노드 없음(양 노드 응답 실패)'
}

function Get-HarnessStatus {
    param([int] $Port)
    return Invoke-RestMethod -Uri "http://localhost:$Port/harness/v1/status" -TimeoutSec 5
}

function Invoke-InvariantRun {
    param([int] $Port, [string] $RunId, [string] $Window)
    $uri = "http://localhost:$Port/harness/v1/invariants/run?runId=$RunId&window=$Window"
    return Invoke-RestMethod -Uri $uri -Method Post -TimeoutSec 60
}

function Reset-LoadtestAcks {
    param([int] $Port)
    Invoke-RestMethod -Uri "http://localhost:$Port/harness/v1/loadtest-acks/reset" -Method Post -TimeoutSec 10 | Out-Null
}

function Record-Failover {
    param([int] $Port, [hashtable] $Body)
    $json = $Body | ConvertTo-Json -Depth 6
    return Invoke-RestMethod -Uri "http://localhost:$Port/harness/v1/failover" -Method Post `
        -ContentType 'application/json' -Body $json -TimeoutSec 10
}

# Toxiproxy 2.12.0은 DNS 리바인딩 방어로 User-Agent에 'Mozilla'가 있으면 거부(User agent not allowed).
# PowerShell Invoke-RestMethod 기본 UA가 Mozilla라 전부 비브라우저 UA로 지정한다(5.1/7 공통).
$Global:CHAOS_UA = 'chaos-harness'

function Add-Toxic {
    param([string] $Proxy, [string] $Name, [string] $Type, [hashtable] $Attributes)
    $body = @{ name = $Name; type = $Type; stream = 'downstream'; attributes = $Attributes } | ConvertTo-Json
    Invoke-RestMethod -Uri "$($Global:CHAOS.ToxiUrl)/proxies/$Proxy/toxics" -Method Post `
        -ContentType 'application/json' -Body $body -UserAgent $Global:CHAOS_UA -TimeoutSec 10 | Out-Null
}

function Remove-AllToxics {
    param([string] $Proxy)
    try {
        $toxics = Invoke-RestMethod -Uri "$($Global:CHAOS.ToxiUrl)/proxies/$Proxy/toxics" -UserAgent $Global:CHAOS_UA -TimeoutSec 10
        foreach ($t in $toxics) {
            Invoke-RestMethod -Uri "$($Global:CHAOS.ToxiUrl)/proxies/$Proxy/toxics/$($t.name)" -Method Delete -UserAgent $Global:CHAOS_UA | Out-Null
        }
    } catch { }
}

function Write-Section {
    param([string] $Text)
    Write-Host ''
    Write-Host "== $Text ==" -ForegroundColor Cyan
}

function Now-Iso { return (Get-Date).ToUniversalTime().ToString('o') }

# 조건 충족까지 폴링. 반환: 충족 시각(UTC) 또는 $null(타임아웃).
function Wait-For {
    param([scriptblock] $Condition, [int] $TimeoutSec, [double] $PollSec = 0.5)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        # UTC로 반환한다. DB engine_ts(UTC)와 뺄셈할 때 로컬 시각이면 오프셋만큼 오차가 난다.
        if (& $Condition) { return (Get-Date).ToUniversalTime() }
        Start-Sleep -Milliseconds ([int]($PollSec * 1000))
    }
    return $null
}

# 주입(T0) 이후 승격·체결 재개 관찰 → 사후 권위 run → RTO/RPO 판정 → failover_runs 기록.
# E2·E3가 공용으로 쓴다(E1은 상세 참조본이라 인라인 유지). 개입 금지 구간이므로 관찰만 한다.
function Complete-FailoverExperiment {
    param(
        [string] $ExperimentId, [string] $RunId,
        [long] $OldEpoch, [string] $OldHolder, [datetime] $LeaseUntilBefore,
        [long] $TradesAtInject, [datetime] $T0,
        [int] $ObserveSec, [int] $RtoTarget
    )
    $standbyPort = $Global:CHAOS.Api2Port

    Write-Section 'ⓒ 관찰(개입 금지)'
    $t2 = Wait-For {
        try { $s = Get-HarnessStatus -Port $standbyPort; ($s.epoch -gt $OldEpoch) -and $s.leaderAlive }
        catch { $false }
    } -TimeoutSec $ObserveSec -PollSec 0.5
    $statusAfter = Get-HarnessStatus -Port (Get-LiveNodePort)
    $newEpoch = [long]$statusAfter.epoch
    $newHolder = $statusAfter.holder

    # T5 = 페일오버 후 최초 신규 TRADE의 engine_ts(§5-1 정의). created_at은 DB now()가 아니라
    # 엔진 처리 시각이라 커밋 가시화 지연과 무관한 재개 시점을 가리킨다. T0 이후 신 epoch 필터.
    $t0Iso = $T0.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fff') + 'Z'
    $firstTradeSql = "SELECT min(created_at) AT TIME ZONE 'UTC' " +
        "FROM event_journal WHERE event_type='TRADE' AND fencing_epoch=$newEpoch AND created_at > '$t0Iso'"
    $obsResume = Wait-For {
        -not [string]::IsNullOrWhiteSpace((Invoke-Psql $firstTradeSql))
    } -TimeoutSec $ObserveSec -PollSec 0.5
    $firstTradeRaw = Invoke-Psql $firstTradeSql
    $t5 = $null; $firstTradeTs = $null
    if ($firstTradeRaw) {
        $firstTradeTs = $firstTradeRaw.Replace(' ', 'T') + 'Z'
        $t5 = ([datetimeoffset]$firstTradeTs).UtcDateTime
    }

    Write-Section 'ⓓ 사후 권위 run'
    Start-Sleep -Seconds 3
    $postPort = Get-LiveNodePort
    $post = Invoke-InvariantRun -Port $postPort -RunId $RunId -Window 'post'

    $rows = @{}
    foreach ($c in $post.checks) { $rows[$c.checkName] = [long]$c.violatedRows }
    $assetSum = $rows['C01_ASSET_CONSERVATION']
    $cmdGap = $rows['C05_CMD_GAPLESS']; $evtGap = $rows['C06_EVT_GAPLESS']
    $dupOrder = $rows['C07_DUP_ORDER'] + $rows['C07P_DUP_ORDER_JOURNAL']
    $dupFill = $rows['C08_DUP_FILL']; $ordersLost = $rows['C13A_ORDER_MATERIALIZED']
    $rpo = $cmdGap + $evtGap + $ordersLost

    $rto = $null; if ($t5) { $rto = ($t5 - $T0).TotalSeconds }
    # §5-1 정의: downtime(exchange_failover_downtime_seconds)=T2-T1. T1은 마지막 heartbeat이며
    # 구 lease_until − 리스 TTL로 역산한다. 사전 스냅샷 lease_until을 T1로 직접 쓰면 TTL만큼 편향된다.
    $leaseTtlSec = Get-LeaseTtlSec
    $downtime = $null; if ($t2) { $downtime = ($t2 - $LeaseUntilBefore).TotalSeconds + $leaseTtlSec }
    $rtoRounded = $null; if ($null -ne $rto) { $rtoRounded = [math]::Round($rto, 3) }
    $downtimeRounded = $null; if ($null -ne $downtime) { $downtimeRounded = [math]::Round($downtime, 3) }
    $rtoDisplay = 'n/a'; if ($null -ne $rtoRounded) { $rtoDisplay = $rtoRounded }
    $firstTradeVal = $null; if ($firstTradeTs) { $firstTradeVal = $firstTradeTs }

    $verdict = 'FAIL'
    if ($post.criticalClean -and $t5 -and ($newEpoch -eq $OldEpoch + 1) -and ($null -ne $rto) -and ($rto -lt $RtoTarget)) {
        $verdict = 'PASS'
    }

    $tradesAfter = [long](Get-Scalar "SELECT count(*) FROM trades")
    $body = @{
        experimentId = $ExperimentId; oldLeader = $OldHolder; newLeader = $newHolder
        oldEpoch = $OldEpoch; newEpoch = $newEpoch; firstTradeTs = $firstTradeVal
        rtoSeconds = $rtoRounded; downtimeSeconds = $downtimeRounded
        rpoRecords = $rpo; verdict = $verdict
        details = @{ runId = $RunId; assetSum = "$assetSum"; dupOrder = "$dupOrder";
                     dupFill = "$dupFill"; cmdGap = "$cmdGap"; evtGap = "$evtGap";
                     tradesBefore = "$TradesAtInject"; tradesAfter = "$tradesAfter" }
    }
    $rec = Record-Failover -Port $postPort -Body $body

    Write-Host ''
    Write-Host "── $ExperimentId 증거 ─────────────────────" -ForegroundColor Green
    Write-Host ("verdict          : {0}" -f $verdict)
    Write-Host ("RTO(체결 재개)   : {0} s (목표 < {1})" -f $rtoDisplay, $RtoTarget)
    Write-Host ("epoch            : {0} -> {1}" -f $OldEpoch, $newEpoch)
    Write-Host ("자산합(C01)      : {0}" -f $assetSum)
    Write-Host ("중복 접수/체결   : {0} / {1}" -f $dupOrder, $dupFill)
    Write-Host ("저널 gap C05/C06 : {0} / {1}" -f $cmdGap, $evtGap)
    Write-Host ("주문 유실(C13a)  : {0}" -f $ordersLost)
    Write-Host ("failover_run_id  : {0}" -f $rec.failover_run_id)
    Write-Host '────────────────────────────────────────'
    Invoke-Psql "SELECT id, experiment_id, old_epoch, new_epoch, rto_seconds, rpo_records, verdict FROM harness.failover_runs ORDER BY id DESC LIMIT 3"
    return $verdict
}

# ── 확장 유틸(E1·E3·E5·E7·E8·E9 전용, 추가만). 레거시 E1~E3 경로는 위 함수만 쓴다. ──

# 엔드포인트 지연 표본(ms) + 실패 카운트. DB 경유 read를 반복 GET한다. E1은 리더 pg 프록시
# 지연 주입의 p99 상승·복귀를, 실패 표본은 오류율을 준다. status는 leader_lease를 읽어 DB를 탄다.
function Measure-Latency {
    param([int] $Port, [string] $Path = '/harness/v1/status', [int] $Count = 100, [int] $GapMs = 50)
    $samples = New-Object System.Collections.Generic.List[double]
    $errors = 0
    for ($i = 0; $i -lt $Count; $i++) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            Invoke-RestMethod -Uri "http://localhost:$Port$Path" -TimeoutSec 10 | Out-Null
            $sw.Stop(); $samples.Add($sw.Elapsed.TotalMilliseconds)
        } catch {
            $sw.Stop(); $errors++
        }
        if ($GapMs -gt 0) { Start-Sleep -Milliseconds $GapMs }
    }
    return [pscustomobject]@{ Samples = $samples.ToArray(); Errors = $errors; Total = $Count }
}

# nearest-rank 백분위수(ms). 표본 부재 시 $null. 정렬은 배열 강제(@)로 단일 표본 스칼라화 방지.
function Get-Percentile {
    param([double[]] $Values, [double] $P = 99)
    if (-not $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $rank = [int][math]::Ceiling(($P / 100.0) * $sorted.Count)
    if ($rank -lt 1) { $rank = 1 }
    if ($rank -gt $sorted.Count) { $rank = $sorted.Count }
    return [double] $sorted[$rank - 1]
}

# 컨테이너 실행/존재 여부. E3 redis 전제 검사, E5/E7 대상 확인용. 이름 정확 일치만 참.
function Test-ContainerRunning {
    param([string] $Name)
    $names = & docker ps --format '{{.Names}}' 2>$null
    return ($names -contains $Name)
}
function Test-ContainerExists {
    param([string] $Name)
    $names = & docker ps -a --format '{{.Names}}' 2>$null
    return ($names -contains $Name)
}

# toxiproxy에 특정 프록시가 배선돼 있는지. E3 redis 프록시 전제 검사용.
function Test-ToxiproxyProxy {
    param([string] $Name)
    try {
        $proxies = Invoke-RestMethod -Uri "$($Global:CHAOS.ToxiUrl)/proxies" -UserAgent $Global:CHAOS_UA -TimeoutSec 10
        return ($null -ne $proxies.$Name)
    } catch { return $false }
}

# 저널 워터마크. 주입 직후 max(seq)를 잡아 이후 구 epoch append(스테일 쓰기)를 시간 축으로 격리한다.
function Get-CommandWatermark { [long](Get-Scalar "SELECT COALESCE(max(seq),0) FROM command_journal") }
function Get-EventWatermark   { [long](Get-Scalar "SELECT COALESCE(max(event_seq),0) FROM event_journal") }

# command_journal에 명령 축 fencing 컬럼(fencing_token)이 있는지(실스키마 대조). 실DDL 정본:
# command_journal.fencing_token(= epoch 값), event_journal.fencing_epoch. 정본 §4-1 C22 주석은
# 이를 command_journal.fencing_epoch로 오기(§5-1 T5는 fencing_token으로 정확). 실스키마가 권위다.
function Test-CommandFencingColumn {
    $v = Invoke-Psql "SELECT count(*) FROM information_schema.columns WHERE table_name='command_journal' AND column_name='fencing_token'"
    return ([decimal]$v -gt 0)
}

# 스테일 쓰기 수: 워터마크 이후 구 epoch로 append된 저널 행. split-brain 반증(E5)·좀비 리더(E7)의
# 기계 판정. 명령 축(command_journal.fencing_token)이 1차 쓰기 경로라 존재 시 합산, event 축
# (event_journal.fencing_epoch)은 항상. fencing_token/fencing_epoch 값 = 리더 epoch(실데이터 확인).
function Get-StaleWriteCount {
    param([long] $CmdWatermark, [long] $EvtWatermark, [long] $OldEpoch, [bool] $CmdHasFencing)
    $n = 0
    if ($CmdHasFencing) {
        $n += [long](Get-Scalar "SELECT count(*) FROM command_journal WHERE seq > $CmdWatermark AND fencing_token = $OldEpoch")
    }
    $n += [long](Get-Scalar "SELECT count(*) FROM event_journal WHERE event_seq > $EvtWatermark AND fencing_epoch = $OldEpoch")
    return $n
}

# 승격 후 최초 신규 TRADE의 engine_ts(§5-1 T5). T0 이후·신 epoch 필터. 커밋 가시화 지연과 무관한
# 체결 재개 시점. 반환: UtcDateTime 또는 $null. Complete-FailoverExperiment와 동일 술어를 인라인용으로 노출.
function Wait-FirstTradeAfter {
    param([long] $NewEpoch, [datetime] $T0, [int] $TimeoutSec)
    $t0Iso = $T0.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fff') + 'Z'
    $sql = "SELECT min(created_at) AT TIME ZONE 'UTC' " +
        "FROM event_journal WHERE event_type='TRADE' AND fencing_epoch=$NewEpoch AND created_at > '$t0Iso'"
    Wait-For { -not [string]::IsNullOrWhiteSpace((Invoke-Psql $sql)) } -TimeoutSec $TimeoutSec -PollSec 0.5 | Out-Null
    $raw = Invoke-Psql $sql
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    return ([datetimeoffset]($raw.Replace(' ', 'T') + 'Z')).UtcDateTime
}

# 현 리더 컨테이너 판별. 자기 인스턴스 id 노출 엔드포인트가 없어(status는 lease.holder만 반환)
# 활성(lease alive) holder UUID를 각 노드 로그에서 찾는다. holder UUID는 소유 노드 로그에만
# 찍히므로 매칭되는 컨테이너가 현 리더다. ASCII UUID만 대조해 콘솔 인코딩 무관(승격/상실 한글
# 매칭은 5.1 OEM 코드페이지에서 깨져 신뢰 불가). 실패·리더 부재 시 $null.
# 용도: E5/E7이 팔로워를 잘못 대상 삼아 페일오버 없이 공허 FAIL 나는 것을 막는다(README 리허설 규약).
function Get-LeaderContainer {
    param([string[]] $Candidates = @('chaos-api-1', 'chaos-api-2'))
    $holder = Invoke-Psql "SELECT holder FROM leader_lease WHERE resource='matching-engine' AND lease_until > now()"
    if ([string]::IsNullOrWhiteSpace($holder)) { return $null }
    foreach ($c in $Candidates) {
        # cmd /c로 stderr를 nul에 버려 PowerShell이 네이티브 stderr(JAVA_TOOL_OPTIONS)를
        # NativeCommandError로 승격시키는 것을 원천 차단한다(2>$null도 Stop에서 터짐, 공용 규약).
        $lines = & cmd /c "docker logs $c 2>nul" | Select-String -SimpleMatch $holder
        if ($lines) { return $c }
    }
    return $null
}

# /actuator/prometheus 텍스트에서 메트릭 1개(첫 매치의 캡처그룹 1)를 뽑는다. 부재 시 $null(옵션 지표).
function Get-AppMetric {
    param([int] $Port, [string] $Pattern)
    try {
        $text = Invoke-RestMethod -Uri "http://localhost:$Port/actuator/prometheus" -TimeoutSec 5
    } catch { return $null }
    $m = [regex]::Match(($text | Out-String), $Pattern)
    if ($m.Success -and $m.Groups.Count -gt 1) { return $m.Groups[1].Value }
    return $null
}

# 실험 무결성 판정. CONDITIONAL 스코프 체크(C13a 물질화·C16 ACK·C19 오더북 비교차)를 제외한 나머지가
# 전부 0행이면 clean. 이 세 체크는 정지 시점/실험 한정(§4-2 ④·§4-3 각주)이라 라이브·부하·지연 중
# 비동기 창·프로젝션 지연으로 순간 위반해 상시 무결성 판정을 오염시킨다. 권위 run의 criticalClean은
# 이들을 포함하므로 실험 게이팅에는 이 함수로 ALWAYS 스코프만 본다(C16은 이 스택에서 빈 테이블 0).
function Test-ExperimentIntegrity {
    param($Run)
    $conditional = @('C13A_ORDER_MATERIALIZED', 'C16_ACK_JOURNALED', 'C19_ORDERBOOK_NONCROSS')
    foreach ($c in $Run.checks) {
        if ($conditional -contains $c.checkName) { continue }
        if ([long]$c.violatedRows -ne 0) { return $false }
    }
    return $true
}

# 비페일오버·SKIP 실험의 verdict JSON을 표준 형식으로 stdout에 낸다. failover_runs 적재는 안 한다
# (epoch 변화 없는 실험을 페일오버 행으로 남기면 증거가 오염된다). E1·E3·E8·E9가 쓴다.
function Write-Verdict {
    param([string] $ExperimentId, [string] $Verdict, [hashtable] $Metrics)
    $obj = [ordered]@{ experimentId = $ExperimentId; verdict = $Verdict; ts = (Now-Iso) }
    foreach ($k in $Metrics.Keys) { $obj[$k] = $Metrics[$k] }
    Write-Host ''
    Write-Host '── verdict(JSON) ───────────────────────' -ForegroundColor Green
    ([pscustomobject]$obj) | ConvertTo-Json -Depth 6 -Compress | Write-Host
    Write-Host '────────────────────────────────────────'
}

# ── 시계 동기 전제조건 가드(조사 #58). 페일오버 승격 게이트 실험 전용. 추가만. ──

# Docker Desktop VM 시계가 호스트와 비단조로 어긋나면(호스트 슬립발 간헐 스큐) 리스 만료 구동
# 승격(try_acquire: lease_until < clock_timestamp)이 지연돼 페일오버 실험이 거짓 FAIL 난다.
# 코드·HA(CP 안전)는 정상. chaos-pg clock_timestamp와 호스트 UTC 차의 절대값(ms)이 임계 초과면 스큐.
# 임계: CHAOS_CLOCK_SKEW_TOLERANCE_MS(기본 2000). psql 왕복지연을 midpoint로 상쇄해 잔차만 남긴다.
function Test-ClockSkew {
    param([int] $ToleranceMs = $(if ($env:CHAOS_CLOCK_SKEW_TOLERANCE_MS) { [int]$env:CHAOS_CLOCK_SKEW_TOLERANCE_MS } else { 2000 }))
    # 호스트 시각으로 psql 호출을 감싸 before·after 중앙값을 기준점으로 쓴다(NTP식 왕복 상쇄).
    $before = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $raw = Invoke-Psql "SELECT (extract(epoch from clock_timestamp()) * 1000)::bigint"
    $after = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $roundTrip = [long]($after - $before)
    if ([string]::IsNullOrWhiteSpace($raw)) {
        # PG 미도달은 스큐가 아니라 별개 실패다. 스큐 아님으로 반환하되 도달불가를 표기(호출부 판단).
        return [pscustomobject]@{ Skewed = $false; SkewMs = $null; ToleranceMs = $ToleranceMs; Reachable = $false; RoundTripMs = $roundTrip }
    }
    $pgMs = [long]$raw
    $hostMid = [long](($before + $after) / 2)
    $skewMs = [long][math]::Abs($pgMs - $hostMid)
    return [pscustomobject]@{
        Skewed = ($skewMs -gt $ToleranceMs); SkewMs = $skewMs; ToleranceMs = $ToleranceMs
        Reachable = $true; RoundTripMs = $roundTrip; PgEpochMs = $pgMs; HostEpochMs = $hostMid
    }
}

# 페일오버 실험 주입 직전 호출. 스큐면 verdict SKIP(스큐값·임계 기록)을 방출하고 $true 반환
# (호출부가 주입 없이 exit 0으로 조기 종료). 동기·PG미도달이면 $false(정상 진행). 주입이 없어
# failover_runs 미적재(SKIP은 페일오버 사건 아님). SKIP은 E3 redis·E8 chaosmonkey 부재와 동일 어휘.
function Test-FailoverClockGuard {
    param([Parameter(Mandatory = $true)][string] $ExperimentId)
    $clock = Test-ClockSkew
    if (-not $clock.Reachable) {
        # 도달불가는 시계가드 소관 밖. 정상 진행시켜 기존 권위 run·주입 경로가 실패를 드러내게 둔다.
        Write-Host "시계가드: chaos-pg clock_timestamp 미응답, 스큐 판정 생략(왕복 $($clock.RoundTripMs)ms)." -ForegroundColor Yellow
        return $false
    }
    if (-not $clock.Skewed) {
        Write-Host ("시계가드 통과: 스큐 {0}ms, 임계 {1}ms, 왕복 {2}ms." -f $clock.SkewMs, $clock.ToleranceMs, $clock.RoundTripMs)
        return $false
    }
    Write-Verdict -ExperimentId $ExperimentId -Verdict 'SKIP' -Metrics @{
        reason      = 'clock_skew'
        clockSkewMs = $clock.SkewMs
        toleranceMs = $clock.ToleranceMs
        roundTripMs = $clock.RoundTripMs
        pgEpochMs   = $clock.PgEpochMs
        hostEpochMs = $clock.HostEpochMs
        note        = 'Docker VM 시계와 호스트 UTC 스큐가 임계 초과. 장애 미주입(거짓 FAIL 방지, 조사 #58). WSL2/Docker Desktop 시계 재동기(wsl --shutdown 후 재기동) 뒤 재실행.'
    }
    Write-Host ''
    Write-Host ("{0} SKIP: 시계 스큐 {1}ms, 임계 {2}ms 초과. 주입 생략(#58 거짓 FAIL 방지)." -f $ExperimentId, $clock.SkewMs, $clock.ToleranceMs) -ForegroundColor Yellow
    return $true
}
