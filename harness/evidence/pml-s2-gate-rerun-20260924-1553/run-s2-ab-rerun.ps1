# S2 관문 6-4 A/B 재측정(마켓분할_리더_S2_설계 6-4, 2026-09-24 사용자 결정: 5xx 는 리더가 낸 것만 세고 반복 측정 뒤 넘어간다).
# 폐기용 스택(compose 프로젝트 exchange-pml)에서만 돈다. 칸마다 roundtrip_probe 가 down -v 뒤 up 으로 새 스택을 세운다.
# 첫 주행(../pml-s2-gate-20260924-0440/run-s2-gate.ps1 -Phase AB)과 같은 러너·부하(600/s, 봇 20 ops, 12마켓, 부하 계정 4)와
# 같은 오버레이(append 히스토그램)·LB 집계 스크립트를 쓴다. 오버레이와 lb_status.py 는 첫 주행 폴더의 파일을 그대로 가리킨다.
# 더한 것: 칸마다 인스턴스별 5xx·append 수(Prometheus 창), 리더 인스턴스 판별, 칸 직전 호스트 CPU 표본.
# 사용: pwsh -NoProfile -File run-s2-ab-rerun.ps1 [-Cells 'A1=A,B1=B,A2=A,B2=B,A3=A,B3=B']
# 비밀 파일(harness/.env.chaos)은 compose 에 경로로만 넘긴다.
param(
    [string] $ImageA = 'exchange-chaos-app:mainref',
    [string] $ImageB = 'exchange-chaos-app:s2ref',
    [int] $Rate = 600,
    [string] $Cells = 'A1=A,B1=B,A2=A,B2=B,A3=A,B3=B',
    [string] $SubDir = 'ab'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONIOENCODING = 'utf-8'
$env:CHAOS_PROJECT = 'exchange-pml'
$env:COMPOSE_PROJECT_NAME = 'exchange-pml'
# 첫 주행과 같은 기본 프로필(dev,chaos-monkey). A(main)에는 prod-timeouts 프로필이 없다.
Remove-Item Env:CHAOS_SPRING_PROFILES -ErrorAction SilentlyContinue
Remove-Item Env:SCALE_LOAD_KEYS -ErrorAction SilentlyContinue
Remove-Item Env:SCALE_MARKETS -ErrorAction SilentlyContinue
$here = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $here '..\..\..')).Path
$first = Join-Path $repo 'harness\evidence\pml-s2-gate-20260924-0440'
$histOverlay = Join-Path $first 'override-append-histogram.yml'
$lbScript = Join-Path $first 'lb_status.py'
$env:CHAOS_EXTRA_COMPOSE = $histOverlay
$env:CHAOS_EVIDENCE_DIR = Join-Path $here 'chaos-runs'
. (Join-Path $repo 'harness\scripts\lib.ps1')
if ($Global:CHAOS.Project -ne 'exchange-pml') { throw "프로젝트 고정 실패: $($Global:CHAOS.Project)" }
$resolved = (Invoke-Compose config --format json | ConvertFrom-Json).name
if ($resolved -ne 'exchange-pml') { throw "compose 프로젝트 해석이 exchange-pml 이 아니다: $resolved" }

$phaseDir = Join-Path $here $SubDir
New-Item -ItemType Directory -Force -Path $phaseDir | Out-Null
$log = Join-Path $phaseDir 'commands.log'

function Write-Log {
    param([string] $Text)
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'), $Text
    Add-Content -Path $log -Value $line -Encoding utf8
    Write-Host $line
}
function Save-Text { param([string] $Path, [string] $Text) [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false)) }
function Save-Json { param([string] $Path, $Obj) Save-Text $Path ($Obj | ConvertTo-Json -Depth 12) }

# 하네스 SQL(summary.sql)을 소유자 로그인으로 통째로 돌린다. 첫 주행 스크립트와 같다.
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
    $na = @($rows | Where-Object { $_.verdict -eq 'N/A' })
    $res = [ordered]@{ rc = $rc; checks = $rows.Count; fail = @($fail | ForEach-Object { '{0}={1}' -f $_.code, $_.violatedRows })
        skip = @($skip | ForEach-Object { $_.code }); na = @($na | ForEach-Object { $_.code })
        c29 = (@($rows | Where-Object { $_.code -eq 'C29' }) | Select-Object -First 1).verdict
        violationsZero = ($rc -eq 0 -and $rows.Count -gt 0 -and $fail.Count -eq 0) }
    Write-Log ("SUMMARY {0} rc={1} checks={2} fail=[{3}] skip=[{4}] na=[{5}] C29={6}" -f $Name, $rc, $rows.Count, ($res.fail -join ','), ($res.skip -join ','), ($res.na -join ','), $res.c29)
    return $res
}

function Stop-BotsAndDrain {
    $code = Invoke-DockerQuiet 'stop chaos-bots'
    Write-Log "docker stop chaos-bots rc=$code"
    $drained = Wait-DrainZero -Sql $Global:CHAOS_DRAIN_SQL -TimeoutSec 120 -RequiredStreak 2
    Write-Log "drain=$drained"
    return [ordered]@{ botsStopped = ($code -eq 0); drained = $drained }
}

function Get-FlywayState {
    $raw = Invoke-Psql ("SELECT json_build_object('rows', count(*), 'max_version', max(version::int) FILTER (WHERE version IS NOT NULL), " +
        "'failed', count(*) FILTER (WHERE NOT success)) FROM flyway_schema_history")
    return ($raw | ConvertFrom-Json)
}

function Get-LogErrorCounts {
    $res = [ordered]@{}
    foreach ($c in 'chaos-api-1', 'chaos-api-2', 'chaos-bots') {
        $lines = @(& cmd /c "docker logs $c 2>&1")
        $res[$c] = [ordered]@{
            permissionDenied = @($lines | Select-String -SimpleMatch 'permission denied').Count
            sqlstate42501 = @($lines | Select-String -SimpleMatch '42501').Count
            undefinedFunction = @($lines | Select-String -Pattern 'function .* does not exist').Count
            errorLines = @($lines | Select-String -Pattern ' ERROR ').Count }
    }
    return $res
}

function Invoke-PromScalar {
    param([string] $Expr, [long] $At)
    $r = Invoke-RestMethod -Uri ('http://localhost:59090/api/v1/query?query={0}&time={1}' -f [uri]::EscapeDataString($Expr), $At) -TimeoutSec 20
    return @($r.data.result)
}

# k6 창의 append 평균·p95, 5xx(전체·인스턴스별), 인스턴스별 append 수. 창 끝은 probe 가 적은 DB 시각이다.
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
    $byInst = [ordered]@{
        http5xxByInstance = "sum by (instance) (increase(http_server_requests_seconds_count{job=`"exchange-api`",status=~`"5..`"}[${sec}s]))"
        http5xxByInstanceUriStatus = "sum by (instance, method, uri, status) (increase(http_server_requests_seconds_count{job=`"exchange-api`",status=~`"5..`"}[${sec}s]))"
        appendCountByInstance = "sum by (instance) (increase(exchange_journal_append_seconds_count{job=`"exchange-api`"}[${sec}s]))"
        httpAllByInstance = "sum by (instance) (increase(http_server_requests_seconds_count{job=`"exchange-api`"}[${sec}s]))"
    }
    $out = [ordered]@{ from = $FromDb; to = $ToDb; seconds = $sec; evalAt = $at }
    foreach ($k in $qs.Keys) {
        try { $v = Invoke-PromScalar $qs[$k] $at; $out[$k] = $(if ($v.Count -gt 0) { [double]$v[0].value[1] } else { $null }) }
        catch { $out[$k] = "error: $($_.Exception.Message)" }
    }
    foreach ($k in $byInst.Keys) {
        try {
            $rows = @(Invoke-PromScalar $byInst[$k] $at | ForEach-Object {
                    $m = [ordered]@{}; foreach ($p in $_.metric.PSObject.Properties) { $m[$p.Name] = $p.Value }
                    [ordered]@{ labels = $m; value = [double]$_.value[1] } })
            $out[$k] = $rows
        } catch { $out[$k] = "error: $($_.Exception.Message)" }
    }
    # 리더 인스턴스는 창 안에서 명령 저널 append 를 한 인스턴스다. 둘 이상이면 판별 실패로 남긴다.
    $appenders = @($out.appendCountByInstance | Where-Object { $_ -is [System.Collections.IDictionary] -and $_.value -gt 0 })
    $leaderInst = $(if ($appenders.Count -eq 1) { $appenders[0].labels.instance } else { $null })
    $out['leaderInstance'] = $leaderInst
    $out['appenderCount'] = $appenders.Count
    $l5 = $null; $f5 = 0.0
    foreach ($r in @($out.http5xxByInstance | Where-Object { $_ -is [System.Collections.IDictionary] })) {
        if ($leaderInst -and $r.labels.instance -eq $leaderInst) { $l5 = [double]$r.value } else { $f5 += [double]$r.value }
    }
    if ($leaderInst -and $null -eq $l5) { $l5 = 0.0 }
    $out['leader5xx'] = $l5
    $out['nonLeader5xx'] = $f5
    $allQ = [ordered]@{}
    foreach ($k in $qs.Keys) { $allQ[$k] = $qs[$k] }
    foreach ($k in $byInst.Keys) { $allQ[$k] = $byInst[$k] }
    $out['queries'] = $allQ
    return $out
}

# 노드 actuator 의 누적 5xx(스택을 세운 뒤 전부). 첫 주행과 같은 보조 값이다.
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

# 칸 직전 호스트 CPU(Windows 전체, 논리 프로세서 합의 백분율) 10초 표본과 CPU 상위 프로세스. 조용한 호스트의 근거다.
function Get-HostCpu {
    $s = @((Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 2 -MaxSamples 5).CounterSamples | ForEach-Object { [math]::Round($_.CookedValue, 1) })
    return [ordered]@{ samplesPct = $s; meanPct = [math]::Round(($s | Measure-Object -Average).Average, 1) }
}

Write-Log "PHASE AB-rerun project=$resolved repo=$repo cells=$Cells overlay=$histOverlay"
$cellList = @($Cells.Split(',') | ForEach-Object { $kv = $_.Trim().Split('='); @{ label = $kv[0]; arm = $kv[1]; image = $(if ($kv[1] -eq 'A') { $ImageA } else { $ImageB }) } })
$all = [ordered]@{ phase = 'AB-rerun'; rate = $Rate; imageA = $ImageA; imageB = $ImageB
    overlay = [ordered]@{ path = '../pml-s2-gate-20260924-0440/override-append-histogram.yml'; sha256 = (Get-FileHash $histOverlay -Algorithm SHA256).Hash.ToLower() }
    lbScript = [ordered]@{ path = '../pml-s2-gate-20260924-0440/lb_status.py'; sha256 = (Get-FileHash $lbScript -Algorithm SHA256).Hash.ToLower() }
    cells = @() }
foreach ($c in $cellList) {
    $env:CHAOS_APP_IMAGE = $c.image
    $out = Join-Path $phaseDir "$($c.label).json"
    $hostCpu = Get-HostCpu
    Write-Log "PROBE $($c.label) arm=$($c.arm) image=$($c.image) rate=$Rate hostCpuMeanPct=$($hostCpu.meanPct)"
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    Push-Location $repo
    try { & python harness/scale/roundtrip_probe.py --label $c.label --rate $Rate --out $out *> (Join-Path $phaseDir "probe-$($c.label).log"); $prc = $LASTEXITCODE }
    finally { Pop-Location; $ErrorActionPreference = $eap }
    Write-Log "PROBE $($c.label) rc=$prc"
    $cell = [ordered]@{ label = $c.label; arm = $c.arm; image = $c.image; imageId = (& docker image inspect $c.image --format '{{.Id}}' | Out-String).Trim(); probeRc = $prc; hostCpuBefore = $hostCpu }
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
        Write-Log ("CELL {0} accept/s={1} appendMean={2} appendP95={3} 5xx={4} leader={5} leader5xx={6}" -f $c.label, $cell.probe.acceptPerSec,
            $cell.prom.appendMeanMs, $cell.prom.appendP95Ms, $cell.prom.http5xx, $cell.prom.leaderInstance, $cell.prom.leader5xx)
    }
    $cell['lease'] = (Invoke-Psql "SELECT json_build_object('holder', holder, 'epoch', epoch, 'alive', lease_until > now()) FROM leader_lease WHERE resource='matching-engine'" | ConvertFrom-Json)
    $cell['node5xxCumulative'] = Get-Node5xx
    # 클라이언트가 받은 상태 코드(chaos-lb 접근 로그). 원문은 저장소 밖 임시 폴더에 두고 집계만 남긴다.
    $lbRaw = Join-Path ([System.IO.Path]::GetTempPath()) "pml-s2-rerun-lb-$($c.label).log"
    & cmd /c "docker logs chaos-lb > `"$lbRaw`" 2>&1"
    if (Test-Path $out) { & python $lbScript --log $lbRaw --probe $out --label $c.label --out (Join-Path $phaseDir "lb_status_$($c.label).json") | Out-Null }
    Remove-Item $lbRaw -ErrorAction SilentlyContinue
    $cell['drain'] = Stop-BotsAndDrain
    $cell['summary'] = Invoke-Summary "summary-$($c.label)"
    $cell['flyway'] = Get-FlywayState
    $cell['logErrors'] = Get-LogErrorCounts
    $all.cells += $cell
    Save-Json (Join-Path $phaseDir 'result.json') $all
}
Write-Log 'AB-rerun done. 마지막 칸의 스택은 남아 있다. 끝나면 -p exchange-pml down -v 로 버린다'
