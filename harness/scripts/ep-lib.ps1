# Patroni 실험(E-P) 실행기 공용(Patroni 설계 8절). 시나리오 스크립트(ep-p*.ps1)가 dot-source 한다.
#
# 한 회차는 다음 순서다. 회차마다 스택을 새로 세운다(설계 8-1).
#   1) down -> up(-Obs). 준비 확인: Patroni 리더와 동기 Standby, 두 앱의 거래 상태 ACTIVE, 앱 리더 리스
#   2) 부하 계정 키, DB 수준 ACK 기록기(patroni-ackrec), 관측기(patroni_observe.py), 필요하면 k6·출금 요청기
#   3) 주입 전 창(35초 이상, 기록기 비공허 조건) -> 사전 권위 run -> 시계 가드 -> 주입(T0)
#   4) 유지(HoldSec) -> 복귀(T_back) -> 재개(T_resume)와 앱 승격 관찰 -> 재개 뒤 창(35초 이상)
#   5) k6·출금 요청기 정지 -> ACK 적재 -> 봇 정지·드레인 -> 사후 권위 run -> 사실 수집 -> 기록기 대조 -> 판정
#   6) 증거(판정 JSON, 시각, 사실, 관측 표본, 로그 해시)를 harness/evidence/patroni-EP-<YYYYMMDD-HHMM>/ 에 둔다.
#      기록기 원본과 컨테이너 로그 원문은 저장소 밖(%TEMP%\exchange-patroni-ep\<run>)에 둔다
#   7) down -v --remove-orphans 와 남은 자원 0 확인(-KeepStack 이면 건너뛴다)
#
# 주입은 이 스크립트를 사람이 실행해서만 한다(설계 8-4). compose 프로젝트 exchange-patroni 의 patroni-* 컨테이너 이름만 다룬다.
# 다른 작업의 프로젝트(exchange-chaos, exchange-pml), chaos-* 컨테이너, 보존 볼륨에는 명령을 보내지 않는다.

$ErrorActionPreference = 'Stop'

$Global:EP = @{
    Repo      = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    PatroniDir = (Resolve-Path (Join-Path $PSScriptRoot '..\patroni')).Path
    ScaleDir  = (Resolve-Path (Join-Path $PSScriptRoot '..\scale')).Path
    Project   = 'exchange-patroni'
    AppNetwork = 'exchange-patroni_patroni-app'
    DbNetwork = 'exchange-patroni_patroni-db'
    Prometheus = 'http://127.0.0.1:59190'
    Patroni   = @('http://127.0.0.1:59008', 'http://127.0.0.1:59009')
    Nodes     = [ordered]@{ 'patroni-api-1' = 59181; 'patroni-api-2' = 59182 }
    Python    = $(if ($env:EP_PYTHON) { $env:EP_PYTHON } else { 'python' })
}
$Global:EP.EnvFile = Join-Path $Global:EP.PatroniDir '.env.patroni'
$Global:EP.Compose = Join-Path $Global:EP.PatroniDir 'docker-compose.patroni.yml'

# lib.ps1 의 대상을 Patroni 스택으로 돌린다(검토 R7). psql 은 patroni-toolbox 에서 patroni-haproxy-b 를 거쳐 현재 Primary 에 붙는다.
$env:CHAOS_PSQL_CONTAINER = 'patroni-toolbox'
$env:CHAOS_API1_PORT = '59181'
$env:CHAOS_API2_PORT = '59182'
$env:CHAOS_LB_PORT = '59180'
$env:CHAOS_API_CONTAINERS = 'patroni-api-1,patroni-api-2'
$env:CHAOS_BOTS_CONTAINER = 'patroni-bots'
$env:CHAOS_ENV_FILE = $Global:EP.EnvFile
$env:CHAOS_PROJECT = 'exchange-patroni'
$env:CHAOS_APP_IMAGE = 'exchange-chaos-app:patroni'
# 주입 동안 psql 이 매달려 실행기가 멈추지 않게 한다(lib.ps1 Invoke-Psql).
$env:CHAOS_PSQL_TIMEOUT_SEC = '15'
. (Join-Path $PSScriptRoot 'lib.ps1')

function Get-EpNow { return [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000.0 }
function ConvertTo-EpIso([double] $Epoch) {
    return [DateTimeOffset]::FromUnixTimeMilliseconds([long]($Epoch * 1000)).UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
}
function ConvertFrom-DbTimestamp([string] $Raw) {
    if ([string]::IsNullOrWhiteSpace($Raw)) { return $null }
    $iso = $Raw.Trim().Replace(' ', 'T') + 'Z'
    return ([DateTimeOffset]::Parse($iso)).ToUnixTimeMilliseconds() / 1000.0
}

function Invoke-EpCompose {
    # 프로젝트·파일·env 를 고정한다. 다른 프로젝트 이름으로 명령이 나갈 길을 막는다.
    & docker compose -p $Global:EP.Project -f $Global:EP.Compose --env-file $Global:EP.EnvFile @args
}

function Write-EpJson([string] $Path, $Object) {
    $json = $Object | ConvertTo-Json -Depth 12
    [System.IO.File]::WriteAllText($Path, $json, [System.Text.UTF8Encoding]::new($false))
}

function New-EpRun {
    param([Parameter(Mandatory)][string] $Scenario)
    $stamp = (Get-Date).ToUniversalTime()
    $id = ($Scenario -replace '[^A-Za-z0-9]', '')
    $runId = "$id-$($stamp.ToString('yyyyMMdd-HHmmss'))"
    $raw = Join-Path ([System.IO.Path]::GetTempPath()) "exchange-patroni-ep\$runId"
    New-Item -ItemType Directory -Force $raw | Out-Null
    $evidence = Join-Path $Global:EP.Repo "harness\evidence\patroni-EP-$($stamp.ToString('yyyyMMdd-HHmm'))"
    New-Item -ItemType Directory -Force $evidence | Out-Null
    $Global:CHAOS.EvidenceDir = $evidence
    return [ordered]@{ Scenario = $Scenario; RunId = $runId; Raw = $raw; Evidence = $evidence; Timeline = [ordered]@{
        scenario = $Scenario; run_id = $runId }; Facts = [ordered]@{}; Observer = $null; Stale = $null }
}

function Start-EpStack {
    param([switch] $Build, [int] $BotsOps = 0)
    Write-Section "스택 새로 세우기(down -> up -Obs)"
    # 자식 출력은 화면으로만 보낸다. 파이프라인에 섞이면 Invoke-EpExperiment 의 반환값(종료코드)이 배열이 된다.
    & pwsh -NoProfile -File (Join-Path $Global:EP.PatroniDir 'down.ps1') | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'down.ps1 실패' }
    if ($BotsOps -gt 0) { $env:PATRONI_BOTS_OPS = "$BotsOps" } else { Remove-Item Env:PATRONI_BOTS_OPS -ErrorAction SilentlyContinue }
    $upArgs = @('-NoProfile', '-File', (Join-Path $Global:EP.PatroniDir 'up.ps1'), '-Obs')
    if (-not $Build) { $upArgs += '-SkipBuild' }
    & pwsh @upArgs | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'up.ps1 실패' }
}

function Stop-EpStack {
    Write-Section '스택 정리(down -v --remove-orphans)'
    & pwsh -NoProfile -File (Join-Path $Global:EP.PatroniDir 'down.ps1') | Out-Host
}

function Get-EpCluster {
    foreach ($base in $Global:EP.Patroni) {
        try { return Invoke-RestMethod -Uri "$base/cluster" -TimeoutSec 2 } catch { }
    }
    return $null
}

function Get-EpClusterLeader {
    $c = Get-EpCluster
    if (-not $c) { return $null }
    $leader = @($c.members | Where-Object { $_.role -eq 'leader' }) | Select-Object -First 1
    $sync = @($c.members | Where-Object { $_.role -eq 'sync_standby' -or $_.role -eq 'quorum_standby' } | ForEach-Object { $_.name })
    return [pscustomobject]@{ Leader = $leader.name; Timeline = $leader.timeline; Sync = $sync; Members = @($c.members) }
}

function Get-EpStatus([int] $Port) {
    try { return Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/status" -TimeoutSec 2 } catch { return $null }
}

function Test-EpAllActive {
    foreach ($port in $Global:EP.Nodes.Values) {
        $s = Get-EpStatus $port
        if (-not $s -or $s.trading_suspended) { return $false }
    }
    return $true
}

function Get-EpLease {
    $row = Invoke-Psql "SELECT epoch || '|' || holder FROM leader_lease WHERE resource = 'matching-engine' AND lease_until > now()"
    if ([string]::IsNullOrWhiteSpace($row)) { return $null }
    $parts = $row.Split('|')
    return [pscustomobject]@{ Epoch = [long]$parts[0]; Holder = $parts[1] }
}

function Get-EpLeaseEpoch {
    $v = Invoke-Psql "SELECT max(epoch) FROM leader_lease WHERE resource = 'matching-engine'"
    if ([string]::IsNullOrWhiteSpace($v)) { return $null }
    return [long]$v
}

function Wait-EpReady {
    param([int] $TimeoutSec = 240)
    Write-Section '준비 확인'
    $ok = Wait-For {
        $c = Get-EpClusterLeader
        $lease = Get-EpLease
        ($c -and $c.Leader -and $c.Sync.Count -ge 1) -and (Test-EpAllActive) -and ($null -ne $lease)
    } -TimeoutSec $TimeoutSec -PollSec 1
    if (-not $ok) { throw "스택 준비 미완($TimeoutSec 초). Patroni 동기 Standby, 거래 상태, 리더 리스를 확인한다." }
    $c = Get-EpClusterLeader
    Write-Host "Patroni 리더 $($c.Leader), 동기 $($c.Sync -join ','), 타임라인 $($c.Timeline)"
}

# 앱 망 주소. ack 기록기의 inet_server_addr() 와 같은 모양(주소/32)으로 적는다.
function Get-EpDbIps {
    $map = [ordered]@{}
    foreach ($name in @('patroni-db-1', 'patroni-db-2')) {
        $ip = (& docker inspect -f "{{(index .NetworkSettings.Networks `"$($Global:EP.AppNetwork)`").IPAddress}}" $name 2>$null | Out-String).Trim()
        if ($ip) { $map[$name] = "$ip/32" }
    }
    return $map
}

function New-EpKeys($Run, [int] $Count = 1) {
    # 부하 계정 키. 이 폐기용 스택에만 있는 값이라 저장소 밖 원시 폴더에만 둔다. 출력하지 않는다.
    $uri = "http://127.0.0.1:59181/harness/v1/loadtest-keys?market=KRW-BTC&count=$Count&quote=1000000000&base=100"
    $keys = $null
    for ($i = 0; $i -lt 6 -and -not $keys; $i++) {
        try { $keys = Invoke-RestMethod -Uri $uri -Method Post -TimeoutSec 90 } catch { Start-Sleep -Seconds 5 }
    }
    if (-not $keys) { throw '부하 계정 키 발급 실패' }
    $path = Join-Path $Run.Raw 'keys.json'
    Write-EpJson $path @($keys)
    return $path
}

function Start-EpRecorder($Run) {
    Write-Section 'DB 수준 ACK 기록기'
    $init = "CREATE TABLE IF NOT EXISTS ack_probe (n bigint PRIMARY KEY, writer int NOT NULL, t_client timestamptz NOT NULL); TRUNCATE ack_probe;"
    & docker exec -e PGCONNECT_TIMEOUT=5 patroni-toolbox timeout 30 psql -d infra_probe -v ON_ERROR_STOP=1 -q -c $init | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'ack_probe 준비 실패' }
    $env:PATRONI_EP_OUT = $Run.Raw
    Remove-Item (Join-Path $Run.Raw 'ackrec.stop') -ErrorAction SilentlyContinue
    Invoke-EpCompose --profile ep up -d --no-deps patroni-ackrec | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'patroni-ackrec 기동 실패' }
}

function Stop-EpRecorder($Run) {
    New-Item -ItemType File -Force (Join-Path $Run.Raw 'ackrec.stop') | Out-Null
    $stopped = Wait-For { (& docker inspect -f '{{.State.Running}}' patroni-ackrec 2>$null | Out-String).Trim() -ne 'true' } -TimeoutSec 30 -PollSec 0.5
    if (-not $stopped) { & docker stop -t 5 patroni-ackrec | Out-Null }
    & docker logs patroni-ackrec *> (Join-Path $Run.Raw 'ackrec.container.log')
    $env:PATRONI_EP_OUT = $Run.Raw
    Invoke-EpCompose --profile ep run --rm --no-deps patroni-ackrec verify --log /out/ackrec.jsonl --out /out/ackrec-verify.json *> (Join-Path $Run.Raw 'ackrec-verify.log')
    Write-Host "기록기 대조 종료코드 $LASTEXITCODE(0 이면 빠진 번호 0)"
}

function Start-EpObserver($Run, [string] $KeysPath, [switch] $Pool, [switch] $Withdraw) {
    Write-Section '관측기'
    $pyArgs = @((Join-Path $Global:EP.ScaleDir 'patroni_observe.py'), '--out', (Join-Path $Run.Raw 'observe.jsonl'),
        '--stop-file', (Join-Path $Run.Raw 'observe.stop'), '--keys', $KeysPath,
        '--withdraw-stop-file', (Join-Path $Run.Raw 'withdraw.stop'))
    if ($Pool) { $pyArgs += '--pool' }
    if ($Withdraw) { $pyArgs += '--withdraw' }
    $Run.Observer = Start-Process -FilePath $Global:EP.Python -ArgumentList $pyArgs -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $Run.Raw 'observe.stdout.log') -RedirectStandardError (Join-Path $Run.Raw 'observe.stderr.log')
}

function Stop-EpObserver($Run) {
    if (-not $Run.Observer) { return }
    New-Item -ItemType File -Force (Join-Path $Run.Raw 'observe.stop') | Out-Null
    if (-not $Run.Observer.WaitForExit(20000)) { $Run.Observer.Kill() }
    $Run.Observer = $null
}

function Start-EpK6($Run, [string] $KeysPath, [int] $Rate = 10) {
    # 접수 주행. 이 스택의 LB 로 보낸다. 창 표식은 쓰지 않는다(판정은 patroni_axes 가 한다). e6 프로파일(13분)로 띄우고
    # 실험 끝에 멈춘다. 시나리오 정의가 있는 스크립트에 --duration 을 주면 k6 가 시나리오를 기본 시나리오로 바꾼다.
    $load = Join-Path $Global:EP.Repo 'harness\load'
    Copy-Item $KeysPath (Join-Path $Run.Raw 'k6_keys.json') -Force
    $name = 'patroni-k6'
    & docker rm -f $name 2>$null | Out-Null
    & docker run -d --name $name --network $Global:EP.AppNetwork -v "${load}:/w:ro" -v "$($Run.Raw):/raw:ro" -w /w `
        grafana/k6:1.4.0 run --quiet -e RUN_ID=$($Run.RunId.Substring(0, [Math]::Min(16, $Run.RunId.Length)).Replace('-', '')) `
        -e BASE_URL=http://patroni-lb:8080 -e K6_KEYS=/raw/k6_keys.json -e PROFILE=e6 -e STEADY_RATE=$Rate -e SPIKE_RATE=$Rate `
        -e READ_RATE=1 -e WS_VUS=1 order_flow.js | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'k6 기동 실패' }
}

function Stop-EpK6($Run) {
    $running = (& docker inspect -f '{{.State.Running}}' patroni-k6 2>$null | Out-String).Trim()
    if (-not $running) { return $null }
    if ($running -eq 'true') { & docker stop -t 10 patroni-k6 | Out-Null }
    $log = Join-Path $Run.Raw 'k6.log'
    & docker logs patroni-k6 *> $log
    & docker rm -f patroni-k6 | Out-Null
    return $log
}

# /actuator/prometheus 본문에서 판정에 쓰는 계수를 읽는다. 태그가 없는 계수는 중괄호 없이 찍힌다
# (`exchange_write_probe_unconfirmed_total 1.0`). 중괄호를 요구하면 그런 계수를 0 으로 읽는다(2026-09-25 E-P7 첫 회차).
function ConvertFrom-EpMetricsText([string] $Text) {
    $sum = {
        param([string] $Name, [string] $LabelPattern)
        $total = 0.0
        foreach ($m in [regex]::Matches($Text, "(?m)^$Name(?:\{([^}]*)\})?\s+(\S+)")) {
            if ($LabelPattern -and $m.Groups[1].Value -notmatch $LabelPattern) { continue }
            $total += [double]::Parse($m.Groups[2].Value, [Globalization.CultureInfo]::InvariantCulture)
        }
        return $total
    }
    return [ordered]@{
        demotions = (& $sum 'exchange_partition_demotions_total' $null) + (& $sum 'exchange_leader_standdown_total' $null) +
            (& $sum 'exchange_leader_recoverable_standdown_total' $null)
        lease_acquire_expired = (& $sum 'exchange_lease_acquire_expired_total' $null)
        renew_rejected = (& $sum 'exchange_leader_lease_renew_total' 'outcome="rejected"')
        gate_423 = (& $sum 'exchange_write_gate_rejected_total' 'response="423"')
        gate_503 = (& $sum 'exchange_write_gate_rejected_total' 'response="503"')
        probe_unconfirmed = (& $sum 'exchange_write_probe_unconfirmed_total' $null)
        definitive_deferred = (& $sum 'exchange_write_definitive_deferred_total' $null)
        orders_500 = (& $sum 'http_server_requests_seconds_count' 'status="500".*uri="/api/v1/orders|uri="/api/v1/orders.*status="500"')
        commit_recovered = (& $sum 'exchange_order_intake_commit_recovered_total' $null)
        unresolved = (& $sum 'exchange_order_intake_unresolved_total' $null)
        unavailable = (& $sum 'exchange_order_intake_unavailable_total' $null)
    }
}

function Get-EpNodeMetrics([int] $Port) {
    try { $text = (Invoke-WebRequest -Uri "http://127.0.0.1:$Port/actuator/prometheus" -TimeoutSec 5 -UseBasicParsing).Content }
    catch { return $null }
    return ConvertFrom-EpMetricsText $text
}

function Get-EpMetricsTotal {
    $total = [ordered]@{}
    $nodes = [ordered]@{}
    foreach ($entry in $Global:EP.Nodes.GetEnumerator()) {
        $m = Get-EpNodeMetrics $entry.Value
        $nodes[$entry.Key] = $m
        if (-not $m) { continue }
        foreach ($k in $m.Keys) { $total[$k] = [double]($total[$k]) + [double]$m[$k] }
    }
    $total['nodes_answered'] = @($nodes.Keys | Where-Object { $nodes[$_] }).Count
    return [pscustomobject]@{ Total = $total; Nodes = $nodes }
}

function Get-EpHostCpu {
    try {
        return [double](Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'").PercentProcessorTime
    } catch { return $null }
}

# 회차 무효 조건(c)의 실험 밖 부하 도구. 호스트의 k6·Gradle·java 시험 가운데 2초 동안 CPU 를 10% 넘게(한 코어 기준) 쓴 것과,
# 다른 프로젝트의 k6 컨테이너를 적는다. 쉬고 있는 Gradle 데몬(이 작업 트리의 bootJar 빌드가 남긴 것 포함)은 부하가 아니다.
function Get-EpForeignLoad {
    $candidates = @()
    foreach ($p in @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)) {
        $cmd = [string]$p.CommandLine
        if ($p.Name -match '^k6(\.exe)?$') { $candidates += [pscustomobject]@{ Id = $p.ProcessId; Kind = 'k6' } }
        elseif ($p.Name -match '^java(w)?\.exe$' -and $cmd -match 'GradleDaemon|GradleWorkerMain|junit|surefire') {
            $candidates += [pscustomobject]@{ Id = $p.ProcessId; Kind = 'java-gradle' }
        }
    }
    $before = @{}
    foreach ($c in $candidates) {
        $proc = Get-Process -Id $c.Id -ErrorAction SilentlyContinue
        if ($proc) { $before[$c.Id] = $proc.TotalProcessorTime.TotalMilliseconds }
    }
    Start-Sleep -Seconds 2
    $found = @()
    foreach ($c in $candidates) {
        $proc = Get-Process -Id $c.Id -ErrorAction SilentlyContinue
        if (-not $proc -or -not $before.ContainsKey($c.Id)) { continue }
        $cpuMs = $proc.TotalProcessorTime.TotalMilliseconds - $before[$c.Id]
        if ($cpuMs -gt 200) { $found += ('{0}(pid {1}, {2}ms/2s)' -f $c.Kind, $c.Id, [int]$cpuMs) }
    }
    foreach ($c in @(& docker ps --format '{{.Names}} {{.Image}}' 2>$null)) {
        if ($c -match 'k6' -and $c -notmatch '^patroni-') { $found += "container:$($c.Split(' ')[0])" }
    }
    return $found
}

function Get-EpPromScalar([string] $Query, [double] $At = 0) {
    $uri = "$($Global:EP.Prometheus)/api/v1/query?query=$([uri]::EscapeDataString($Query))"
    if ($At -gt 0) { $uri += "&time=$($At.ToString([Globalization.CultureInfo]::InvariantCulture))" }
    try {
        $r = Invoke-RestMethod -Uri $uri -TimeoutSec 10
        if ($r.status -ne 'success' -or -not $r.data.result) { return $null }
        $v = $r.data.result[0].value[1]
        if ($v -eq 'NaN') { return $null }
        return [double]::Parse($v, [Globalization.CultureInfo]::InvariantCulture)
    } catch { return $null }
}

function Get-EpStaleCounts([long] $Epoch) {
    return Get-EpochWriteCounts -Epoch $Epoch -CmdHasFencing (Test-CommandFencingColumn)
}

function Save-EpContainerLogs($Run) {
    $logs = Join-Path $Run.Raw 'logs'
    New-Item -ItemType Directory -Force $logs | Out-Null
    $hashes = [ordered]@{}
    foreach ($name in @(& docker ps -a --filter "label=com.docker.compose.project=$($Global:EP.Project)" --format '{{.Names}}')) {
        $path = Join-Path $logs "$name.log"
        & cmd /c "docker logs $name > `"$path`" 2>&1"
        $hashes[$name] = (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    return $hashes
}

# 한 회차. Inject 는 주입하고 대상 정보를 hashtable 로 돌려준다({ injected; role; sync_member }). Recover 는 복귀한다.
function Invoke-EpExperiment {
    param(
        [Parameter(Mandatory)][string] $Scenario,
        [Parameter(Mandatory)][scriptblock] $Inject,
        [scriptblock] $Recover,
        [int] $HoldSec = 60,
        [switch] $ExpectEpochChange,
        [switch] $ExpectSuspension,
        [switch] $Pool,
        [switch] $Withdraw,
        [switch] $K6,
        [int] $BotsOps = 0,
        [switch] $Build,
        [switch] $KeepStack,
        [int] $ResumeTimeoutSec = 240,
        [int] $PreWindowSec = 40,
        [int] $PostWindowSec = 40
    )
    $run = New-EpRun $Scenario
    $tl = $run.Timeline
    $facts = $run.Facts
    Write-Section "$Scenario 시작 runId=$($run.RunId) 원시=$($run.Raw)"
    $exit = 1
    $skipped = $false
    $failure = $null
    try {
        Start-EpStack -Build:$Build -BotsOps $BotsOps
        Wait-EpReady
        $cluster = Get-EpClusterLeader
        $lease = Get-EpLease
        $tl.db_ips = Get-EpDbIps
        # haproxy.cfg 의 pg_write 서버 이름(db-N)이다. 망 별칭(db-N-app)과 다르다.
        $tl.haproxy_server = [ordered]@{ 'patroni-db-1' = 'db-1'; 'patroni-db-2' = 'db-2' }
        $tl.pre = [ordered]@{ patroni_leader = $cluster.Leader; timeline = $cluster.Timeline; sync = $cluster.Sync
            epoch = $lease.Epoch; app_leader = (Get-LeaderContainer) }
        $keys = New-EpKeys $run 2
        Start-EpRecorder $run
        Start-EpObserver $run $keys -Pool:$Pool -Withdraw:$Withdraw
        if ($K6) { Start-EpK6 $run $keys }
        Write-Host "주입 전 창 $PreWindowSec 초"
        Start-Sleep -Seconds ([Math]::Max(5, $PreWindowSec - 10))
        $facts.invariants = [ordered]@{ pre = (Invoke-InvariantRun -Port 59181 -RunId $run.RunId -Window 'pre') }
        $facts.metrics = [ordered]@{ before = (Get-EpMetricsTotal).Total }
        $facts.invalid = [ordered]@{ cpu_samples = @(); foreign_processes = @(Get-EpForeignLoad) }
        $facts.invalid.foreign_idle_note = '쉬는 Gradle 데몬은 세지 않는다(2초 CPU 200ms 이하)'
        $facts.invalid.cpu_samples += Get-EpHostCpu
        $etcdChangesBefore = Get-EpPromScalar 'sum(etcd_server_leader_changes_seen_total)'
        if (Test-FailoverClockGuard -ExperimentId $Scenario -RunId $run.RunId) {
            # 시계가 어긋나 주입하지 않았다. 판정 파일은 lib.ps1 이 SKIP 으로 남겼다.
            $skipped = $true
            throw [System.OperationCanceledException]::new('clock_skew')
        }
        $facts.invalid.cpu_samples += Get-EpHostCpu
        Start-Sleep -Seconds 3

        # ── 주입 ──
        $oldEpoch = [long]$tl.pre.epoch
        $t0 = Get-EpNow
        $tl.t0 = $t0
        $tl.hold_s = $(if ($Recover) { $HoldSec } else { $null })
        $injected = & $Inject $tl
        foreach ($k in $injected.Keys) { $tl[$k] = $injected[$k] }
        Write-Host ("T0={0} 주입 {1}" -f (ConvertTo-EpIso $t0), ($injected | ConvertTo-Json -Compress))

        # ── 유지와 복귀. 새 epoch 가 보이면 그때 구 epoch 쓰기 기준선을 잡는다(E4 와 같은 규약) ──
        # $observe 는 점 소싱으로 이 함수 범위에서 돈다. 안에서 대입한 값이 그대로 남는다.
        $promotedAt = $null; $promotedNode = $null; $staleBase = $null; $promotionMetrics = $null
        # 기대하지 않은 승격(E-P1b 의 앱 리더 교체 등)도 기준선을 잡는다. 기대 여부는 기다릴지만 정한다.
        $observe = {
            if (-not $promotedAt) {
                $e = Get-EpLeaseEpoch
                if ($null -ne $e -and $e -gt $oldEpoch) {
                    $promotedAt = Get-EpNow
                    $staleBase = Get-EpStaleCounts $oldEpoch
                    $promotedNode = Get-LeaderContainer
                    if ($promotedNode) { $promotionMetrics = Get-EpNodeMetrics $Global:EP.Nodes[$promotedNode] }
                    Write-Host ("승격 관찰 epoch {0} {1} {2}" -f $e, $promotedNode, (ConvertTo-EpIso $promotedAt))
                }
            }
        }
        $holdEnd = $t0 + $HoldSec
        # 쉼표가 + 보다 먼저 묶인다. 괄호가 없으면 $t0 + @(3, $t0) + ... 가 된다.
        $cpuAt = @(($t0 + 3), ($t0 + 6), ($t0 + 9))
        while ((Get-EpNow) -lt $holdEnd) {
            . $observe
            if ($cpuAt.Count -gt 0 -and (Get-EpNow) -ge $cpuAt[0]) { $facts.invalid.cpu_samples += Get-EpHostCpu; $cpuAt = @($cpuAt | Select-Object -Skip 1) }
            Start-Sleep -Milliseconds 500
        }
        if ($Recover) {
            $tl.t_back = Get-EpNow
            & $Recover $tl
            Write-Host ("T_back={0}" -f (ConvertTo-EpIso $tl.t_back))
        }
        $deadline = (Get-EpNow) + $ResumeTimeoutSec
        $resumedAt = $null; $activeStreak = 0; $nextStatus = 0
        while ((Get-EpNow) -lt $deadline) {
            . $observe
            # 상태 API 는 IP 당 분당 120회 상한이다. 관측기(노드마다 초당 1회)와 합쳐 넘지 않게 2초마다 읽는다.
            if ((Get-EpNow) -ge $nextStatus) {
                $nextStatus = (Get-EpNow) + 2
                if (Test-EpAllActive) { $activeStreak++ } else { $activeStreak = 0 }
            }
            if (-not $resumedAt -and $activeStreak -ge 2) { $resumedAt = Get-EpNow }
            $epochDone = (-not $ExpectEpochChange) -or $promotedAt
            if ($resumedAt -and $epochDone -and (-not $promotedAt -or (Get-EpNow) -ge $promotedAt + 30)) { break }
            Start-Sleep -Milliseconds 500
        }
        if ($promotedAt -and $promotedNode) {
            $after = Get-EpNodeMetrics $Global:EP.Nodes[$promotedNode]
            if ($after -and $promotionMetrics) {
                $facts.post_promotion = [ordered]@{ node = $promotedNode
                    fencing_rejections = $after.renew_rejected - $promotionMetrics.renew_rejected
                    demotions = $after.demotions - $promotionMetrics.demotions }
            }
        }
        $tl.t_resume_hint = $resumedAt
        if (-not $resumedAt) { Write-Host '경고: 재개 미관찰(시간 초과)' -ForegroundColor Yellow }
        # 무효 조건 (c)는 주입부터 재개까지다. 재개 직후에 한 번 더 본다.
        $facts.invalid.foreign_processes = @($facts.invalid.foreign_processes) + @(Get-EpForeignLoad)
        Write-Host "재개 뒤 창 $PostWindowSec 초"
        Start-Sleep -Seconds $PostWindowSec

        # ── 부하 정지와 사후 run ──
        New-Item -ItemType File -Force (Join-Path $run.Raw 'withdraw.stop') | Out-Null
        $k6Log = Stop-EpK6 $run
        if ($k6Log) {
            & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'load-acks.ps1') -LogPath $k6Log -Reset -Pg patroni-toolbox | Out-Host
            $facts.k6 = [ordered]@{ log_sha256 = (Get-FileHash $k6Log -Algorithm SHA256).Hash.ToLowerInvariant()
                ack_lines = @(Select-String -Path $k6Log -Pattern 'ACK|' -SimpleMatch).Count }
        }
        Start-Sleep -Seconds 3
        $botsStopped = Stop-BotLoad
        $drained = Wait-DrainZero -Sql $Global:CHAOS_DRAIN_SQL -TimeoutSec 90 -RequiredStreak 2
        $postPort = Get-LiveNodePort
        $facts.invariants.post = Invoke-InvariantRun -Port $postPort -RunId $run.RunId -Window 'post'
        $facts.drained = $drained
        $facts.bots_stopped = $botsStopped

        # ── 사실 ──
        $facts.metrics.after = (Get-EpMetricsTotal).Total
        $facts.orders_500 = [long]($facts.metrics.after.orders_500 - $facts.metrics.before.orders_500)
        $post = Get-EpLease
        $clusterAfter = Get-EpClusterLeader
        $tl.post = [ordered]@{ patroni_leader = $clusterAfter.Leader; timeline = $clusterAfter.Timeline; sync = $clusterAfter.Sync
            epoch = $(if ($post) { $post.Epoch } else { Get-EpLeaseEpoch }); app_leader = (Get-LeaderContainer) }
        if ($staleBase) {
            $facts.stale_write_delta = Get-StaleWriteDelta -Baseline $staleBase -After (Get-EpStaleCounts $oldEpoch)
        } elseif ($ExpectEpochChange) {
            $facts.stale_write_delta = $null
            $facts.stale_note = '승격을 관찰하지 못해 기준선이 없다'
        } else {
            $facts.stale_write_delta = 0
            $facts.stale_note = '주행 동안 epoch 가 바뀌지 않았다. 구 epoch 가 없다'
        }
        if ($tl.role_injected -eq 'primary') { $facts.rejoin = Get-EpRejoinFacts $tl.injected }
        $t5Raw = Invoke-Psql (Get-FirstTradeSql -OldEpoch $oldEpoch -T0Iso (ConvertTo-EpIso $t0))
        $facts.t5 = ConvertFrom-DbTimestamp $t5Raw
        if ($resumedAt) {
            $ft = Invoke-Psql ("SELECT min(created_at) AT TIME ZONE 'UTC' FROM event_journal WHERE event_type = 'TRADE' AND created_at > '{0}'" -f (ConvertTo-EpIso ($resumedAt - 5)))
            $facts.first_trade_after_resume = ConvertFrom-DbTimestamp $ft
        }
        $facts.duplicate_coid_orders = [long](Get-Scalar "SELECT count(*) FROM (SELECT user_id, client_order_id FROM command_journal WHERE command_type = 'NEW' AND client_order_id IS NOT NULL GROUP BY 1, 2 HAVING count(*) > 1) d" -Required)
        $facts.withdraw_duplicates = [long](Get-Scalar "SELECT count(*) FROM (SELECT user_id, idem_key FROM transfer_requests WHERE idem_key IS NOT NULL GROUP BY 1, 2 HAVING count(*) > 1) d" -Required)
        $facts.syncrep_waiters_after_resume = [long](Get-Scalar "SELECT count(*) FROM pg_stat_activity WHERE wait_event = 'SyncRep' AND application_name <> 'exchange-write-probe'" -Required)
        $end = Get-EpNow
        $tl.t_end = $end
        $etcdChangesAfter = Get-EpPromScalar 'sum(etcd_server_leader_changes_seen_total)'
        if ($null -ne $etcdChangesBefore -and $null -ne $etcdChangesAfter) { $facts.invalid.etcd_leader_changes = $etcdChangesAfter - $etcdChangesBefore }
        $winEnd = $(if ($resumedAt) { $resumedAt } else { $end })
        $win = [int][Math]::Max(15, $winEnd - ($t0 - 10))
        $facts.invalid.fsync_p99_s = Get-EpPromScalar "histogram_quantile(0.99, sum by (le) (increase(etcd_disk_wal_fsync_duration_seconds_bucket[${win}s])))" $winEnd
        $facts.invalid.peer_rtt_p99_s = Get-EpPromScalar "histogram_quantile(0.99, sum by (le) (increase(etcd_network_peer_round_trip_time_seconds_bucket[${win}s])))" $winEnd
        $facts.alerts_fired = [ordered]@{}
        foreach ($alert in @('EtcdMemberDown', 'EtcdQuorumLost', 'TradingSuspended', 'PatroniSyncStandbyMissing', 'NonMemberWalSender', 'PatroniFailoverOccurred')) {
            $v = Get-EpPromScalar "max(max_over_time(ALERTS{alertname=`"$alert`",alertstate=`"firing`"}[$([int]($end - $t0 + 5))s]))" $end
            $facts.alerts_fired[$alert] = ($v -eq 1)
        }
        $facts.failsafe_max = Get-EpPromScalar "max(max_over_time(patroni_failsafe_mode_is_active[$([int]($end - $t0 + 5))s]))" $end
    }
    catch [System.OperationCanceledException] {
        Write-Host '주입 없이 끝냈다(시계 가드).' -ForegroundColor Yellow
    }
    catch {
        # 주입 뒤 오류여도 아래에서 판정(원시 파일로)과 스택 정리를 한다. 오류 회차는 종료코드 1 이다.
        $failure = $_
        $facts.error = "$($_.Exception.Message) (line $($_.InvocationInfo.ScriptLineNumber))"
        Write-Host "실험 중 오류: $($facts.error)" -ForegroundColor Red
        Write-Host $_.ScriptStackTrace -ForegroundColor DarkGray
    }
    finally {
        # 정리는 판정보다 먼저 해도 된다. 판정은 원시 폴더의 파일만 읽는다.
        try { Stop-EpObserver $run } catch { Write-Host "관측기 정지 실패: $($_.Exception.Message)" -ForegroundColor Yellow }
        try { Stop-EpK6 $run | Out-Null } catch { }
        try { if ((& docker ps -a --filter name=^patroni-ackrec$ --format '{{.Names}}')) { Stop-EpRecorder $run } } catch { Write-Host "기록기 정지 실패: $($_.Exception.Message)" -ForegroundColor Yellow }
        try { $facts.container_log_sha256 = Save-EpContainerLogs $run } catch { }
        Write-EpJson (Join-Path $run.Raw 'timeline.json') $tl
        Write-EpJson (Join-Path $run.Raw 'facts.json') $facts
    }
    try {
        if ($skipped) { $exit = 0 }
        else {
        $verdictPath = Join-Path $run.Evidence "$($run.RunId).verdict.json"
        & $Global:EP.Python (Join-Path $Global:EP.ScaleDir 'patroni_axes.py') --scenario $Scenario --dir $run.Raw --out $verdictPath | Out-Host
        $exit = $LASTEXITCODE
        foreach ($f in @('timeline.json', 'facts.json', 'ackrec-verify.json')) {
            $src = Join-Path $run.Raw $f
            if (Test-Path $src) { Copy-Item $src (Join-Path $run.Evidence "$($run.RunId).$f") -Force }
        }
        $obs = Join-Path $run.Raw 'observe.jsonl'
        if (Test-Path $obs) { Copy-Item $obs (Join-Path $run.Evidence "$($run.RunId).observe.jsonl") -Force }
        $ack = Join-Path $run.Raw 'ackrec.jsonl'
        $hashes = [ordered]@{ ackrec_jsonl = $(if (Test-Path $ack) { (Get-FileHash $ack -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null })
            containers = $facts.container_log_sha256; raw_dir_outside_repo = $run.Raw }
        Write-EpJson (Join-Path $run.Evidence "$($run.RunId).hashes.json") $hashes
        $verdict = $(if (Test-Path $verdictPath) { (Get-Content $verdictPath -Raw | ConvertFrom-Json).verdict } else { 'ERROR' })
        Save-VerdictFile -ExperimentId $Scenario -RunId $run.RunId -Verdict $verdict -Metrics @{ verdictFile = (Split-Path $verdictPath -Leaf) } | Out-Host
        Write-Host "판정 $verdict ($verdictPath)"
        }
    }
    catch {
        Write-Host "판정 단계 오류: $($_.Exception.Message) (line $($_.InvocationInfo.ScriptLineNumber))" -ForegroundColor Red
        $exit = 1
    }
    finally {
        if (-not $KeepStack) { Stop-EpStack }
    }
    if ($failure) { $exit = 1 }
    return $exit
}

# 옛 Primary 재합류(E-P6a 기록). 되감기인지 재초기화인지를 Patroni 로그에서, 두 노드의 주요 표 건수·합이 같은지를 각 노드의
# 로컬 소켓 psql 로 본다. 기록만 하고 PASS 조건은 patroni_axes 가 정한다.
function Get-EpNodeTableFacts([string] $Db) {
    $sql = "SELECT json_build_object('command_journal', (SELECT count(*) FROM command_journal), " +
        "'event_journal', (SELECT count(*) FROM event_journal), 'orders', (SELECT count(*) FROM orders), " +
        "'trades', (SELECT count(*) FROM trades), 'max_cmd_seq', (SELECT max(seq) FROM command_journal), " +
        "'balances_sum', (SELECT coalesce(sum(available + locked), 0)::text FROM user_balances))"
    $raw = (& docker exec $Db timeout 30 psql -h /var/run/postgresql -U postgres -d exchange -Atq -c $sql 2>$null | Out-String).Trim()
    if (-not $raw) { return $null }
    return $raw | ConvertFrom-Json
}

function Get-EpRejoinFacts([string] $Old) {
    $log = (& cmd /c "docker logs $Old 2>&1" | Out-String)
    $method = if ($log -match 'pg_rewind') { 'rewind' } elseif ($log -match 'removing data directory|reinitializ') { 'reinit' } else { 'none-seen' }
    # 복제가 따라잡은 뒤 잰다. 부하는 이미 멈췄다(사후 run 앞의 드레인).
    Start-Sleep -Seconds 3
    $a = Get-EpNodeTableFacts 'patroni-db-1'
    $b = Get-EpNodeTableFacts 'patroni-db-2'
    $equal = $null
    if ($a -and $b) { $equal = (($a | ConvertTo-Json -Compress) -eq ($b | ConvertTo-Json -Compress)) }
    return [ordered]@{ old_primary = $Old; method = $method; nodes = [ordered]@{ 'patroni-db-1' = $a; 'patroni-db-2' = $b }; equal = $equal }
}

# ── 주입 동작 ──────────────────────────────────────────────────────────────

function Get-EpPrimaryContainer { return (Get-EpClusterLeader).Leader }
function Get-EpStandbyContainer {
    $c = Get-EpClusterLeader
    return @($c.Members | Where-Object { $_.name -ne $c.Leader } | ForEach-Object { $_.name }) | Select-Object -First 1
}
# DB 컨테이너와 같은 역할의 etcd 멤버(db-1 <-> etcd-1, db-2 <-> etcd-2). etcd-3 은 bot-1 역할이다.
function Get-EpEtcdFor([string] $Db) { return $Db.Replace('patroni-db-', 'patroni-etcd-') }

function Assert-EpContainer([string] $Name) {
    if ($Name -notmatch '^patroni-(db|etcd|api|haproxy|toxiproxy)-[0-9a-z]+$') { throw "이 실행기가 다루지 않는 컨테이너: $Name" }
}

# 멈춘 컨테이너를 다시 띄운다. DB 컨테이너의 호스트 포트(59008·59009)는 Windows 동적 포트 대역(49152~65535) 안이라, 멈춘 동안
# 다른 프로세스가 그 포트를 잡으면 docker start 가 bind 오류로 실패한다(2026-09-25 E-P2a: 다른 작업의 Gradle 시험 JVM 이
# 59009 를 잡음). 2초마다 다시 시도하고 끝내 실패하면 예외를 던져 회차를 오류로 끝낸다. 그대로 두면 엄격 동기 아래에서
# 사후 단계의 쓰기(ACK 적재)가 커밋 대기로 끝없이 멈춘다. 실패 기록은 돌려준다(timeline.start_failures).
function Start-EpContainer([string] $Name, [int] $TimeoutSec = 30) {
    Assert-EpContainer $Name
    $fails = @()
    $until = (Get-EpNow) + $TimeoutSec
    while ($true) {
        $out = (& docker start $Name 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -eq 0) { return , $fails }
        $fails += [ordered]@{ t = Get-EpNow; error = $out.Substring(0, [Math]::Min(200, $out.Length)) }
        Write-Host "docker start $Name 실패: $out" -ForegroundColor Yellow
        if ((Get-EpNow) -ge $until) { throw "docker start $Name 가 $TimeoutSec 초 안에 성공하지 못했다" }
        Start-Sleep -Seconds 2
    }
}
