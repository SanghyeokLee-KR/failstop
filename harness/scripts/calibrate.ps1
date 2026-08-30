<#
.SYNOPSIS
용량 캘리브레이션(검증설계 §2-4). 외부 k6 주입 기저 OPS를 확정하고 스파이크 상한을 유도한다.

.DESCRIPTION
§2-4 1항이 규정한 판정 기준과 그로부터 파생된 축을 실측한다. 판정은 전부 기계가 한다.
  C1 호스트 전체 CPU < 60%           (-Provisional이면 스택 기여 CPU로 대체, §2-4 6항)
  C2 k6 프로세스 CPU < 1코어
  C3 부하기 내부 지연 기여(iteration_duration - http_req_duration 중앙값) < 5ms
  C4 PG fsync 건당 대기 < 10ms 이면서 HikariCP pending < 1
  C5 dropped_iterations = 0          부하기가 발행 자체를 못 하면 위 넷이 무의미하다
  C6 주문 429 비율 < 1%              §2-3이 steady에 요구하는 "429 비율 ~0"
  C7 멱등 재생(200) 비율 < 1%        재생은 엔진에 닿지 않아 용량을 증명하지 못한다
  C8 주문 도메인 거절(400) 비율 < 1% 잔고 고갈로 엔진 앞에서 튕긴 부하도 같은 실패 클래스다

전부 충족한 최고 OPS가 기저다. 스파이크 상한은 그 10배로 정의한다. 목표는 절대값이 아니라
"기저 대비 10배에서의 무결성·백프레셔 증명"이다(§2-4 머리말).

주행마다 첫 단계로 preflight.js를 1회 돌리고 비영 종료면 중단한다. 기능 실패와 용량 실패가 같은
지표에 합산되면 튜닝 대상을 오판한다(§2-4 5항, 실측 2건 2026-08-29: 죽은 키로 전량 401, LB 옛
IP 고정으로 전량 502). 사다리 앞 1회가 아니라 주행마다 도는 이유는 preflight.js가 coid 규약과
잔고 헤드룸을 그 주행의 RUN_ID·OPS·길이에서 유도하기 때문이다.

-Ops 사다리는 짧은 탐색 주행으로 후보를 좁히는 용도다. 최종 확정은 -Duration 30m 단독 주행으로
한다. 짧은 주행은 WAL 증가·커넥션 누수·GC 누적 같은 느린 발현을 못 본다.

종료코드: 0 기저 확정, 1 후보 전부 미충족·기록 실패, 2 호스트 소음 초과, 3 프리플라이트 실패.

.PARAMETER Ops
시험할 기저 OPS 목록. 외부 k6 주입 축(calibrate.js BASE_OPS)이며 봇 거버너 축
(EXCHANGE_SIM_TARGET_OPS)과 별개다. 내림차순으로 돌며 처음 통과한 값을 채택한다.

.PARAMETER Duration
각 주행의 길이. k6 표기를 쓴다(30m·3m·1m30s·90s). 정본 확정 주행은 30m.

.PARAMETER Record
통과한 결과를 harness.calibration_runs에 기록한다.

.PARAMETER AllowNoisyHost
스택 밖 소음이 허용치를 넘어도 주행한다. 그 주행은 탐색용이고 기저 확정 근거가 아니다.

.PARAMETER Provisional
C1을 호스트 전체 CPU 대신 스택 기여 CPU로 판정한다(§2-4 6항). 판정 표기가 '잠정'이 된다.

.PARAMETER K6Host
harness.calibration_runs.k6_host에 남길 부하기 호스트명. 기본은 현재 호스트다.
#>
[CmdletBinding()]
param(
    # 2026-08-29 확정치가 통과시킨 대역이다(외부 주입 기저 10 OPS, 데모 구성). 종전 기본값
    # @(300,200,150,100)은 실측상 전 구간 실패 대역이라, 무인자 실행이 사다리 넷을 다 태우고
    # exit 1로만 끝났다.
    [int[]]$Ops = @(30, 20, 15, 10),
    [string]$Duration = '3m',
    [switch]$Record,
    [switch]$AllowNoisyHost,
    [switch]$Provisional,
    [switch]$SpikeCheck,
    [string]$K6Host = $env:COMPUTERNAME
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$repoRoot = Split-Path -Parent $root
$loadDir = Join-Path $root 'load'
$artifacts = Join-Path $loadDir 'artifacts'
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null

# §2-4 판정 임계. 코드에 흩어두면 문서와 어긋나므로 한자리에 모은다.
$LIMIT_HOST_CPU = 60.0   # 퍼센트, 호스트 전체
$LIMIT_K6_CORES = 1.0    # 코어
$LIMIT_OVERHEAD = 5.0    # ms, 부하기 내부 지연 기여 중앙값
$LIMIT_PENDING  = 1.0    # HikariCP pending 스레드 표본 평균
$LIMIT_WAL_MS   = 10.0   # ms, WAL fsync 건당 대기
$LIMIT_429_RATE = 0.01   # 주문 수용 거절(429) 비율
$LIMIT_REPLAY   = 0.01   # 멱등 재생(200) 비율
$LIMIT_400_RATE = 0.01
# 스파이크 주행의 접수 처리율 하한. 확정 기저 10 OPS의 2배(§2-4 실측 id=8 54.8/s)
$SPIKE_MIN_ACCEPT_OPS = [double]($env:SPIKE_MIN_ACCEPT_OPS | ForEach-Object { if ($_) { $_ } else { 20 } })   # 주문 도메인 거절(400) 비율
# 코어, 스택 밖 프로세스 허용치(§2-4 2항 부하기 격리). 1.0인 이유는 주행을 구동하는
# 프로세스(셸·에이전트·터미널) 자체가 스택 밖으로 계상되기 때문이다. 같은 호스트에서
# 돌리는 한 이 몫은 없앨 수 없어 0.5로는 어떤 유휴 호스트도 통과하지 못한다.
# 1.0코어는 16코어 기준 6.25%로 C1 예산 60%의 1/10 미만이라 판정을 뒤집지 못한다.
# PS5.1에는 ??가 없다. 이 스크립트는 pwsh7 미설치 환경에서도 돌아야 한다.
$LIMIT_NOISE    = if ($env:CAL_NOISE_LIMIT) { [double]$env:CAL_NOISE_LIMIT } else { 1.0 }
$LOGICAL_CPUS   = [int]$env:NUMBER_OF_PROCESSORS

$PG = 'chaos-pg'
$API = 'http://localhost:58080'
$NET = 'chaos-net'
$K6_IMAGE = 'grafana/k6:1.4.0'
$CAL_CONTAINER = 'chaos-k6-cal'
$PREFLIGHT_CONTAINER = 'chaos-k6-preflight'
$API_HOSTNAME = 'api.exchange.local'
# 주행 성립 조건이다. 기본값에 기대면 어느 마켓을 실었는지가 기록에서 빠진다.
$MARKET = if ($env:CAL_MARKET) { $env:CAL_MARKET } else { 'KRW-BTC' }

# 호스트 마운트 경로는 컨테이너 밖 절대경로여야 한다. Docker Desktop이 변환한다.
$mountLoad = ($loadDir -replace '\\', '/')
$mountArt = ($artifacts -replace '\\', '/')

# ── 네이티브 호출 규약 ────────────────────────────────────────────────
# PowerShell은 EAP=Stop에서 네이티브 stderr를 NativeCommandError로 승격시킨다. 5.1에서는
# 2>$null로도 막히지 않아, 컨테이너 부재라는 매 첫 주행의 정상 상태에서 docker rm -f가 그대로
# throw한다(실측: PS5.1 + EAP=Stop). 같은 이유로 종전의 LASTEXITCODE 가드는 5.1에서 도달조차
# 하지 못하는 죽은 코드였다. lib.ps1:582-584 공용 규약대로 cmd /c로 감싸 스트림을 nul에 버린다.
# 인용 규약: docker 인자 중 값에 공백·중괄호가 들어가는 것만 큰따옴표로 감싼다. 큰따옴표 안에서는
# cmd가 파이프·괄호를 특수문자로 보지 않으므로 SQL의 || 와 '|' 가 그대로 전달된다(실측 확인).
function Invoke-DockerQuiet {
    param([string] $DockerArgs)
    & cmd /c "docker $DockerArgs >nul 2>nul"
    return $LASTEXITCODE
}

# stdout이 필요한 docker 호출. 실패는 $null로 돌려 호출부가 무응답과 진짜 값을 가르게 한다.
# 빈 문자열을 성공값으로 돌려주면 상위 판정이 관측 실패에서 만들어진다(lib.ps1 Get-Scalar 규약).
function Invoke-DockerCapture {
    param([string] $DockerArgs)
    $out = & cmd /c "docker $DockerArgs 2>nul"
    if ($LASTEXITCODE -ne 0) { return $null }
    return $out
}

function Get-PgScalar([string]$Sql) {
    # -q가 없으면 비질의 태그(UPDATE 0 등)가 섞여 파싱이 깨진다(lib.ps1과 같은 이유).
    $out = & cmd /c "docker exec $PG psql -U exchange -d exchange -q -t -A -c `"$Sql`" 2>nul"
    if ($LASTEXITCODE -ne 0) { return $null }
    return (@($out) | Where-Object { "$_" -ne '' } | Select-Object -First 1)
}

function Get-WalSnapshot {
    $row = Get-PgScalar "SELECT wal_records || '|' || wal_sync || '|' || round(wal_sync_time)::text FROM pg_stat_wal"
    if (-not $row) { return $null }
    $p = $row -split '\|'
    if ($p.Count -lt 3) { return $null }
    return [pscustomobject]@{ Records = [long]$p[0]; Syncs = [long]$p[1]; SyncMs = [double]$p[2] }
}

function Get-HikariPending {
    try {
        $r = Invoke-RestMethod -Uri "$API/actuator/metrics/hikaricp.connections.pending" -TimeoutSec 5
        return [double]($r.measurements | Where-Object statistic -eq 'VALUE' | Select-Object -First 1).value
    } catch { return $null }
}

function Get-K6Cores([string]$Name) {
    # docker stats의 CPUPerc는 100%가 1코어다. 코어 단위로 환산해 §2-4 기준과 맞춘다.
    $s = Invoke-DockerCapture "stats --no-stream --format `"{{.CPUPerc}}`" $Name"
    if ($null -eq $s) { return $null }
    $t = (@($s) | Out-String).Trim()
    if (-not $t) { return $null }
    return [math]::Round(([double]($t -replace '%', '')) / 100.0, 3)
}

function Get-StackCores {
    # 스택 컨테이너 CPU 합. 호스트 전체 CPU는 부하와 무관한 데스크톱 프로세스까지 포함하므로
    # 그것만으로는 "부하가 호스트를 포화시켰는가"를 판정할 수 없다. 둘을 함께 남긴다.
    # k6 컨테이너도 합에 든다. C1 대체 판정(§2-4 6항)은 서버만이 아니라 계측 일습이
    # 호스트에 들어가는지를 묻는 것이라, 원 기준(호스트 전체 CPU)과 대상 범위를 맞춘다.
    $lines = Invoke-DockerCapture "stats --no-stream --format `"{{.CPUPerc}}`""
    if ($null -eq $lines) { return $null }
    $sum = 0.0
    $n = 0
    foreach ($l in @($lines)) {
        $t = "$l".Trim()
        if (-not $t) { continue }
        $sum += [double]($t -replace '%', '')
        $n++
    }
    # 표본 0건은 stats 실패지 CPU 0이 아니다. 0으로 돌려주면 C1 대체 판정이 통과한다.
    if ($n -eq 0) { return $null }
    return [math]::Round($sum / 100.0, 2)
}

function Get-ExternalCores([int]$Seconds = 8) {
    # 스택 밖 프로세스가 쓰는 코어. §2-4 2항(부하기 격리)이 충족되는지 사전 확인한다.
    $a = @{}
    Get-Process | Where-Object CPU | ForEach-Object { $a[$_.Id] = $_.CPU }
    Start-Sleep -Seconds $Seconds
    $top = @()
    $total = 0.0
    $noise = 0.0
    foreach ($p in (Get-Process | Where-Object CPU)) {
        if (-not $a.ContainsKey($p.Id)) { continue }
        $c = ($p.CPU - $a[$p.Id]) / $Seconds
        $total += $c
        # Docker Desktop VM(vmmem*)은 스택 자체라 소음으로 세지 않는다.
        if ($p.ProcessName -match '^(vmmem|Vmmem|docker|com\.docker)') { continue }
        # 합산은 전 프로세스를 돈다. 0.05코어 임계를 합산에 걸면 잔챙이가 통째로 빠져 소음이
        # 과소보고된다(실측: 보고 1.35코어 대 실측 1.57코어, 누락분이 게이트 임계 1.0의 22%).
        # 임계는 화면에 띄울 Top 목록을 추리는 용도로만 쓴다.
        $noise += $c
        if ($c -gt 0.05) { $top += [pscustomobject]@{ Name = $p.ProcessName; Cores = [math]::Round($c, 2) } }
    }
    return [pscustomobject]@{
        Total = [math]::Round($total, 2)
        Noise = [math]::Round($noise, 2)
        Top   = ($top | Sort-Object Cores -Descending | Select-Object -First 6)
    }
}

function ConvertTo-DurationSeconds([string]$Dur) {
    # k6 duration 표기를 초로 푼다. 복합 표기(1m30s·2h30m)를 포함한다. 파싱 실패는 throw다.
    # 종전 default 1은 '1m30s'·'30'·''를 전부 1초로 읽었고, 그 값이 AcceptedRate의 분모라
    # 접수 처리율이 주행 길이 배수만큼 부풀려진 채 harness.calibration_runs에 확정치로 들어갔다.
    $s = "$Dur".Trim()
    $parts = [regex]::Matches($s, '(\d+(?:\.\d+)?)(ms|h|m|s)')
    # 부분 매치를 통과시키면 '1m30x'가 60초로 조용히 읽힌다. 전량 소비를 요구한다.
    $consumed = -join (@($parts) | ForEach-Object { $_.Value })
    if ($s -eq '' -or $consumed -ne $s) {
        throw "주행 길이 표기를 초로 풀 수 없다: '$Dur'. k6 표기를 쓴다(30m·3m·1m30s·90s)."
    }
    $total = 0.0
    foreach ($m in $parts) {
        $v = [double]$m.Groups[1].Value
        switch ($m.Groups[2].Value) {
            'h'  { $total += $v * 3600 }
            'm'  { $total += $v * 60 }
            's'  { $total += $v }
            'ms' { $total += $v / 1000.0 }
        }
    }
    if ($total -le 0) { throw "주행 길이가 0이다: '$Dur'." }
    return $total
}

function Get-BotOps {
    # 봇 거버너 축(EXCHANGE_SIM_TARGET_OPS). 외부 주입 축과 별개의 부하원이라, 이 값이 없으면
    # 같은 기저 OPS라도 서로 다른 배경 위에서 잰 수치가 된다. 성립 조건으로 기록에 남긴다.
    $raw = Invoke-DockerCapture "inspect -f `"{{range .Config.Env}}{{println .}}{{end}}`" chaos-bots"
    if ($null -eq $raw) { return $null }
    foreach ($line in @($raw)) {
        if ("$line" -match '^EXCHANGE_SIM_TARGET_OPS=(.+)$') { return $Matches[1].Trim() }
    }
    return $null
}

function Get-WalDelta($Before, $After) {
    # WAL 계측은 사전·사후 스냅샷 차분이다. 한쪽이라도 없으면 fsync 축을 못 잰 것이므로 0이
    # 아니라 $null을 남긴다. 0으로 폴백하면 C4의 "fsync 정상"이 계측 실패에서 만들어진다
    # (lib.ps1:184-190 규약: 무응답과 진짜 0을 가른다).
    $d = [pscustomobject]@{ Syncs = $null; MsPerSync = $null }
    if ($null -eq $Before -or $null -eq $After) { return $d }
    $d.Syncs = $After.Syncs - $Before.Syncs
    # 차가 0 이하면 주행 중 fsync가 없었거나 pg_stat_wal이 리셋된 것이다. 둘 다 건당 대기를
    # 산출할 근거가 없다. 총량은 부하 크기에 비례하므로 건당으로 봐야 비교가 선다.
    if ($d.Syncs -gt 0) { $d.MsPerSync = [math]::Round(($After.SyncMs - $Before.SyncMs) / $d.Syncs, 3) }
    return $d
}

function Get-OrderRejected($Summary) {
    # calibrate.js가 내는 400 카운트. 필드가 없으면 400 축을 재지 못한 주행이라 0으로 읽지
    # 않는다. PowerShell은 $null + 숫자를 숫자로 만들므로 합에 넣기 전에 부재를 명시로 가른다.
    if ($null -eq $Summary) { return $null }
    $prop = $Summary.PSObject.Properties['order_rejected']
    if ($null -eq $prop -or $null -eq $prop.Value) { return $null }
    return [long]$prop.Value
}

function Get-RepoRelativePath([string]$Path) {
    if ($Path.StartsWith($repoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return ($Path.Substring($repoRoot.Length).TrimStart('\', '/') -replace '\\', '/')
    }
    return ($Path -replace '\\', '/')
}

# 계측 실패를 0으로 보이게 하지 않는다. 화면에서도 $null과 진짜 0을 가른다.
# [math]::Round($null, n)은 예외 없이 0을 낸다. 그대로 출력하면 관측 실패가 실측 0으로
# 보인다. lib.ps1이 금지한 안티패턴이라 반올림 전에 null을 보존한다.
function Format-Round($Value, [int]$Digits) {
    if ($null -eq $Value) { return $null }
    return [math]::Round($Value, $Digits)
}

function Format-OrNa($Value) {
    if ($null -eq $Value) { return 'n/a' }
    return $Value
}

function Format-StatusCounts($Counts) {
    # 상태코드 분해. 실패율 한 숫자만 보면 용량 포화(429·503)와 기능 결함(400·401·502)이
    # 뭉쳐 대응을 정반대로 잡는다.
    if ($null -eq $Counts) { return 'n/a(미보고)' }
    $pairs = @()
    foreach ($p in $Counts.PSObject.Properties) { $pairs += ("{0}:{1}" -f $p.Name, $p.Value) }
    if ($pairs.Count -eq 0) { return '없음' }
    return ($pairs -join ' ')
}

function Invoke-Preflight {
    param([string] $RunId, [int] $BaseOps)
    # 주행의 첫 단계. 서명·키·LB 경로·coid 규약·잔고 헤드룸을 1회 확인하고 비영 종료면 중단한다.
    # RUN_ID·OPS·주행 길이를 뒤따를 주행 값 그대로 넘긴다. preflight.js는 coid 최악 조립과 필요
    # 헤드룸(주행길이 x OPS x 잠금단가)을 이 셋에서 유도하므로, 다른 값으로 통과시키면 검증한
    # 대상이 이 주행이 아니게 된다. 그래서 사다리 앞 1회가 아니라 주행마다 돈다. 앞선 주행이
    # GTC 미체결분으로 잠금 잔고를 소진했으면 다음 주행은 여기서 멎어야 한다. 그대로 재면
    # 400 insufficient_funds가 처리율을 깎은 값을 용량으로 적재하게 된다.
    Write-Host "1단계: preflight.js(서명·키·LB·coid 규약·잔고 헤드룸) RUN_ID=$RunId" -ForegroundColor DarkGray
    [void](Invoke-DockerQuiet "rm -f $PREFLIGHT_CONTAINER")
    $pfArgs = "run --rm --name $PREFLIGHT_CONTAINER --network $NET" +
        " -e `"BASE_URL=http://chaos-lb:8080`" -e `"API_HOST=$API_HOSTNAME`"" +
        " -e `"MARKET=$MARKET`" -e `"RUN_ID=$RunId`"" +
        " -e `"CHECK_OPS=$BaseOps`" -e `"CHECK_DURATION_SEC=$([int]$DurationSec)`"" +
        " -v `"${mountLoad}:/load:ro`"" +
        " -w /load --entrypoint k6 $K6_IMAGE run --quiet --no-color preflight.js"
    $out = & cmd /c "docker $pfArgs 2>&1"
    $code = $LASTEXITCODE
    # 통과해도 출력을 남긴다. 헤드룸 요건 로그가 이 주행이 성립한 근거다.
    Write-Host ((@($out) | Out-String).TrimEnd())
    if ($code -ne 0) {
        Write-Host "  프리플라이트 실패(종료코드 $code). 이 상태의 실패율은 용량이 아니라 배선·키·잔고 문제다." -ForegroundColor Red
        Write-Host '  해소: 키 재시드(POST /harness/v1/loadtest-keys), LB 재적재(docker exec chaos-lb nginx -s reload), 잔고 재시드.' -ForegroundColor Red
        exit 3
    }
    Write-Host '  프리플라이트 통과.'
}

function Invoke-CalibrationRun([int]$BaseOps, [string]$Dur) {
    Write-Host ""
    Write-Host "=== 기저 $BaseOps OPS / $Dur ===" -ForegroundColor Cyan

    # 주행마다 새 client_order_id 공간을 준다. 없으면 재주행이 이전 주행 키를 재사용해
    # 서버가 멱등 재생(200)으로 처리하고, 그 요청은 엔진에 닿지 않아 용량 측정이 무의미해진다.
    # preflight.js RUN_ID 규칙([A-Za-z0-9_-] 1~16자)을 만족해야 한다.
    $runId = (Get-Date).ToString('HHmmss') + $BaseOps

    # 잔류 정리를 프리플라이트 앞에 둔다. 앞선 세션이 죽으며 남긴 chaos-k6-cal이 아직 부하를
    # 싣고 있으면 프리플라이트가 그 부하 위에서 429·타임아웃을 보고 배선 결함으로 오인한다.
    [void](Invoke-DockerQuiet "rm -f $CAL_CONTAINER")
    Remove-Item (Join-Path $artifacts 'calibration.json') -ErrorAction SilentlyContinue

    Invoke-Preflight -RunId $runId -BaseOps $BaseOps

    $botOps = Get-BotOps
    $walBefore = Get-WalSnapshot
    $started = Get-Date
    # 벽시계 상한. k6 duration에 컨테이너 기동·gracefulStop(기본 30s)·요약 파일 기록 여유를
    # 얹는다. 이 상한을 넘겼다는 것은 주행이 끝나지 않았다는 뜻이라 표본을 확정치로 쓸 수 없다.
    $deadline = $started.AddSeconds($DurationSec + 180)

    # 주행별 HTML 리포트. 발표 증거로 쓰려면 주행마다 파일이 갈려야 한다. 한 이름을
    # 재사용하면 사다리 마지막 주행만 남아 어느 OPS의 그래프인지 사후에 못 가린다.
    $reportName = "calibration-$BaseOps" + "ops-$Dur.html"

    $hostCpu = New-Object System.Collections.ArrayList
    $k6Cores = New-Object System.Collections.ArrayList
    $stackCores = New-Object System.Collections.ArrayList
    $pending = New-Object System.Collections.ArrayList
    $exit = $null
    $log = ''

    # 컨테이너 기동부터 회수까지 감싼다. 표본 수집 중 예외·Ctrl+C로 빠져나가면 chaos-k6-cal이
    # 살아남아 다음 카오스 실험이 정체불명의 배경 부하 위에서 판정된다.
    try {
        $runArgs = "run -d --name $CAL_CONTAINER --network $NET" +
            " -e `"BASE_URL=http://chaos-lb:8080`" -e `"API_HOST=$API_HOSTNAME`"" +
            " -e `"MARKET=$MARKET`"" +
            " -e `"BASE_OPS=$BaseOps`" -e `"CAL_DURATION=$Dur`"" +
            " -e `"CAL_OUT=/artifacts/calibration.json`"" +
            " -e `"K6_WEB_DASHBOARD=true`" -e `"K6_WEB_DASHBOARD_EXPORT=/artifacts/$reportName`"" +
            " -e `"RUN_ID=$runId`"" +
            " -v `"${mountLoad}:/load:ro`" -v `"${mountArt}:/artifacts`"" +
            " -w /load --entrypoint k6 $K6_IMAGE run --quiet --no-color calibrate.js"
        $runCode = Invoke-DockerQuiet $runArgs
        if ($runCode -ne 0) { throw "k6 컨테이너 기동 실패(종료코드 $runCode)" }

        # 표본 수집. 램프 구간을 판정에 넣으면 정상 상태가 아닌 값을 재게 되므로 앞 20초는 버린다.
        $warmup = 20
        # docker inspect 단발 실패로 30분 주행을 파기하지 않는다. 연속 3회여야 관측 단절로 본다.
        $inspectFail = 0
        $inspectFailLimit = 3
        while ($true) {
            if ((Get-Date) -gt $deadline) {
                throw ("주행이 벽시계 상한 {0}s를 넘겼다(기대 {1}s). 표본을 확정치로 쓰지 않는다." -f `
                    [int]($DurationSec + 180), [int]$DurationSec)
            }
            $state = Invoke-DockerCapture "inspect -f `"{{.State.Running}}`" $CAL_CONTAINER"
            $stateText = ''
            if ($null -ne $state) { $stateText = (@($state) | Out-String).Trim() }
            if ($stateText -eq '') {
                $inspectFail++
                if ($inspectFail -ge $inspectFailLimit) {
                    throw "docker inspect 연속 $inspectFailLimit 회 실패. 주행 상태를 관측할 수 없다."
                }
            } else {
                $inspectFail = 0
                if ($stateText -ne 'true') { break }
            }

            if (((Get-Date) - $started).TotalSeconds -ge $warmup) {
                try {
                    $c = (Get-Counter '\Processor(_Total)\% Processor Time' -ErrorAction Stop).CounterSamples[0].CookedValue
                    [void]$hostCpu.Add([math]::Round($c, 1))
                } catch { }
                $k = Get-K6Cores $CAL_CONTAINER
                if ($null -ne $k) { [void]$k6Cores.Add($k) }
                $sc = Get-StackCores
                if ($null -ne $sc) { [void]$stackCores.Add($sc) }
                $p = Get-HikariPending
                if ($null -ne $p) { [void]$pending.Add($p) }
            }
            Start-Sleep -Seconds 5
        }

        $exitRaw = Invoke-DockerCapture "inspect -f `"{{.State.ExitCode}}`" $CAL_CONTAINER"
        if ($null -eq $exitRaw) { throw "k6 종료코드 조회 실패. 주행 결과를 판정할 수 없다." }
        $exit = [int]((@($exitRaw) | Out-String).Trim())
        $log = (@(& cmd /c "docker logs $CAL_CONTAINER 2>&1") | Out-String)
    }
    finally {
        # 회수 보장. lib.ps1:80 규약대로 finally에서 throw하지 않는다. 여기서 던지면 try의
        # 원 예외가 덮여 실패 사유가 회수 실패로 바뀐다.
        [void](Invoke-DockerQuiet "rm -f $CAL_CONTAINER")
    }
    $ended = Get-Date

    $walAfter = Get-WalSnapshot
    $summaryPath = Join-Path $artifacts 'calibration.json'
    if (-not (Test-Path $summaryPath)) {
        Write-Host $log
        throw "calibration.json 미생성. k6 종료코드 $exit"
    }
    $k6 = Get-Content $summaryPath -Raw | ConvertFrom-Json

    $wal = Get-WalDelta $walBefore $walAfter
    $rejected = Get-OrderRejected $k6
    $orderTotal = $null
    if ($null -ne $rejected) {
        $orderTotal = [long]$k6.order_accepted + [long]$k6.order_throttled +
                      [long]$k6.order_replayed + $rejected
    }

    $avg = { param($a) if ($a.Count -eq 0) { $null } else { [math]::Round((($a | Measure-Object -Average).Average), 2) } }
    $max = { param($a) if ($a.Count -eq 0) { $null } else { ($a | Measure-Object -Maximum).Maximum } }

    return [pscustomobject]@{
        BaseOps      = $BaseOps
        Duration     = $Dur
        DurationSec  = [int]$DurationSec
        Market       = $MARKET
        RunId        = $runId
        BotOps       = $botOps
        K6Image      = $K6_IMAGE
        StartedUtc   = $started.ToUniversalTime().ToString('o')
        EndedUtc     = $ended.ToUniversalTime().ToString('o')
        K6Exit       = $exit
        BaseOpsForRun = $BaseOps
        HostCpuAvg   = & $avg $hostCpu
        HostCpuMax   = & $max $hostCpu
        K6CoresAvg   = & $avg $k6Cores
        K6CoresMax   = & $max $k6Cores
        StackCoresAvg = & $avg $stackCores
        StackCoresMax = & $max $stackCores
        StackCpuPct  = if ($stackCores.Count -eq 0) { $null } else {
            [math]::Round((($stackCores | Measure-Object -Average).Average) / $LOGICAL_CPUS * 100.0, 1) }
        PendingAvg   = & $avg $pending
        PendingMax   = & $max $pending
        OverheadMs   = $k6.k6_overhead_ms
        # 주문 경로 오버헤드에서 조회 경로 오버헤드를 뺀 값. 서명만이 아니라 응답 본문
        # 파싱과 ACK 로깅이 함께 들어 있다. 서명 비용으로 읽으면 안 된다(calibrate.js 참조).
        OrderPathOverheadMs = $k6.order_path_overhead_ms
        # 재조회 응답 수. name 태그로 분리했으므로 C6·C7 분모에는 안 든다.
        OrderLookup  = $k6.order_lookup
        OrderP95Ms   = $k6.order_http_p95_ms
        # 수용 전용 지연. §2-3 spike 열의 p99<500ms 판정 축이다. 전체 축은 429가
        # 모집단을 지배해 백분위가 낮게 나온다.
        AcceptedP95Ms = $k6.accepted_p95_ms
        AcceptedP99Ms = $k6.accepted_p99_ms
        ReqRate      = $k6.http_req_rate
        FailedRate   = $k6.http_req_failed_rate
        Dropped      = $k6.dropped_iterations
        StatusCounts = $k6.status
        OrderAccepted = $k6.order_accepted
        OrderThrottled = $k6.order_throttled
        OrderReplayed = $k6.order_replayed
        OrderRejected = $rejected
        # 재생 비율. 0이 아니면 이 주행의 부하 일부가 엔진에 닿지 않았다는 뜻이다.
        ReplayRate = if (($k6.order_accepted + $k6.order_throttled + $k6.order_replayed) -le 0) { $null } else {
            [math]::Round($k6.order_replayed / ($k6.order_accepted + $k6.order_throttled + $k6.order_replayed), 4) }
        # 주문 수용 거절 비율 = 429 / (201 + 429). C6은 이 값이 0에 붙어야 통과다. 0에서 멀면
        # 잰 것은 엔진 용량이 아니라 수용 게이트의 거절 비용이다.
        Order429Rate = if (($k6.order_accepted + $k6.order_throttled) -le 0) { $null } else {
            [math]::Round($k6.order_throttled / ($k6.order_accepted + $k6.order_throttled), 4) }
        # 주문 도메인 거절 비율 = 400 / 주문 응답 전량(201+429+200+400). 400은 엔진 앞에서
        # 튕긴 것이라 429와 같은 축에 넣으면 수용 게이트 거절과 구분되지 않는다.
        Order400Rate = if ($null -eq $orderTotal -or $orderTotal -le 0) { $null } else {
            [math]::Round($rejected / [double]$orderTotal, 4) }
        # 초당 접수 건수 = 신규 접수(201) 총건 / 주행 초. 제공 부하(BaseOps)와 견줘 실제로 실린
        # 부하를 본다. 분모가 실제 주행 길이여야 이 값이 처리율이 된다.
        AcceptedRate = if ($k6.order_accepted -gt 0) {
            [math]::Round($k6.order_accepted / [double]$DurationSec, 1) } else { 0 }
        WalSyncs     = $wal.Syncs
        WalMsPerSync = $wal.MsPerSync
        Samples      = $hostCpu.Count
        # 발표자 PC 절대경로를 남기면 저장소 어느 산출물인지 사후에 가릴 수 없다. 상대경로로 남긴다.
        Report       = (Get-RepoRelativePath (Join-Path $artifacts $reportName))
    }
}

function Test-Criteria($r) {
    $BaseOpsForRun = $r.BaseOpsForRun
    $durSec = $r.DurationSec
    $c = [ordered]@{}
    if ($Provisional) {
        # 소음 주행에서는 호스트 전체 CPU가 부하의 함수가 아니다(§2-4 6항). 스택 기여로 대체
        # 판정하되 기록에 근거를 남긴다. 이 결과는 확정이 아니라 잠정이다.
        $c['C1* 스택기여CPU<60%'] = ($null -ne $r.StackCpuPct -and $r.StackCpuPct -lt $LIMIT_HOST_CPU)
    } else {
        $c['C1 호스트CPU<60%'] = ($null -ne $r.HostCpuAvg -and $r.HostCpuAvg -lt $LIMIT_HOST_CPU)
    }
    $c['C2 k6CPU<1코어'] = ($null -ne $r.K6CoresAvg -and $r.K6CoresAvg -lt $LIMIT_K6_CORES)
    $c['C3 부하기지연<5ms'] = ($null -ne $r.OverheadMs -and $r.OverheadMs -lt $LIMIT_OVERHEAD)
    # fsync는 절대 기준이 문서에 없다. 건당 대기가 커밋 지연을 지배하지 않는지로 본다.
    # pending은 대기 스레드라 정상 상태에서 0에 붙어 있어야 한다. 두 축 모두 $null 검사를 둔다.
    # WAL 스냅샷을 못 뜬 주행이 "fsync 정상"으로 통과하면 판정이 계측 실패에서 나온 것이 된다.
    $c['C4 fsync·pending정상'] = ($null -ne $r.PendingAvg -and $r.PendingAvg -lt $LIMIT_PENDING -and
        $null -ne $r.WalMsPerSync -and $r.WalMsPerSync -lt $LIMIT_WAL_MS)
    # C0 주행 완주. 나머지 기준이 전부 "무엇이 없다"를 보는 단측이라 주행이 조기에
    # 끝나면 표본이 적은 채로 전건 통과한다. 관측 실패가 확정치가 되는 경로다.
    # K6Exit는 결과 객체와 DB details에 담기면서 판정에는 안 쓰였다. 여기서 건다.
    $c['C0 주행 완주(k6 exit=0)'] = ($null -ne $r.K6Exit -and $r.K6Exit -eq 0)
    # C0b 발행량. 완주해도 부하가 안 실리면 같은 문제다. 스파이크 주행은 거절이 정상이라
    # S2가 대신 본다.
    #
    # 하한 90%. 종전 50%는 접수 51% 주행도 통과시켰다. 그 값을 고를 근거가 없었고
    # 실측이 그것을 반박한다. id=1(기저 10 OPS, 30분)은 제공 18,000에 접수 18,001로
    # 100.0%다. 시나리오가 constant-arrival-rate라 램프가 없어 앞뒤 손실도 없다.
    # 엔진 직렬 상한이 40~42/s이므로 기저 10 OPS에는 4배 여유가 있고 백프레셔가 없다
    # (같은 주행 429 비율 0.0). 정상 주행의 기대치는 100%지 50%가 아니다.
    #
    # 10%를 마진으로 남긴다. 이 축이 잡는 것은 429나 5xx가 아니라(각각 C6·C0c가 본다)
    # "완주했는데 부하가 안 실린" 경우, 즉 시나리오 미기동이나 VU 부족이다. 그 상태는
    # 부분 손실이 아니라 대폭 미달로 나타나므로 90%가 정상 주행을 깨지 않는다.
    # BaseOpsForRun 상대값이라 느린 기계에서 기저를 다시 확정하면 축이 함께 내려간다.
    if (-not $SpikeCheck) {
        $expected = [double]$BaseOpsForRun * $durSec
        $c['C0b 접수 발행(제공의 90% 이상)'] = ($null -ne $r.OrderAccepted -and
            $expected -gt 0 -and $r.OrderAccepted -ge ($expected * 0.9))
    }
    # C0c HTTP 실패율. 5xx·타임아웃은 201·429·200·400 어느 카운터에도 안 들어가
    # C6~C8 분모에서 빠진다. FailedRate를 수집만 하고 판정에 안 쓰면 주문 절반이 5xx인
    # 주행이 전건 통과해 확정치로 적재된다. 429는 전역 responseCallback이 정상으로
    # 선언하므로 이 값은 실제 실패만 담는다.
    $c['C0c HTTP실패<1%'] = ($null -ne $r.FailedRate -and $r.FailedRate -lt 0.01)
    # 개방 루프에서 발행 못한 반복이 있으면 부하기가 이미 포화다. 위 넷이 통과해도 무효다.
    $c['C5 dropped=0'] = ($null -ne $r.Dropped -and $r.Dropped -eq 0)
    # §2-3이 steady에 요구하는 "429 비율 ~0". 수용 제한(in-flight 12)을 넘는 제공 부하는
    # 초과분이 전량 429가 되고, 그 상태의 p95·처리율은 엔진 용량이 아니라 거절 비용이다.
    if ($SpikeCheck) {
        # 스파이크 확인 주행은 429가 정상이다. §2-4 머리말이 요구하는 것은 무거절이 아니라
        # "기저 대비 10배 폭주에서의 백프레셔 증명"이다. 429가 나야 백프레셔가 발동한 것이고,
        # 그때 노드가 살아 있고 부하가 실재했는지가 판정 대상이다. C6을 그대로 걸면 목적과
        # 반대로 "거절이 없어야 통과"가 되어 스파이크를 영영 기록할 수 없다.
        $c['S1 백프레셔 발동(429>10%)'] = ($null -ne $r.Order429Rate -and $r.Order429Rate -gt 0.10)
            # S2 접수 하한. >0은 접수 1건 주행도 통과시켜 발행량 판정을 승계하지 못한다.
        # 스파이크는 거절이 정상이라 제공량 비율로 잡으면 성립하지 않으므로 확정 기저
        # 대비로 잡는다. 실측 id=8은 접수 54.8/s이고 최악이던 id=7(잔여 11.6만 건)도
        # 24.4/s였다. 확정 기저 10 OPS의 2배를 하한으로 두면 정상 주행을 깨지 않는다.
        $c['S2 접수 지속(기저 2배 이상)'] = ($null -ne $r.AcceptedRate -and
            $r.AcceptedRate -ge ($SPIKE_MIN_ACCEPT_OPS))
        # §2-3 spike 열이 상한에서 요구하는 지연 SLO. 백프레셔가 발동해도 수용분은
        # 방어돼야 한다는 계약이라 스파이크 주행에서 판정한다.
        $c['S3 수용 p99<500ms'] = ($null -ne $r.AcceptedP99Ms -and $r.AcceptedP99Ms -lt 500)
    } else {
        $c['C6 주문429<1%'] = ($null -ne $r.Order429Rate -and $r.Order429Rate -lt $LIMIT_429_RATE)
    }
    # 재생이 섞이면 위 판정 전체가 무효다. 엔진에 닿지 않은 요청은 용량을 증명하지 못한다.
    $c['C7 멱등재생<1%'] = ($null -ne $r.ReplayRate -and $r.ReplayRate -lt $LIMIT_REPLAY)
    # 캘리브레이션 주문은 GTC라 미체결분이 영구 잔류하고 잠금 잔고를 회수하지 않는다. 잔고가
    # 마르면 주문이 엔진 도달 전에 400 insufficient_funds로 튕기는데, 400 축이 없으면 그
    # 주행이 "부하가 엔진에 닿지 않은 채" PASS로 판정된다. C7이 막으려던 실패 클래스가 다른
    # 문으로 재현되는 지점이다.
    $c['C8 주문400<1%'] = ($null -ne $r.Order400Rate -and $r.Order400Rate -lt $LIMIT_400_RATE)
    return $c
}

# 인자 검증. 주행 길이는 AcceptedRate의 분모이자 벽시계 상한·프리플라이트 헤드룸 산출의
# 입력이라, 어느 컨테이너를 띄우기 전에 푼다. 여기서 실패해야 30분을 태우고 나서 분모가
# 틀렸다는 것을 알지 않는다.
$DurationSec = ConvertTo-DurationSeconds $Duration

# 호스트 전제 확인. 주행별 프리플라이트는 Invoke-CalibrationRun의 첫 단계로 돈다.
Write-Host "사전 점검: 스택 밖 프로세스 CPU 측정(8초)" -ForegroundColor DarkGray
$ext = Get-ExternalCores 8
Write-Host ("  스택 밖 소음 {0}코어 / 논리 {1}코어" -f $ext.Noise, $LOGICAL_CPUS)
if ($ext.Top) { $ext.Top | ForEach-Object { Write-Host ("    {0,-24} {1}코어" -f $_.Name, $_.Cores) } }
if ($ext.Noise -gt $LIMIT_NOISE) {
    Write-Host ("  경고: 소음이 {0}코어로 허용치 {1}코어를 넘는다. C1(호스트 전체 CPU)은 부하가 아니라" -f $ext.Noise, $LIMIT_NOISE) -ForegroundColor Yellow
    Write-Host "  이 프로세스들을 재는 값이 된다. 정본 확정 주행은 §2-4 2항대로 유휴 호스트에서 한다." -ForegroundColor Yellow
    if (-not $AllowNoisyHost) {
        Write-Host "  -AllowNoisyHost 없이는 탐색 주행만 의미가 있다. 중단한다." -ForegroundColor Red
        exit 2
    }
}

$results = @()
$winner = $null
foreach ($o in ($Ops | Sort-Object -Descending)) {
    $r = Invoke-CalibrationRun $o $Duration
    $crit = Test-Criteria $r
    $pass = -not ($crit.Values -contains $false)

    Write-Host ("  호스트CPU {0}% (최대 {1}) / 스택 {2}코어 = {3}% / k6 {4}코어 / 오버헤드 {5}ms / 주문경로 {6}ms" -f `
        $r.HostCpuAvg, $r.HostCpuMax, $r.StackCoresAvg, $r.StackCpuPct, $r.K6CoresAvg, `
        (Format-OrNa (Format-Round $r.OverheadMs 3)), (Format-OrNa (Format-Round $r.OrderPathOverheadMs 3)))
    Write-Host ("  주문p95 {0}ms / 접수 {1}/s({2}건) / 429 {3} / dropped {4} / fsync 건당 {5}ms" -f `
        [math]::Round($r.OrderP95Ms, 1), $r.AcceptedRate, $r.OrderAccepted, `
        (Format-OrNa $r.Order429Rate), (Format-OrNa $r.Dropped), (Format-OrNa $r.WalMsPerSync))
    Write-Host ("  재조회 {0}건 / 수용 p95 {1}ms p99 {2}ms" -f `
        (Format-OrNa $r.OrderLookup), (Format-OrNa (Format-Round $r.AcceptedP95Ms 1)), `
        (Format-OrNa (Format-Round $r.AcceptedP99Ms 1)))
    Write-Host ("  멱등재생 {0} ({1}건) / 신규 {2}건 / 수용거절 {3}건 / 도메인거절 {4}건 (400비율 {5})" -f `
        (Format-OrNa $r.ReplayRate), $r.OrderReplayed, $r.OrderAccepted, $r.OrderThrottled, `
        (Format-OrNa $r.OrderRejected), (Format-OrNa $r.Order400Rate))
    Write-Host ("  상태코드 {0}" -f (Format-StatusCounts $r.StatusCounts))
    Write-Host ("  봇 OPS {0} / 마켓 {1} / RUN_ID {2} / {3}" -f `
        (Format-OrNa $r.BotOps), $r.Market, $r.RunId, $r.K6Image)
    foreach ($k in $crit.Keys) {
        $mark = if ($crit[$k]) { 'PASS' } else { 'FAIL' }
        $color = if ($crit[$k]) { 'Green' } else { 'Red' }
        Write-Host ("  [{0}] {1}" -f $mark, $k) -ForegroundColor $color
    }

    $r | Add-Member -NotePropertyName Pass -NotePropertyValue $pass
    $r | Add-Member -NotePropertyName Criteria -NotePropertyValue $crit
    $results += $r
    if ($pass) { $winner = $r; break }
}

Write-Host ""
if (-not $winner) {
    Write-Host "판정: 후보 전부 미충족. 기저를 더 낮추거나 부하기를 분리해야 한다(§2-4 2항)." -ForegroundColor Red
    $results | Export-Clixml (Join-Path $artifacts 'calibration-runs.xml')
    exit 1
}

$baseOps = $winner.BaseOps
# 스파이크 확인 주행은 그 자체가 상한 측정이라 10배를 다시 곱하지 않는다.
$spikeCap = if ($SpikeCheck) { $baseOps } else { $baseOps * 10 }
$verdict = if ($Provisional) { '잠정' } elseif ($SpikeCheck) { '스파이크 상한 확정' } else { '확정' }
if ($SpikeCheck) {
    Write-Host ("판정($verdict): 스파이크 $baseOps OPS에서 백프레셔 발동 확인") -ForegroundColor Green
} else {
    Write-Host ("판정($verdict): 기저 $baseOps OPS, 스파이크 상한 $spikeCap OPS") -ForegroundColor Green
}
if ($Provisional) {
    Write-Host ("  근거는 스택 기여 CPU다. 호스트 전체 CPU {0}%는 스택 밖 소음 {1}코어를 포함한다." -f `
        $winner.HostCpuAvg, $ext.Noise) -ForegroundColor Yellow
    Write-Host "  §2-4 6항상 이 값은 기저 확정이 아니다. 유휴 호스트 주행으로 다시 받아야 한다." -ForegroundColor Yellow
}

if ($Record) {
    $winner | Add-Member -NotePropertyName Provisional -NotePropertyValue ([bool]$Provisional) -Force
    $winner | Add-Member -NotePropertyName SpikeCheck -NotePropertyValue ([bool]$SpikeCheck) -Force
    $winner | Add-Member -NotePropertyName C1Basis -NotePropertyValue $(if ($Provisional) { 'stack' } else { 'host' }) -Force
    $winner | Add-Member -NotePropertyName ExternalNoiseCores -NotePropertyValue $ext.Noise -Force
    $winner | Add-Member -NotePropertyName LogicalCpus -NotePropertyValue $LOGICAL_CPUS -Force
    $details = ($winner | Select-Object -Property * -ExcludeProperty Criteria, Pass |
        ConvertTo-Json -Compress -Depth 4)
    # psql -c에 인자로 넘기면 따옴표·달러가 Windows 인자 분해와 셸 사이에서 두 번 해석된다.
    # 파일로 써서 psql -f로 먹이면 해석 단계가 없어진다. stdin 파이프를 쓰면 안 된다. PS5.1은
    # $OutputEncoding이 us-ascii라 비ASCII가 '?'로 치환된 채 적재되고 psql은 exit 0으로 성공한다
    # (실측: md5 불일치, 길이는 보존). BOM 없는 UTF-8 파일 + docker cp가 바이트를 보존한다.
    $sqlFile = Join-Path $artifacts 'calibration-insert.sql'
    $dollar = [char]36
    $q = "$dollar" + "cal" + "$dollar"
    $sqlText = @(
        "INSERT INTO harness.calibration_runs (base_ops, spike_cap, k6_host, details)",
        "VALUES ($baseOps, $spikeCap, $q$K6Host$q, $q$details$q" + "::jsonb);"
    ) -join "`n"
    [System.IO.File]::WriteAllText($sqlFile, $sqlText, (New-Object System.Text.UTF8Encoding($false)))

    $cpCode = Invoke-DockerQuiet "cp `"$sqlFile`" ${PG}:/tmp/calibration-insert.sql"
    if ($cpCode -ne 0) { Write-Host "기록 실패(SQL 전송 종료코드 $cpCode)" -ForegroundColor Red; exit 1 }
    $psqlOut = & cmd /c "docker exec $PG psql -U exchange -d exchange -q -v ON_ERROR_STOP=1 -f /tmp/calibration-insert.sql 2>&1"
    $psqlCode = $LASTEXITCODE
    [void](Invoke-DockerQuiet "exec $PG rm -f /tmp/calibration-insert.sql")
    if ($psqlCode -ne 0) {
        Write-Host ("기록 실패(psql 종료코드 {0}): {1}" -f $psqlCode, ((@($psqlOut) | Out-String).Trim())) -ForegroundColor Red
        exit 1
    }
    $id = Get-PgScalar "SELECT max(id) FROM harness.calibration_runs"
    if (-not $id) { Write-Host "기록 확인 실패(적재 id 조회 무응답)" -ForegroundColor Red; exit 1 }
    Write-Host "harness.calibration_runs 기록 완료 (id=$id)"
}
exit 0
