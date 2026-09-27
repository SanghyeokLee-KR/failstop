# 시연 스택(compose 프로젝트 exchange-chaos) 스크립트 공용. demo-obs-up.ps1, demo-patroni-migrate.ps1, demo-db-failover.ps1 이
# dot-source 한다. compose 파일 목록·env 파일·주소를 한 곳에 둔다. 세 스크립트가 서로 다른 목록으로 compose 를 부르면
# 빠진 오버레이의 서비스가 기본값으로 다시 만들어진다(예: 알림 오버레이를 빼면 Alertmanager 가 기본 수신처로 돌아간다).
#
# DB 모드. 'patroni' 는 docker-compose.demo-patroni.yml 을 겹친 Patroni 엄격 동기 두 노드(ADR 0036), 'async' 는 종전
# chaos-pg + 비동기 대기 DB(ADR 0024)다. 판별은 Get-DemoDbMode 가 한다(Patroni 노드 컨테이너가 만들어져 있으면 patroni).
#
# ASCII 경로(C:\dev\exchange)로 부른다. 기존 컨테이너가 그 경로의 바인드 마운트로 만들어져 있어, 다른 경로로 부르면 compose 가
# 경로 차이만으로 컨테이너를 다시 만든다.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Global:Demo = [ordered]@{
    Harness        = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    Project        = 'exchange-chaos'
    PrometheusUrl  = 'http://localhost:59090'
    GrafanaUrl     = 'http://localhost:53001'
    LbUrl          = 'http://localhost:58080'
    # Patroni 멤버 이름 → 컨테이너, REST(호스트 루프백), 앱 망 IP 는 실행 중에 읽는다.
    Members        = [ordered]@{ 'db-1' = 'chaos-db-1'; 'db-2' = 'chaos-db-2' }
    PatroniRest    = [ordered]@{ 'db-1' = 'http://127.0.0.1:18008'; 'db-2' = 'http://127.0.0.1:28008' }
    Haproxy        = [ordered]@{ 'haproxy-1' = 'http://127.0.0.1:18404'; 'haproxy-2' = 'http://127.0.0.1:28404'
                                 'haproxy-3' = 'http://127.0.0.1:38404'; 'haproxy-b' = 'http://127.0.0.1:48404' }
    ApiPorts       = [ordered]@{ 'chaos-api-1' = 18080; 'chaos-api-2' = 28080 }
    Toolbox        = 'chaos-db-toolbox'
    EtcdServices   = @('chaos-etcd-1', 'chaos-etcd-2', 'chaos-etcd-3')
    HaproxyServices = @('chaos-haproxy-1', 'chaos-haproxy-2', 'chaos-haproxy-3', 'chaos-haproxy-b')
    ExporterServices = @('chaos-db-1-exporter', 'chaos-db-2-exporter')
    AsyncServices  = @('chaos-pg', 'chaos-pg-standby', 'chaos-pg-exporter', 'chaos-pg-standby-exporter')
}
$Global:Demo.Repo = [System.IO.Path]::GetFullPath((Join-Path $Global:Demo.Harness '..'))
$Global:Demo.BaseFile = Join-Path $Global:Demo.Harness 'docker-compose.chaos.yml'
# 겹치는 순서가 뜻을 가진다. 뒤 파일이 앞 파일을 덮는다. demo-patroni 는 DB 모드가 patroni 일 때만 맨 뒤에 붙인다.
$Global:Demo.Overlays = @(
    (Join-Path $Global:Demo.Harness 'docker-compose.demo-obs.yml'),      # 관측(MSP 화면·수집기·대기 DB·백업)
    (Join-Path $Global:Demo.Harness 'docker-compose.demo-report.yml'),   # Alertmanager → 보고 서비스(9088)
    (Join-Path $Global:Demo.Harness 'docker-compose.demo-bots.yml')      # 시연 봇 배치(리플 집중, 목표 OPS 25)
)
$Global:Demo.PatroniOverlay = Join-Path $Global:Demo.Harness 'docker-compose.demo-patroni.yml'
$Global:Demo.RootCompose = Join-Path $Global:Demo.Repo 'compose.yaml'
$Global:Demo.AdminOverlay = Join-Path $Global:Demo.Harness 'docker-compose.demo-admin.yml'
$Global:Demo.EnvChaos = Join-Path $Global:Demo.Harness '.env.chaos'
$Global:Demo.EnvObs = Join-Path $Global:Demo.Harness 'demo\obs.local\obs.env'
$Global:Demo.EnvPatroni = Join-Path $Global:Demo.Harness 'patroni\.env.patroni'
$Global:Demo.PatroniLocal = Join-Path $Global:Demo.Harness 'demo\patroni.local'
$Global:Demo.SdDir = Join-Path $Global:Demo.Harness 'demo\prometheus-sd.local'
$Global:Demo.ReporterToken = Join-Path $Global:Demo.Harness 'demo\reporting.local\reporter-token'

function Write-DemoStep([string] $Text) { Write-Host ''; Write-Host "== $Text" -ForegroundColor Cyan }

function Get-DemoNow { return [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000.0 }

# epoch 초 → 'yyyy-MM-ddTHH:mm:ss.fffZ'(docker logs --since 와 PostgreSQL 이 읽는 UTC 문자열)
function ConvertTo-IsoUtc([double] $Epoch) {
    return [DateTimeOffset]::FromUnixTimeMilliseconds([long]($Epoch * 1000)).UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
}

# 'patroni' 또는 'async'. Patroni 노드 컨테이너가 만들어져 있으면(멈춰 있어도) patroni 다. 전환 스크립트가 처음 만든다.
function Get-DemoDbMode {
    $names = @(& docker ps -a --filter 'name=^chaos-db-[12]$' --format '{{.Names}}' 2>$null)
    if ($names.Count -gt 0) { return 'patroni' }
    return 'async'
}

function Get-DemoComposeArgs([string] $Mode) {
    $fileArgs = @('-p', $Global:Demo.Project, '-f', $Global:Demo.BaseFile)
    foreach ($f in $Global:Demo.Overlays) { $fileArgs += @('-f', $f) }
    $envArgs = @()
    if ($Mode -eq 'patroni') {
        $fileArgs += @('-f', $Global:Demo.PatroniOverlay)
        # .env.patroni 를 맨 앞에 둔다. 뒤 파일이 이기므로 이름이 겹치면 카오스 스택 값이 쓰인다.
        $envArgs += @('--env-file', $Global:Demo.EnvPatroni)
    }
    $envArgs += @('--env-file', $Global:Demo.EnvChaos, '--env-file', $Global:Demo.EnvObs)
    return @($fileArgs + $envArgs + @('--profile', 'obs'))
}

# 첫 인자가 DB 모드('patroni'|'async'), 나머지가 compose 인자다. 인자 안의 배열은 펼친다.
# param 블록을 두지 않는다. [Parameter()] 가 붙은 고급 함수는 -d 를 -Debug 로 먹는다(lib.ps1 Invoke-Compose 주석).
# 출력을 파이프로 받지 않는다. 재기동한 컨테이너가 출력 핸들을 물려받아 파이프가 닫히지 않는 경우가 있다(lib.ps1).
function Invoke-DemoCompose {
    $mode = [string]$args[0]
    if ($mode -notin @('patroni', 'async')) { throw "Invoke-DemoCompose 의 첫 인자는 patroni 또는 async 다: $mode" }
    $rest = @(foreach ($a in @($args | Select-Object -Skip 1)) { $a })
    $composeArgs = Get-DemoComposeArgs $mode
    & docker compose @composeArgs @rest
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($rest -join ' ') 실패(종료코드 $LASTEXITCODE)" }
}

# 관리 평면은 저장소 루트 compose.yaml 에 있다(chaos-admin). --no-deps 로만 부른다. 빼면 compose 가 의존 서비스(chaos-api-1 등)를
# 이 목록에 없는 설정으로 다시 만든다.
function Invoke-DemoAdminCompose {
    $rest = @(foreach ($a in $args) { $a })
    & docker compose -f $Global:Demo.RootCompose -f $Global:Demo.AdminOverlay --env-file $Global:Demo.EnvChaos --profile admin @rest
    if ($LASTEXITCODE -ne 0) { throw "관리 평면 docker compose $($rest -join ' ') 실패(종료코드 $LASTEXITCODE)" }
}

function Get-DemoHealth([string] $Container) {
    $state = & docker inspect -f '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}' $Container 2>$null
    if ($LASTEXITCODE -ne 0) { return 'missing|' }
    return ($state | Out-String).Trim()
}

function Test-DemoRunning([string] $Container) { return (Get-DemoHealth $Container) -like 'running|*' }

function Wait-DemoUntil([string] $What, [scriptblock] $Condition, [int] $TimeoutSec = 180, [double] $PollSec = 2) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $ok = $false
        try { $ok = [bool](& $Condition) } catch { $ok = $false }
        if ($ok) { Write-Host "  $What"; return }
        Start-Sleep -Milliseconds ([int]($PollSec * 1000))
    }
    throw "$What 을(를) ${TimeoutSec}초 안에 확인하지 못했다"
}

function Get-DemoHttpCode([string] $Url, [int] $TimeoutSec = 5) {
    try {
        return [int](Invoke-WebRequest -Uri $Url -TimeoutSec $TimeoutSec -SkipHttpErrorCheck -UseBasicParsing).StatusCode
    } catch { return 0 }
}

function Get-DemoPromValue([string] $Query) {
    try {
        $r = Invoke-RestMethod -Uri "$($Global:Demo.PrometheusUrl)/api/v1/query" -Method Get -Body @{ query = $Query } -TimeoutSec 10
        return @($r.data.result)
    } catch { return @() }
}

# obs.env 의 값(복제·감시 계정 비밀번호). 값을 출력하지 않는다.
function Read-DemoObsEnv {
    if (-not (Test-Path -LiteralPath $Global:Demo.EnvObs)) { throw "$($Global:Demo.EnvObs) 가 없다. demo-obs-up.ps1 이 처음 한 번 만든다" }
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Global:Demo.EnvObs -Encoding utf8) {
        if ($line -match '^\s*([A-Z_]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
    }
    foreach ($name in 'DEMO_REPL_PASSWORD', 'DEMO_MONITOR_PASSWORD') {
        if ([string]::IsNullOrWhiteSpace($values[$name])) { throw "$($Global:Demo.EnvObs) 에 $name 이 없다" }
    }
    return $values
}

# ── Patroni ────────────────────────────────────────────────────────────────

# /cluster 를 답하는 노드에서 읽는다. 둘 다 답하지 않으면 $null.
function Get-DemoCluster {
    foreach ($base in $Global:Demo.PatroniRest.Values) {
        try { return Invoke-RestMethod -Uri "$base/cluster" -TimeoutSec 2 } catch { }
    }
    return $null
}

# 리더 멤버 이름, 동기 Standby 목록, 타임라인, 멤버 표. 클러스터를 읽지 못하면 $null.
function Get-DemoLeader {
    $c = Get-DemoCluster
    if (-not $c) { return $null }
    $members = @($c.members)
    $leader = @($members | Where-Object { $_.role -eq 'leader' }) | Select-Object -First 1
    $sync = @($members | Where-Object { $_.role -in @('sync_standby', 'quorum_standby') } | ForEach-Object { $_.name })
    return [pscustomobject]@{
        Leader   = $(if ($leader) { $leader.name } else { $null })
        Timeline = $(if ($leader) { $leader.timeline } else { $null })
        Sync     = $sync
        Members  = $members
    }
}

# 리더와 동기 Standby 가 모두 있고 두 멤버가 streaming 이면 참.
function Test-DemoClusterHealthy {
    $c = Get-DemoLeader
    if (-not $c -or -not $c.Leader -or $c.Sync.Count -lt 1) { return $false }
    $states = @($c.Members | ForEach-Object { $_.state })
    return (@($states | Where-Object { $_ -notin @('running', 'streaming') }).Count -eq 0)
}

function Show-DemoCluster {
    $c = Get-DemoLeader
    if (-not $c) { Write-Host '  Patroni /cluster 응답 없음' -ForegroundColor Yellow; return }
    foreach ($m in $c.Members) {
        $lag = if ($null -ne $m.PSObject.Properties['lag']) { $m.lag } else { '' }
        Write-Host ("  {0,-5} {1,-13} {2,-10} TL {3} lag {4}" -f $m.name, $m.role, $m.state, $m.timeline, $lag)
    }
}

# 도구 컨테이너의 psql(chaos-haproxy-b 를 거쳐 현재 Primary). 연결 5초, 질의 전체 $TimeoutSec 초. 실패하면 $null.
function Invoke-DemoSql([string] $Sql, [string] $Database = 'exchange', [int] $TimeoutSec = 30) {
    $out = & docker exec -e PGCONNECT_TIMEOUT=5 $Global:Demo.Toolbox timeout $TimeoutSec psql -d $Database -X -A -t -q -v ON_ERROR_STOP=1 -c $Sql 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return (($out | Out-String).Trim())
}

# 비밀번호를 명령 인자에 싣지 않는다. docker exec -e 이름(값 없이)은 이 프로세스의 환경 변수를 넘기고 psql 은 \getenv 로 읽는다.
function Invoke-DemoSqlScript([string] $Script, [string[]] $EnvNames = @(), [string] $Database = 'exchange') {
    $envArgs = @()
    foreach ($name in $EnvNames) { $envArgs += @('-e', $name) }
    $Script | & docker exec -i @envArgs $Global:Demo.Toolbox psql -d $Database -X -q -v ON_ERROR_STOP=1 -f - | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'SQL 스크립트 실패' }
}

# 수집기 확장 질의 파일. 학교 AWS 와 같은 harness/prometheus/pg-queries.yaml 에 Patroni 질의 둘을 붙인다. Patroni 질의의 멤버 이름은
# 실험 스택(patroni-db-1·2)이라 시연 스택 이름(db-1·2)으로 바꾼다. 바뀐 곳이 정확히 한 곳이 아니면 멈춘다(원본 구조 변경).
function Write-DemoPgQueries {
    $base = Get-Content -LiteralPath (Join-Path $Global:Demo.Harness 'prometheus\pg-queries.yaml') -Raw -Encoding utf8
    $patroni = Get-Content -LiteralPath (Join-Path $Global:Demo.Harness 'patroni\prometheus\pg-queries.patroni.yaml') -Raw -Encoding utf8
    $from = "ARRAY['patroni-db-1', 'patroni-db-2']"
    $count = ([regex]::Matches($patroni, [regex]::Escape($from))).Count
    if ($count -ne 1) { throw "pg-queries.patroni.yaml 의 멤버 목록이 한 곳이 아니라 $count 곳이다. 원본 구조가 바뀌었다" }
    $patroni = $patroni.Replace($from, "ARRAY['db-1', 'db-2']")
    $header = "# 자동 생성(harness/scripts/demo-common.ps1 Write-DemoPgQueries). 손으로 고치지 않는다.`n" +
        "# harness/prometheus/pg-queries.yaml + harness/patroni/prometheus/pg-queries.patroni.yaml(멤버 이름 db-1·db-2)`n"
    $text = $header + $base.TrimEnd() + "`n`n" + $patroni.TrimEnd() + "`n"
    New-Item -ItemType Directory -Force -Path $Global:Demo.PatroniLocal | Out-Null
    $path = Join-Path $Global:Demo.PatroniLocal 'pg-queries.yaml'
    $old = if (Test-Path -LiteralPath $path) { Get-Content -LiteralPath $path -Raw -Encoding utf8 } else { $null }
    if ($old -ne $text) {
        [System.IO.File]::WriteAllText($path, $text, [System.Text.UTF8Encoding]::new($false))
        return $true
    }
    return $false
}

# 조회 노드 수집 대상 파일(file_sd). 조정기가 붙이고 떼며 고친다. 여기서는 폴더와 파일이 있게 하고, 조정기가 돌지 않을 때를 위해
# 실제 상태로 맞춘다(chaos-api-3 이 떠 있고 조회 업스트림에 들어 있으면 대상).
function Initialize-DemoSd {
    New-Item -ItemType Directory -Force -Path $Global:Demo.SdDir | Out-Null
    $path = Join-Path $Global:Demo.SdDir 'reader.json'
    $lbConf = Get-Content -LiteralPath (Join-Path $Global:Demo.Harness 'nginx\nginx.conf') -Raw -Encoding utf8
    $inUpstream = $lbConf -match '(?m)^\s*server\s+chaos-api-3:8080[^;]*?(?<!down);'
    $running = Test-DemoRunning 'chaos-api-3'
    $text = if ($running -and $inUpstream) { "[`n  {`n    `"targets`": [`n      `"chaos-api-3:8080`"`n    ]`n  }`n]`n" } else { "[]`n" }
    [System.IO.File]::WriteAllText($path, $text, [System.Text.UTF8Encoding]::new($false))
    return ($running -and $inUpstream)
}

# 현재 리더 앱 컨테이너. 리스 holder 를 각 앱의 공개 상태가 아니라 harness 상태 API 로 읽는다(chaos-api-1 readiness 200 이면 리더).
function Get-DemoAppLeader {
    foreach ($entry in $Global:Demo.ApiPorts.GetEnumerator()) {
        if ((Get-DemoHttpCode "http://localhost:$($entry.Value)/actuator/health/readiness") -eq 200) { return $entry.Key }
    }
    return $null
}

# 시연 봇 오버레이(docker-compose.demo-bots.yml)의 시작 기준가를 지금 시세로 맞춘다. 그 파일 머리 주석의 절차다(기준가가 시세와
# 멀면 봇 재기동 직후 차트가 기준가 쪽으로 튄다). 시세를 읽지 못한 마켓은 그대로 둔다. 바꾼 값을 화면에 적는다.
function Update-DemoBotPrices {
    $path = $Global:Demo.Overlays | Where-Object { $_ -like '*demo-bots.yml' } | Select-Object -First 1
    if (-not $path -or -not (Test-Path -LiteralPath $path)) { return }
    $text = Get-Content -LiteralPath $path -Raw -Encoding utf8
    $changed = $false
    $ticks = @{}
    try {
        foreach ($mk in @((Invoke-RestMethod -Uri "$($Global:Demo.LbUrl)/api/v1/markets" -TimeoutSec 5).data)) { $ticks[$mk.market] = [decimal]$mk.tick_size }
    } catch { }
    $symbols = [regex]::Matches($text, 'EXCHANGE_SIM_MARKETS_(\d+)_SYMBOL:\s*(\S+)')
    foreach ($m in $symbols) {
        $index = $m.Groups[1].Value; $symbol = $m.Groups[2].Value
        try {
            $t = Invoke-RestMethod -Uri "$($Global:Demo.LbUrl)/api/v1/ticker?markets=$symbol" -TimeoutSec 5
            $price = [decimal](@($t.data)[0].trade_price)
        } catch { Write-Host "  $symbol 시세를 읽지 못해 기준가를 그대로 둔다" -ForegroundColor Yellow; continue }
        if ($price -le 0) { continue }
        $pattern = "(EXCHANGE_SIM_MARKETS_${index}_INITIALPRICE:\s*`")([0-9.]+)(`")"
        $old = [regex]::Match($text, $pattern)
        if (-not $old.Success) { continue }
        # 호가 단위(tick_size)의 배수로 맞춘다. 단위를 모르면 정수로 둔다.
        $tick = if ($ticks.ContainsKey($symbol) -and $ticks[$symbol] -gt 0) { $ticks[$symbol] } else { [decimal]1 }
        $new = [string]([decimal]::Round($price / $tick, 0) * $tick)
        if ($new.Contains('.')) { $new = $new.TrimEnd('0').TrimEnd('.') }
        if ($old.Groups[2].Value -ne $new) {
            $text = [regex]::Replace($text, $pattern, "`${1}$new`${3}")
            Write-Host "  봇 기준가 $symbol $($old.Groups[2].Value) → $new"
            $changed = $true
        }
    }
    if ($changed) { [System.IO.File]::WriteAllText($path, $text, [System.Text.UTF8Encoding]::new($false)) }
}

function Get-DemoTradingState([int] $Port) {
    try { return Invoke-RestMethod -Uri "http://localhost:$Port/api/v1/status" -TimeoutSec 3 } catch { return $null }
}

# 두 앱 노드가 모두 거래 상태 ACTIVE 면 참.
function Test-DemoTradingActive {
    foreach ($port in $Global:Demo.ApiPorts.Values) {
        $s = Get-DemoTradingState $port
        if (-not $s -or $s.trading_suspended) { return $false }
    }
    return $true
}
