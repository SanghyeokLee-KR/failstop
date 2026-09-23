# S2 관문 6-4 주행(마켓분할_리더_S2_설계 6-4). 폐기용 스택(compose 프로젝트 exchange-pml)에서만 돈다.
#   E2E      : S2 이미지(B)를 앱 역할 로그인으로 띄워 리더·주문·체결·스냅샷과 C01~C29 를 본다.
#   AB       : 같은 날 같은 스택에서 A(main ec8ef8e) 두 번, B(S2) 한 번을 roundtrip_probe 600/s 로 돈다.
#   Rollback : AB 의 B 주행이 남긴 V48 DB 에서 B 를 내리고 A 를 앱 역할 로그인으로 다시 띄운다.
# 사용: pwsh -NoProfile -File run-s2-gate.ps1 -Phase E2E|AB|Rollback
# 보존 스택(exchange-chaos)과 보존 볼륨을 건드리지 않도록 프로젝트를 네 곳에서 고정하고 compose 해석값을 확인한 뒤 시작한다.
# 비밀 파일(harness/.env.chaos)은 compose 에 경로로만 넘긴다. 런타임 로그인 비밀번호는 실행마다 무작위로 만들고 적지 않는다.
param(
    [Parameter(Mandatory = $true)][ValidateSet('E2E', 'AB', 'Rollback')][string] $Phase,
    [string] $ImageA = 'exchange-chaos-app:mainref',
    [string] $ImageB = 'exchange-chaos-app:pml',
    [int] $Rate = 600,
    # AB 칸 구성. 본 주행은 기본값(A1, A2, B)이다. 원인 좁히기 추가 주행은 -Cells 'B2=B,A3=A' -SubDir ab-extra 로 돌렸다.
    [string] $Cells = 'A1=A,A2=A,B=B',
    [string] $SubDir = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONIOENCODING = 'utf-8'
$env:CHAOS_PROJECT = 'exchange-pml'
$env:COMPOSE_PROJECT_NAME = 'exchange-pml'
# S2 관문은 기본 프로필(dev,chaos-monkey)로 돈다. A(main)에는 prod-timeouts 프로필이 없어 같은 조건을 만들 수 없다.
Remove-Item Env:CHAOS_SPRING_PROFILES -ErrorAction SilentlyContinue
Remove-Item Env:CHAOS_EXTRA_COMPOSE -ErrorAction SilentlyContinue
$here = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $here '..\..\..')).Path
$env:CHAOS_EVIDENCE_DIR = Join-Path $here 'chaos-runs'
. (Join-Path $repo 'harness\scripts\lib.ps1')
if ($Global:CHAOS.Project -ne 'exchange-pml') { throw "프로젝트 고정 실패: $($Global:CHAOS.Project)" }
$resolved = (Invoke-Compose config --format json | ConvertFrom-Json).name
if ($resolved -ne 'exchange-pml') { throw "compose 프로젝트 해석이 exchange-pml 이 아니다: $resolved" }

$phaseDir = Join-Path $here $(if ($SubDir) { $SubDir } else { $Phase.ToLower() })
New-Item -ItemType Directory -Force -Path $phaseDir | Out-Null
$log = Join-Path $phaseDir 'commands.log'
$appOverlay = Join-Path $here 'override-app-login.yml'
$histOverlay = Join-Path $here 'override-append-histogram.yml'

function Write-Log {
    param([string] $Text)
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'), $Text
    Add-Content -Path $log -Value $line -Encoding utf8
    Write-Host $line
}
function Save-Text { param([string] $Path, [string] $Text) [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false)) }
function Save-Json { param([string] $Path, $Obj) Save-Text $Path ($Obj | ConvertTo-Json -Depth 12) }

# compose 를 오버레이와 함께 부른다. Invoke-ComposeQuiet 와 같은 이유로 cmd /c 로 출력 핸들을 끊는다.
function Invoke-ComposeWith {
    param([string[]] $Overlays, [string] $ComposeArgs)
    $files = "-f `"$($Global:CHAOS.ComposeFile)`""
    foreach ($o in $Overlays) { $files += " -f `"$o`"" }
    $cmd = "docker compose -p $($Global:CHAOS.Project) $files --env-file `"$($Global:CHAOS.EnvFile)`" $ComposeArgs"
    Write-Log "COMPOSE $($Global:CHAOS.Project) overlays=[$(($Overlays | ForEach-Object { Split-Path $_ -Leaf }) -join ',')] $ComposeArgs image=$env:CHAOS_APP_IMAGE"
    & cmd /c "$cmd >nul 2>nul"
    $rc = $LASTEXITCODE
    Write-Log "COMPOSE rc=$rc"
    return $rc
}

function Get-DbNow { return (Invoke-Psql "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.MS')") }

function Get-Lease {
    $raw = Invoke-Psql "SELECT json_build_object('holder', holder, 'epoch', epoch, 'alive', lease_until > now()) FROM leader_lease WHERE resource='matching-engine'"
    if (-not $raw) { return $null }
    return ($raw | ConvertFrom-Json)
}

function Wait-NewLeader {
    param([long] $AfterEpoch, [int] $TimeoutSec = 240)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try { $l = Get-Lease; if ($l -and $l.alive -and [long]$l.epoch -gt $AfterEpoch) { return $l } } catch { }
        Start-Sleep -Seconds 2
    }
    return $null
}

# 런타임 로그인을 배포와 같은 SQL 로 만든다. 값은 docker exec -e NAME 으로 셸 환경에서 넘겨 명령줄에 남기지 않는다.
function Initialize-RuntimeRoles {
    if (-not $env:PML_APP_PASSWORD) {
        foreach ($k in 'PML_APP_PASSWORD', 'PML_BOT_PASSWORD', 'PML_READ_PASSWORD') {
            Set-Item -Path "Env:$k" -Value ([guid]::NewGuid().ToString('N'))
        }
    }
    $env:PML_APP_USER = 'pml_app'; $env:PML_BOT_USER = 'pml_bot'; $env:PML_READ_USER = 'pml_read'
    $env:DB_USER = $env:PML_APP_USER; $env:DB_PASSWORD = $env:PML_APP_PASSWORD
    $env:BOT_DB_USER = $env:PML_BOT_USER; $env:BOT_DB_PASSWORD = $env:PML_BOT_PASSWORD
    $env:READ_DB_USER = $env:PML_READ_USER; $env:READ_DB_PASSWORD = $env:PML_READ_PASSWORD
    $sql = [System.IO.File]::ReadAllText((Join-Path $repo 'deploy\provision-runtime-roles.sql'))
    Write-Log 'RUN provision-runtime-roles.sql (docker exec -i chaos-pg psql -U exchange, 로그인 pml_app·pml_bot·pml_read)'
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $out = ($sql | & docker exec -i -e DB_USER -e DB_PASSWORD -e BOT_DB_USER -e BOT_DB_PASSWORD -e READ_DB_USER -e READ_DB_PASSWORD `
            $Global:CHAOS.Pg psql -U $Global:CHAOS.PgUser -d $Global:CHAOS.PgDb -X -q 2>&1 | Out-String)
        $rc = $LASTEXITCODE
    } finally { $ErrorActionPreference = $eap }
    Save-Text (Join-Path $phaseDir 'provision.log') $out
    Write-Log "provision rc=$rc"
    if ($rc -ne 0) { throw "런타임 로그인 준비 실패(rc=$rc)" }
    $roles = Invoke-Psql ("SELECT json_agg(json_build_object('role', r.rolname, 'super', r.rolsuper, 'login', r.rolcanlogin, " +
        "'member_of', (SELECT array_agg(g.rolname ORDER BY g.rolname) FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid WHERE m.member = r.oid)) ORDER BY r.rolname) " +
        "FROM pg_roles r WHERE r.rolname IN ('pml_app','pml_bot','pml_read')")
    Save-Text (Join-Path $phaseDir 'runtime_roles.json') $roles
    return ($roles | ConvertFrom-Json)
}

# 하네스 SQL(summary.sql)을 소유자 로그인으로 통째로 돌린다. C01~C29 전부의 위반 행 수다.
function Invoke-Summary {
    param([string] $Name)
    $sqlText = [System.IO.File]::ReadAllText((Join-Path $repo 'harness\sql\summary.sql'))
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $out = ($sqlText | & docker exec -i $Global:CHAOS.Pg psql -U $Global:CHAOS.PgUser -d $Global:CHAOS.PgDb -X -q -t -A -F '|' 2>&1 | Out-String)
        $rc = $LASTEXITCODE
    } finally { $ErrorActionPreference = $eap }
    Save-Text (Join-Path $phaseDir "$Name.txt") $out
    $rows = @()
    foreach ($line in ($out -split "`r?`n")) {
        $f = $line.Split('|')
        if ($f.Count -eq 6 -and $f[0] -match '^C\d') {
            $rows += [ordered]@{ code = $f[0]; check = $f[1]; severity = $f[2]; scope = $f[3]
                violatedRows = $(if ($f[4] -eq '') { $null } else { [long]$f[4] }); verdict = $f[5] }
        }
    }
    $fail = @($rows | Where-Object { $_.verdict -eq 'FAIL' })
    $skip = @($rows | Where-Object { $_.verdict -eq 'SKIP' })
    $res = [ordered]@{ rc = $rc; checks = $rows.Count; fail = @($fail | ForEach-Object { '{0}={1}' -f $_.code, $_.violatedRows })
        skip = @($skip | ForEach-Object { $_.code }); c29 = (@($rows | Where-Object { $_.code -eq 'C29' }) | Select-Object -First 1).verdict
        allZero = ($rc -eq 0 -and $rows.Count -gt 0 -and $fail.Count -eq 0 -and $skip.Count -eq 0) }
    Write-Log ("SUMMARY {0} rc={1} checks={2} fail=[{3}] skip=[{4}] C29={5}" -f $Name, $rc, $rows.Count, ($res.fail -join ','), ($res.skip -join ','), $res.c29)
    return $res
}

function Stop-BotsAndDrain {
    $code = Invoke-DockerQuiet 'stop chaos-bots'
    Write-Log "docker stop chaos-bots rc=$code"
    $drained = Wait-DrainZero -Sql $Global:CHAOS_DRAIN_SQL -TimeoutSec 120 -RequiredStreak 2
    Write-Log "drain=$drained"
    return [ordered]@{ botsStopped = ($code -eq 0); drained = $drained }
}

# 쓰기 증거. 전환 시각 뒤에 새 epoch 로 남은 행을 센다.
function Get-WriteCounts {
    param([long] $Epoch, [string] $SinceUtc)
    $sql = "SELECT json_build_object(" +
        "'cmd_epoch', (SELECT count(*) FROM command_journal WHERE fencing_token = $Epoch), " +
        "'evt_epoch', (SELECT count(*) FROM event_journal WHERE fencing_epoch = $Epoch), " +
        "'trade_evt_epoch', (SELECT count(*) FROM event_journal WHERE fencing_epoch = $Epoch AND event_type = 'TRADE'), " +
        "'snapshots_epoch', (SELECT count(*) FROM snapshots WHERE fencing_epoch = $Epoch), " +
        "'snapshot_markets_epoch', (SELECT count(DISTINCT market_id) FROM snapshots WHERE fencing_epoch = $Epoch), " +
        "'orders_since', (SELECT count(*) FROM orders WHERE created_at > '$SinceUtc'::timestamp AT TIME ZONE 'UTC'), " +
        "'trades_since', (SELECT count(*) FROM trades WHERE created_at > '$SinceUtc'::timestamp AT TIME ZONE 'UTC'))"
    $raw = Invoke-Psql $sql
    if (-not $raw) { return $null }
    return ($raw | ConvertFrom-Json)
}

function Wait-Writes {
    param([long] $Epoch, [string] $SinceUtc, [int] $TimeoutSec = 300)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $w = $null
    while ((Get-Date) -lt $deadline) {
        try { $w = Get-WriteCounts -Epoch $Epoch -SinceUtc $SinceUtc } catch { $w = $null }
        if ($w -and $w.cmd_epoch -gt 0 -and $w.trade_evt_epoch -gt 0 -and $w.snapshots_epoch -gt 0 -and $w.orders_since -gt 0 -and $w.trades_since -gt 0) { return $w }
        Start-Sleep -Seconds 5
    }
    return $w
}

# 앱·봇 연결이 어느 로그인으로 붙었는지. api 는 toxiproxy 를 거치므로 그 주소로 가른다.
function Get-ConnectionLogins {
    $ips = [ordered]@{}
    foreach ($c in 'chaos-toxiproxy', 'chaos-bots', 'chaos-api-3') {
        $nets = (& cmd /c "docker inspect -f `"{{json .NetworkSettings.Networks}}`" $c 2>nul" | Out-String).Trim()
        if (-not $nets) { continue }
        $n = ($nets | ConvertFrom-Json -AsHashtable)[$Global:CHAOS.Network]
        if ($n -and $n['IPAddress']) { $ips[$c] = $n['IPAddress'] }
    }
    $raw = Invoke-Psql ("SELECT coalesce(json_agg(json_build_object('usename', usename, 'client_addr', host(client_addr), 'n', n)), '[]'::json) FROM " +
        "(SELECT usename, client_addr, count(*) AS n FROM pg_stat_activity WHERE datname = 'exchange' AND backend_type = 'client backend' GROUP BY 1, 2) s")
    $rows = @($raw | ConvertFrom-Json)
    $viaProxy = @($rows | Where-Object { $_.client_addr -eq $ips['chaos-toxiproxy'] })
    $viaBots = @($rows | Where-Object { $_.client_addr -eq $ips['chaos-bots'] })
    return [ordered]@{ ips = $ips; rows = $rows
        apiLogins = @($viaProxy | ForEach-Object { $_.usename } | Sort-Object -Unique)
        botLogins = @($viaBots | ForEach-Object { $_.usename } | Sort-Object -Unique) }
}

function Get-ContainerFacts {
    $out = [ordered]@{}
    foreach ($c in 'chaos-api-1', 'chaos-api-2', 'chaos-bots') {
        $img = (& docker inspect -f '{{.Image}}' $c 2>$null | Out-String).Trim()
        $envs = @(& docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' $c 2>$null)
        $user = ($envs | Where-Object { $_ -like 'SPRING_DATASOURCE_USERNAME=*' } | Select-Object -First 1)
        $fly = ($envs | Where-Object { $_ -like 'SPRING_FLYWAY_ENABLED=*' } | Select-Object -First 1)
        $out[$c] = [ordered]@{ imageId = $img; datasourceUser = "$user"; flyway = "$fly" }
    }
    return $out
}

# 권한 거부(42501)와 리스 함수 부재(42883) 흔적을 컨테이너 로그에서 센다. 값은 행 수이고 로그 본문은 저장하지 않는다.
function Get-LogErrorCounts {
    param([string[]] $Containers = @('chaos-api-1', 'chaos-api-2', 'chaos-bots'))
    $res = [ordered]@{}
    foreach ($c in $Containers) {
        $lines = @(& cmd /c "docker logs $c 2>&1")
        $res[$c] = [ordered]@{
            permissionDenied = @($lines | Select-String -SimpleMatch 'permission denied').Count
            sqlstate42501 = @($lines | Select-String -SimpleMatch '42501').Count
            undefinedFunction = @($lines | Select-String -Pattern 'function .* does not exist').Count
            errorLines = @($lines | Select-String -Pattern ' ERROR ').Count }
    }
    return $res
}

function Get-FlywayState {
    $raw = Invoke-Psql ("SELECT json_build_object('rows', count(*), 'max_version', max(version::int) FILTER (WHERE version IS NOT NULL), " +
        "'failed', count(*) FILTER (WHERE NOT success), 'last', (SELECT json_build_object('version', version, 'installed_by', installed_by, 'installed_on', installed_on) " +
        "FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1)) FROM flyway_schema_history")
    return ($raw | ConvertFrom-Json)
}

function Invoke-AppRoleRun {
    param([string] $Image, [string] $Label)
    $env:CHAOS_APP_IMAGE = $Image
    $imageId = (& docker image inspect $Image --format '{{.Id}}' | Out-String).Trim()
    $before = Get-Lease
    $e0 = $(if ($before) { [long]$before.epoch } else { 0 })
    $since = Get-DbNow
    Write-Log "APP-ROLE $Label image=$Image epoch0=$e0 since=$since"
    $rc = Invoke-ComposeWith @($appOverlay) 'up -d --no-deps --force-recreate chaos-api-1 chaos-api-2 chaos-bots'
    if ($rc -ne 0) { throw "앱 역할 로그인 재기동 실패(rc=$rc)" }
    $leader = Wait-NewLeader -AfterEpoch $e0 -TimeoutSec 240
    if (-not $leader) { Write-Log 'NO LEADER within 240s'; return [ordered]@{ label = $Label; leader = $null; verdict = 'FAIL'; reason = '앱 역할 로그인으로 리더가 서지 않았다' } }
    Write-Log "leader holder=$($leader.holder) epoch=$($leader.epoch)"
    $writes = Wait-Writes -Epoch ([long]$leader.epoch) -SinceUtc $since -TimeoutSec 300
    $conns = Get-ConnectionLogins
    $facts = Get-ContainerFacts
    $drain = Stop-BotsAndDrain
    $sum = Invoke-Summary "summary-$Label"
    $logs = Get-LogErrorCounts
    $imagesOk = @($facts.Values | Where-Object { $_.imageId -ne $imageId }).Count -eq 0
    $loginOk = ($conns.apiLogins.Count -eq 1 -and $conns.apiLogins[0] -eq 'pml_app' -and $conns.botLogins.Count -eq 1 -and $conns.botLogins[0] -eq 'pml_bot')
    $writesOk = ($writes -and $writes.cmd_epoch -gt 0 -and $writes.trade_evt_epoch -gt 0 -and $writes.snapshots_epoch -gt 0 -and $writes.orders_since -gt 0 -and $writes.trades_since -gt 0)
    $verdict = $(if ($imagesOk -and $loginOk -and $writesOk -and $sum.allZero -and $drain.drained) { 'PASS' } else { 'FAIL' })
    $res = [ordered]@{ label = $Label; image = $Image; imageId = $imageId; imagesOk = $imagesOk; epochBefore = $e0; leader = $leader; since = $since
        writes = $writes; writesOk = $writesOk; connections = $conns; loginOk = $loginOk; containers = $facts; drain = $drain
        summary = $sum; logErrors = $logs; verdict = $verdict }
    Write-Log "APP-ROLE $Label verdict=$verdict imagesOk=$imagesOk loginOk=$loginOk writesOk=$writesOk invariantsZero=$($sum.allZero)"
    return $res
}

# Prometheus 에서 k6 창의 append 평균·p95 와 5xx 증가를 읽는다. 창 끝은 probe 가 적은 DB 시각이다.
function Get-PromWindow {
    param([string] $FromDb, [string] $ToDb)
    $t0 = [DateTimeOffset]::Parse(($FromDb.Trim() + 'Z').Replace(' ', 'T').Replace('+00Z', 'Z'))
    $t1 = [DateTimeOffset]::Parse(($ToDb.Trim() + 'Z').Replace(' ', 'T').Replace('+00Z', 'Z'))
    $sec = [int][math]::Ceiling(($t1 - $t0).TotalSeconds)
    $at = $t1.ToUnixTimeSeconds()
    $qs = [ordered]@{
        appendMeanMs = "1000 * sum(increase(exchange_journal_append_seconds_sum[${sec}s])) / sum(increase(exchange_journal_append_seconds_count[${sec}s]))"
        appendP95Ms = "1000 * histogram_quantile(0.95, sum by (le) (increase(exchange_journal_append_seconds_bucket[${sec}s])))"
        appendP99Ms = "1000 * histogram_quantile(0.99, sum by (le) (increase(exchange_journal_append_seconds_bucket[${sec}s])))"
        appendCount = "sum(increase(exchange_journal_append_seconds_count[${sec}s]))"
        http5xx = "sum(increase(http_server_requests_seconds_count{status=~`"5..`"}[${sec}s])) or vector(0)"
        httpAll = "sum(increase(http_server_requests_seconds_count[${sec}s]))"
        orders201 = "sum(increase(http_server_requests_seconds_count{uri=`"/api/v1/orders`",method=`"POST`",status=`"201`"}[${sec}s]))"
        writerApplyPerSec = "sum(increase(exchange_writer_apply_count_total[${sec}s])) / $sec"
        tradesPerSec = "sum(increase(exchange_trades_total[${sec}s])) / $sec"
        sinkMsPerApply = "1000 * sum(increase(exchange_writer_sink_nanos_total[${sec}s])) / 1e9 / sum(increase(exchange_writer_apply_count_total[${sec}s]))"
        prologueMsPerTx = "1000 * sum(increase(exchange_journal_prologue_nanos_total[${sec}s])) / 1e9 / sum(increase(exchange_journal_intx_count_total[${sec}s]))"
        insertMsPerTx = "1000 * sum(increase(exchange_journal_insert_nanos_total[${sec}s])) / 1e9 / sum(increase(exchange_journal_intx_count_total[${sec}s]))"
    }
    $out = [ordered]@{ from = $FromDb; to = $ToDb; seconds = $sec; evalAt = $at }
    foreach ($k in $qs.Keys) {
        try {
            $r = Invoke-RestMethod -Uri ('http://localhost:59090/api/v1/query?query={0}&time={1}' -f [uri]::EscapeDataString($qs[$k]), $at) -TimeoutSec 20
            $v = @($r.data.result)
            $out[$k] = $(if ($v.Count -gt 0) { [double]$v[0].value[1] } else { $null })
        } catch { $out[$k] = "error: $($_.Exception.Message)" }
    }
    $out['queries'] = $qs
    return $out
}

# 노드 actuator 의 누적 5xx(스택을 세운 뒤 전부). Prometheus 창과 별개로 둔다.
function Get-Node5xx {
    $res = [ordered]@{}
    foreach ($p in 18080, 28080) {
        try {
            $t = (Invoke-RestMethod "http://localhost:$p/actuator/prometheus" -TimeoutSec 10) | Out-String
            $n = 0.0
            foreach ($m in [regex]::Matches($t, '(?m)^http_server_requests_seconds_count\{[^}]*status="5\d\d"[^}]*\}\s+(\S+)')) { $n += [double]$m.Groups[1].Value }
            $res["$p"] = $n
        } catch { $res["$p"] = "error: $($_.Exception.Message)" }
    }
    return $res
}

Write-Log "PHASE $Phase project=$resolved repo=$repo"

switch ($Phase) {
    'E2E' {
        $env:CHAOS_APP_IMAGE = $ImageB
        $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
        Write-Log "down -v rc=$code"
        $code = Invoke-ComposeQuiet 'up -d --wait'
        Write-Log "up (owner login, Flyway V1..V48 by api-1) rc=$code"
        if ($code -ne 0) { throw "compose up 실패 rc=$code" }
        $pre = [ordered]@{ flyway = Get-FlywayState; lease = (Invoke-Psql "SELECT json_agg(json_build_object('resource', resource, 'holder', holder, 'epoch', epoch) ORDER BY resource) FROM leader_lease" | ConvertFrom-Json)
            protocol = Invoke-Psql 'SELECT protocol FROM leader_protocol' }
        $roles = Initialize-RuntimeRoles
        $run = Invoke-AppRoleRun -Image $ImageB -Label 'B-app-role'
        $result = [ordered]@{ phase = 'E2E'; pre = $pre; roles = $roles; run = $run; verdict = $run.verdict }
        Save-Json (Join-Path $phaseDir 'result.json') $result
        $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
        Write-Log "down -v rc=$code"
        Write-Log "E2E verdict=$($run.verdict)"
    }
    'AB' {
        $env:CHAOS_EXTRA_COMPOSE = $histOverlay
        $cellList = @($Cells.Split(',') | ForEach-Object { $kv = $_.Trim().Split('='); @{ label = $kv[0]; image = $(if ($kv[1] -eq 'A') { $ImageA } else { $ImageB }) } })
        $all = [ordered]@{ phase = 'AB'; rate = $Rate; overlay = [ordered]@{ path = 'override-append-histogram.yml'; sha256 = (Get-FileHash $histOverlay -Algorithm SHA256).Hash.ToLower() }; cells = @() }
        foreach ($c in $cellList) {
            $env:CHAOS_APP_IMAGE = $c.image
            $out = Join-Path $phaseDir "$($c.label).json"
            Write-Log "PROBE $($c.label) image=$($c.image) rate=$Rate"
            $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
            Push-Location $repo
            try { & python harness/scale/roundtrip_probe.py --label $c.label --rate $Rate --out $out *> (Join-Path $phaseDir "probe-$($c.label).log"); $prc = $LASTEXITCODE }
            finally { Pop-Location; $ErrorActionPreference = $eap }
            Write-Log "PROBE $($c.label) rc=$prc"
            $cell = [ordered]@{ label = $c.label; image = $c.image; imageId = (& docker image inspect $c.image --format '{{.Id}}' | Out-String).Trim(); probeRc = $prc }
            if ($prc -eq 0 -and (Test-Path $out)) {
                $p = Get-Content $out -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
                $win = $p['접수(DB, 마켓별)']['창']
                $cell['window'] = $win
                $cell['prom'] = Get-PromWindow -FromDb $win['from'] -ToDb $win['to']
                $cell['probe'] = [ordered]@{ acceptPerSec = $p['접수/s']; tradesPerSec = $p['체결/s']; p95OrderMs = $p['p95(ms)']; p99OrderMs = $p['p99(ms)']
                    appendMs = $p['append 1회(ms)']; prologueMs = $p['  머리말 1회(ms)']; insertMs = $p['  저널삽입 1회(ms)']; commitMs = $p['  커밋(ms)']
                    lockHoldMs = $p['락 보유 1회(ms)']; applyPerSec = $p['적용 호출/s']; sinkMs = $p['sink 1회(ms)']; k6Rc = $p['k6_rc']
                    fsyncMs = $p['fsync 건당(ms)']; deadlocks = $p['deadlocks 증가']; connection = $p['연결']['판정']
                    containerImages = $p['메타']['컨테이너 이미지'] }
            }
            $cell['node5xxCumulative'] = Get-Node5xx
            # 클라이언트가 받은 상태 코드(chaos-lb 접근 로그). 원문은 저장소 밖에 두고 집계만 남긴다. 본 주행 A1 에는 없던 단계다.
            $lbRaw = Join-Path ([System.IO.Path]::GetTempPath()) "pml-s2-lb-$($c.label).log"
            & cmd /c "docker logs chaos-lb > `"$lbRaw`" 2>&1"
            if (Test-Path $out) { & python (Join-Path $here 'lb_status.py') --log $lbRaw --probe $out --label $c.label --out (Join-Path $phaseDir "lb_status_$($c.label).json") | Out-Null }
            $cell['drain'] = Stop-BotsAndDrain
            $cell['summary'] = Invoke-Summary "summary-$($c.label)"
            $cell['flyway'] = Get-FlywayState
            $cell['logErrors'] = Get-LogErrorCounts
            $all.cells += $cell
            Save-Json (Join-Path $phaseDir 'result.json') $all
        }
        Write-Log 'AB done. B 칸의 스택은 되돌림 주행을 위해 남긴다'
    }
    'Rollback' {
        # AB 의 B 칸이 남긴 스택(V48 DB, S2 이미지)을 그대로 쓴다.
        $pre = [ordered]@{ flyway = Get-FlywayState; lease = Get-Lease; containers = Get-ContainerFacts
            protocol = Invoke-Psql 'SELECT protocol FROM leader_protocol' }
        if ([int]$pre.flyway.max_version -ne 48) { throw "되돌림 전제 위반: DB 최대 버전이 48 이 아니다($($pre.flyway.max_version))" }
        $roles = Initialize-RuntimeRoles
        foreach ($c in 'chaos-bots', 'chaos-api-2', 'chaos-api-1') { $rc = Invoke-DockerQuiet "stop $c"; Write-Log "docker stop $c rc=$rc" }
        # 배포 경로(compose.migrate.yml 의 migrate 서비스와 같은 진입점). A 이미지의 Flyway 가 DB 에만 있는 V48 을 건너뛰는지 본다.
        # 소유자 비밀번호는 compose 파일 기본값(exchange)이다. chaos-pg-exporter 가 같은 값으로 붙는다.
        Write-Log "RUN migrate A: docker run --rm --network $($Global:CHAOS.Network) --entrypoint java $ImageA -Dloader.main=kr.exchange.deploy.MigrateSchema ..."
        $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        try {
            $mout = (& docker run --rm --network $Global:CHAOS.Network -e 'MIGRATION_JDBC_URL=jdbc:postgresql://chaos-pg:5432/exchange' `
                -e 'MIGRATION_DB_USER=exchange' -e 'MIGRATION_DB_PASSWORD=exchange' --entrypoint java $ImageA `
                '-Dloader.main=kr.exchange.deploy.MigrateSchema' -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher 2>&1 | Out-String)
            $mrc = $LASTEXITCODE
        } finally { $ErrorActionPreference = $eap }
        Save-Text (Join-Path $phaseDir 'migrate-A.log') $mout
        Write-Log "migrate A rc=$mrc"
        $afterMigrate = Get-FlywayState
        # 카오스 스택 경로. A 의 api-1 을 소유자 로그인·Spring Flyway 켠 채로 띄워 기동 검증을 지나는지 본다.
        $env:CHAOS_APP_IMAGE = $ImageA
        $rc = Invoke-ComposeWith @() 'up -d --no-deps --force-recreate chaos-api-1'
        $springLeader = Wait-NewLeader -AfterEpoch ([long]$pre.lease.epoch) -TimeoutSec 240
        $flywayLines = @(& cmd /c 'docker logs chaos-api-1 2>&1' | Select-String -Pattern 'Flyway|flyway|Migrating|up to date|future|Validated' | ForEach-Object { $_.Line })
        Save-Text (Join-Path $phaseDir 'spring-flyway-A.log') ($flywayLines -join "`n")
        $springOk = ($rc -eq 0 -and $null -ne $springLeader)
        Write-Log "A api-1 owner+Spring Flyway rc=$rc leader=$(if ($springLeader) { $springLeader.epoch } else { 'none' })"
        $afterSpring = Get-FlywayState
        $run = Invoke-AppRoleRun -Image $ImageA -Label 'A-rollback-app-role'
        $flywayUnchanged = ([int]$afterMigrate.rows -eq [int]$pre.flyway.rows -and [int]$afterSpring.rows -eq [int]$pre.flyway.rows -and [int]$afterSpring.failed -eq 0)
        $verdict = $(if ($mrc -eq 0 -and $springOk -and $flywayUnchanged -and $run.verdict -eq 'PASS') { 'PASS' } else { 'FAIL' })
        $result = [ordered]@{ phase = 'Rollback'; pre = $pre; roles = $roles; migrateRc = $mrc; afterMigrate = $afterMigrate
            springFlyway = [ordered]@{ rc = $rc; leader = $springLeader; afterFlyway = $afterSpring; ok = $springOk }
            flywayUnchanged = $flywayUnchanged; run = $run; verdict = $verdict }
        Save-Json (Join-Path $phaseDir 'result.json') $result
        Write-Log "Rollback verdict=$verdict"
    }
}
