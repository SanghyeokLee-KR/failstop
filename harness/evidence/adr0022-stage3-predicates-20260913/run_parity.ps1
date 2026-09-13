# ADR 0022 3단계 판정 술어의 실저널 대조(검증설계 §4-1 대역 축 술어의 대조 규칙).
# 카오스 스택에 두 마켓 이상 체결과 리더 kill 한 번(epoch 전이)을 만든 뒤 부하를 끊고, 같은 정지 저널에
# 권위 run(InvariantCheck), summary.sql, invariants.sql, 개정 전 술어를 대어 result.json 을 남긴다.
# 1상은 저널 그대로, 2상은 C23 레거시 팔과 C15 에 위반을 주입한 값이다. 3상은 앱 노드를 내리고 마켓별 대역 잎에
# 합성 행을 더한 저널에서 C22 문장 비용을 잰다(같은 절 비용 문턱). 끝나면 스택을 down -v 한다.
# 사용: pwsh -NoProfile -File harness/evidence/adr0022-stage3-predicates-20260913/run_parity.ps1
# 종료코드 0 = 대조 전부 일치, 양성 축 충족, 비용 PASS. 1 = 그중 하나라도 아님. 2 = 주행 실패.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$here = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $here '..\..\..')).Path
$scripts = Join-Path $repo 'harness\scripts'
$sqlDir = Join-Path $repo 'harness\sql'
. (Join-Path $scripts 'lib.ps1')

$BandChecks = @('C05', 'C15', 'C22', 'C23', 'C27')

function Invoke-PsqlFile([string] $Path, [string] $OutName) {
    $name = Split-Path $Path -Leaf
    & docker cp $Path "$($Global:CHAOS.Pg):/tmp/$name" 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker cp 실패: $name" }
    $out = Join-Path $here $OutName
    & docker exec $Global:CHAOS.Pg psql -X -U $Global:CHAOS.PgUser -d $Global:CHAOS.PgDb `
        -v ON_ERROR_STOP=1 -Atq -f "/tmp/$name" > $out 2> "$out.stderr"
    # 종료코드를 보지 않으면 문법 오류가 빈 출력, 곧 위반 0 으로 읽힌다.
    if ($LASTEXITCODE -ne 0) { throw "$name 실행 실패(rc=$LASTEXITCODE): $(Get-Content "$out.stderr" -Raw)" }
    if ((Get-Item "$out.stderr").Length -eq 0) { Remove-Item "$out.stderr" }
    return @(Get-Content $out -Encoding UTF8)
}

function ConvertFrom-LabelLines([string[]] $Lines) {
    $map = [ordered]@{}
    foreach ($line in $Lines) {
        $f = $line.Split('|')
        if ($f.Count -ne 2) { throw "label|value 형식 아님: $line" }
        $map[$f[0]] = [long]$f[1]
    }
    return $map
}

# 첫 열(팔 또는 파티션 이름)별 값. C05 는 gaps 열 합, C15·C27 은 rows 열 합, C22·C23 은 행 수다.
function Get-InvariantsArms([string[]] $Lines) {
    $sections = @{}
    $current = $null
    foreach ($line in $Lines) {
        if ($line.StartsWith('== ')) {
            $code = $line.Substring(3, $line.IndexOf(' ', 3) - 3)
            $current = if ($BandChecks -contains $code) { $sections[$code] = [ordered]@{}; $code } else { $null }
            continue
        }
        if (-not $current) { continue }
        $f = $line.Split('|')
        $value = switch ($current) {
            'C05' { [long]$f[2] }
            { $_ -in 'C15', 'C27' } { [long]$f[1] }
            default { 1 }
        }
        $arms = $sections[$current]
        $arms[$f[0]] = $(if ($arms.Contains($f[0])) { $arms[$f[0]] } else { 0 }) + $value
    }
    foreach ($code in $BandChecks) { if (-not $sections.ContainsKey($code)) { throw "invariants.sql 에 $code 절 없음" } }
    return $sections
}

$comparisons = [System.Collections.Generic.List[object]]::new()
$notRun = [System.Collections.Generic.List[string]]::new()
function Add-Comparison([string] $Phase, [string] $Name, $Left, $Right, [bool] $Gate = $true) {
    $comparisons.Add([ordered]@{ phase = $Phase; name = $Name; left = $Left; right = $Right; equal = ($Left -eq $Right); gate = $Gate })
}

function Invoke-Phase([string] $Phase, [int] $Port) {
    $legacy = ConvertFrom-LabelLines (Invoke-PsqlFile (Join-Path $here 'legacy_predicates.sql') "legacy_$Phase.txt")
    $run = Invoke-InvariantRun -Port $Port -RunId "adr0022-s3-parity-$Phase-$(Get-Date -Format yyyyMMddHHmmss)" -Window $Phase
    $run | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $here "authoritative_run_$Phase.json") -Encoding utf8
    $summaryLines = Invoke-PsqlFile (Join-Path $sqlDir 'summary.sql') "summary_$Phase.txt"
    $arms = Get-InvariantsArms (Invoke-PsqlFile (Join-Path $sqlDir 'invariants.sql') "invariants_$Phase.txt")
    $legacyAfter = ConvertFrom-LabelLines (Invoke-PsqlFile (Join-Path $here 'legacy_predicates.sql') "legacy_${Phase}_after.txt")

    foreach ($k in $legacy.Keys) { Add-Comparison $Phase "정지 확인 $k 대조 전 대 후" $legacy[$k] $legacyAfter[$k] }

    $app = @{}
    foreach ($c in $run.checks) { $app[$c.checkName.Split('_')[0]] = [long]$c.violatedRows }
    $summary = @{}
    foreach ($line in $summaryLines) {
        $f = $line.Split('|')
        if ($f.Count -ne 6) { throw "summary.sql 행 형식 불일치: $line" }
        $summary[$f[0]] = [long]$f[4]
    }
    foreach ($code in $BandChecks) {
        $armSum = 0
        foreach ($v in $arms[$code].Values) { $armSum += $v }
        Add-Comparison $Phase "$code InvariantCheck 대 summary.sql" $app[$code] $summary[$code]
        Add-Comparison $Phase "$code InvariantCheck 대 invariants.sql 팔 합" $app[$code] $armSum
    }
    foreach ($code in ($app.Keys | Where-Object { $BandChecks -notcontains $_ } | Sort-Object)) {
        if ($summary.ContainsKey($code)) {
            Add-Comparison $Phase "$code InvariantCheck 대 summary.sql(개정 대상 밖, 참고)" $app[$code] $summary[$code] $false
        }
    }

    Add-Comparison $Phase 'C05 대 구 C05' $app['C05'] $legacy['legacy_c05']
    Add-Comparison $Phase 'C15 + C27 대 구 C15' ($app['C15'] + $app['C27']) $legacy['legacy_c15']
    Add-Comparison $Phase 'C23 대 구 C23' $app['C23'] $legacy['legacy_c23']
    Add-Comparison $Phase 'C22 대 시간 축 팔 + 마켓 안 팔' $app['C22'] ($legacy['arm_time'] + $legacy['arm_market'])
    if ($legacy['legacy_c22'] -eq 0) {
        Add-Comparison $Phase 'C22 구 값 0 이면 마켓 안 팔 0' $legacy['arm_market'] 0
    }
    # 시간 축 팔 동치의 순서 전제는 가정하지 않고 잰다. 구 값이 0 보다 크면 합산에는 대조할 고정 관계가 없다.
    if ($legacy['timeline_id'] -eq 1 -and $legacy['created_at_regressions'] -eq 0) {
        Add-Comparison $Phase 'C22 시간 축 팔 대 구 C22' $legacy['arm_time'] $legacy['legacy_c22']
        if ($legacy['legacy_c22'] -eq 0) { Add-Comparison $Phase 'C22 구 값 0 이면 합산 0' $app['C22'] 0 }
    } else {
        $notRun.Add("$Phase C22 시간 축 팔 대 구 C22: timeline_id=$($legacy['timeline_id']), created_at_regressions=$($legacy['created_at_regressions'])")
    }
    Add-Comparison $Phase 'C05 대역 항 행 없음(N/A 근거)' $arms['C05'].Contains('band') $false
    Add-Comparison $Phase 'C23 대역 팔 행 없음(N/A 근거)' $arms['C23'].Contains('band') $false

    return [ordered]@{
        legacy = $legacy
        app = [ordered]@{ C05 = $app['C05']; C15 = $app['C15']; C22 = $app['C22']; C23 = $app['C23']; C27 = $app['C27'] }
        invariantsArms = $arms
        runVerdict = $run.verdict
        failedChecks = @($run.checks | Where-Object { -not $_.ok } | ForEach-Object { "$($_.checkName)=$($_.violatedRows)" })
    }
}

# 문턱과 행 수 하한은 검증설계 §4-1 비용 항목 값이다. 판정은 세 번 잰 값의 최댓값으로 한다.
function Measure-C22Cost {
    $thresholdMs = 1500
    $minRows = 1000000
    $synthetic = 1000000
    $java = Get-Content (Join-Path $repo 'server\app\src\main\java\kr\exchange\harness\InvariantCheck.java') -Raw -Encoding UTF8
    $m = [regex]::Match($java, 'C22_FENCING_MONOTONIC\(Severity\.CRITICAL, Scope\.AUTHORITATIVE_ONLY, """\r?\n(.*?)"""\)',
        [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $m.Success) { throw 'InvariantCheck.java 에서 C22 문장을 찾지 못함' }

    $load = Join-Path $here 'c22_cost_load.sql'
    Set-Content $load -Encoding utf8 -Value @"
-- 합성 행은 커트오버 뒤 배치로 넣는다. 마켓마다 대역 잎(seq = market_id * band_span + 지역 번호)에 들어가고
-- created_at 은 마켓을 돌아가며 1ms 씩 늘어 잎마다 시간 순 묶음이 서로 엇갈린다. 한 잎에 몰아 넣으면
-- 정렬 입력이 이미 (created_at, seq) 순서라 최선의 경우를 잰다.
WITH b AS (
    SELECT (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq') AS span,
           (SELECT max(fencing_token) FROM command_journal) AS epoch,
           (SELECT max(created_at) FROM command_journal) AS max_ts,
           (SELECT array_agg(id ORDER BY id) FROM markets) AS ids
)
INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                             engine_ts, tie_break, payload, fencing_token, checksum, created_at)
SELECT b.ids[1 + (g - 1) % cardinality(b.ids)] * b.span + (g - 1) / cardinality(b.ids) + 1,
       b.ids[1 + (g - 1) % cardinality(b.ids)], 'CANCEL', 1, NULL, g, 0, 0, '{}',
       b.epoch, 0, b.max_ts + g * interval '1 millisecond'
FROM b CROSS JOIN generate_series(1, $synthetic) g;
ANALYZE command_journal;
"@
    $null = Invoke-PsqlFile $load 'c22_cost_load.txt'
    $bandRows = [long](Get-Scalar "SELECT count(*) FROM command_journal WHERE seq >= (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq')" -Required)
    if ($bandRows -ne $synthetic) { throw "합성 행이 대역 잎에 다 들어가지 않음(대역 행 $bandRows, 합성 $synthetic)" }
    $leafRows = [ordered]@{}
    foreach ($line in @((Invoke-Psql 'SELECT tableoid::regclass, count(*) FROM command_journal GROUP BY 1 ORDER BY 1') -split "`r?`n")) {
        $f = $line.Split('|')
        $leafRows[$f[0]] = [long]$f[1]
    }

    $explain = Join-Path $here 'c22_cost_explain.sql'
    Set-Content $explain -Encoding utf8 -Value @"
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
EXPLAIN (ANALYZE, BUFFERS)
$($m.Groups[1].Value.TrimEnd());
ROLLBACK;
"@
    $times = @()
    foreach ($i in 1..3) {
        $plan = Invoke-PsqlFile $explain "c22_cost_explain_$i.txt"
        $t = [regex]::Match(($plan -join "`n"), 'Execution Time: ([\d.]+) ms')
        if (-not $t.Success) { throw "EXPLAIN 출력에 Execution Time 없음(c22_cost_explain_$i.txt)" }
        $times += [double]$t.Groups[1].Value
    }
    $rows = [long](Get-Scalar 'SELECT count(*) FROM command_journal' -Required)
    $max = ($times | Measure-Object -Maximum).Maximum
    return [ordered]@{
        thresholdMs = $thresholdMs
        minRows = $minRows
        journalRows = $rows
        syntheticRows = $synthetic
        syntheticLayout = '마켓별 대역 잎, 마켓 사이 created_at 교차'
        leaves = [long](Get-Scalar "SELECT count(*) FROM pg_inherits WHERE inhparent = 'command_journal'::regclass" -Required)
        leavesWithRows = $leafRows.Count
        leafRows = $leafRows
        isolation = 'REPEATABLE READ READ ONLY'
        executionMs = $times
        maxExecutionMs = $max
        verdict = if ($rows -lt $minRows) { 'NOT RUN' } elseif ($max -le $thresholdMs) { 'PASS' } else { 'FAIL' }
        pg = [ordered]@{
            version = Invoke-Psql 'SHOW server_version'
            shared_buffers = Invoke-Psql 'SHOW shared_buffers'
            work_mem = Invoke-Psql 'SHOW work_mem'
            statement_timeout = Invoke-Psql 'SHOW statement_timeout'
        }
        host = (& docker info --format '{{.ServerVersion}} NCPU={{.NCPU}} MemTotal={{.MemTotal}} OS={{.OperatingSystem}}')
    }
}

$result = [ordered]@{
    started = Now-Iso
    head = (& git -C $repo rev-parse HEAD)
    uncommitted = @(& git -C $repo status --short)
    sources = [ordered]@{}
}
foreach ($rel in @('server\app\src\main\java\kr\exchange\harness\InvariantCheck.java', 'harness\sql\summary.sql', 'harness\sql\invariants.sql')) {
    $result.sources[$rel.Replace('\', '/')] = (Get-FileHash (Join-Path $repo $rel) -Algorithm SHA256).Hash
}
$rc = 2
try {
    Write-Section '1 스택 기동'
    & pwsh -NoProfile -File (Join-Path $scripts 'up.ps1') *> (Join-Path $here 'up.log')
    if ($LASTEXITCODE -ne 0) { throw "up.ps1 실패(rc=$LASTEXITCODE). up.log 참조" }

    Write-Section '2 두 마켓 이상 체결'
    if (-not (Wait-For { (Get-Scalar 'SELECT count(DISTINCT market_id) FROM trades' -Required) -ge 2 } -TimeoutSec 300 -PollSec 2)) {
        throw '두 마켓 체결 미관측'
    }

    Write-Section '3 리더 kill 로 epoch 전이'
    $oldEpoch = [long](Get-HarnessStatus -Port (Get-LiveNodePort)).epoch
    $leader = Get-LeaderContainer
    if (-not $leader) { throw '리더 컨테이너 판별 실패' }
    $standbyPort = if ($leader -eq 'chaos-api-1') { $Global:CHAOS.Api2Port } else { $Global:CHAOS.Api1Port }
    & docker kill $leader | Out-Null
    $promoted = Wait-For {
        $s = Get-HarnessStatus -Port $standbyPort
        ($s.epoch -gt $oldEpoch) -and $s.leaderAlive
    } -TimeoutSec 120 -PollSec 1
    if (-not $promoted) { throw '승격 미관측' }
    & docker start $leader | Out-Null
    $resumed = Wait-For {
        (Get-Scalar "SELECT count(DISTINCT market_id) FROM command_journal WHERE fencing_token > $oldEpoch" -Required) -ge 2
    } -TimeoutSec 240 -PollSec 2
    if (-not $resumed) { throw '승격 뒤 두 마켓 명령 미관측' }
    Start-Sleep -Seconds 20
    $result.failover = [ordered]@{ killed = $leader; oldEpoch = $oldEpoch; newEpoch = [long](Get-HarnessStatus -Port $standbyPort).epoch }

    Write-Section '4 부하 정지'
    if (-not (Stop-BotLoad)) { throw '봇 정지 실패' }
    if (-not (Wait-DrainZero $Global:CHAOS_DRAIN_SQL 120 3)) { throw '드레인 미완' }
    Start-Sleep -Seconds 5

    Write-Section '5 1상 대조(저널 그대로)'
    $result.natural = Invoke-Phase 'natural' $standbyPort

    Write-Section '6 2상 대조(C23 레거시 팔과 C15 위반 주입)'
    $null = Invoke-Psql "UPDATE journal_counters SET next_value = 1 WHERE name = 'command_seq'"
    $null = Invoke-Psql "INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, created_at) VALUES ('TRADE', 'stage3-parity-probe', 'TradeExecuted', '{}', TIMESTAMPTZ '2000-01-01 00:00:00+00')"
    $result.injected = Invoke-Phase 'injected' $standbyPort
    Add-Comparison 'injected' '주입이 구 C23 에 닿음' ($result.injected.legacy['legacy_c23'] -ge 1) $true
    Add-Comparison 'injected' '주입이 구 C15 에 닿음' ($result.injected.legacy['legacy_c15'] -ge 1) $true

    Write-Section '7 3상 C22 문장 비용'
    $code = Invoke-DockerQuiet 'stop chaos-api-1 chaos-api-2'
    if ($code -ne 0) { throw "앱 노드 정지 실패(rc=$code). 팔로워 catch-up 이 합성 행을 재생하지 않게 내린다" }
    $result.cost = Measure-C22Cost

    $positive = [ordered]@{
        journal_rows = $result.natural.legacy['journal_rows'] -gt 0
        journal_markets_ge_2 = $result.natural.legacy['journal_markets'] -ge 2
        journal_epochs_ge_2 = $result.natural.legacy['journal_epochs'] -ge 2
        band_spec_present = $result.natural.legacy['band_spec_rows'] -eq 1
        timeline_id_is_1 = $result.natural.legacy['timeline_id'] -eq 1
    }
    $result.positiveAxes = $positive
    $result.timelineIdScope = 'timeline_id 1 은 승격·시점 복구 이력만 배제한다. 한 프라이머리 안의 서버 시계 조정은 배제하지 못하고(ADR 0022 9-17), 순서를 뒤집은 조정은 created_at_regressions 가 드러낸다.'
    $result.bandArms = if ($result.natural.legacy['band_rows'] -eq 0) { 'N/A(대상 행 0, 커트오버 전)' } else { '대역 행 존재' }
    $result.comparisons = $comparisons
    $gateFail = @($comparisons | Where-Object { $_.gate -and -not $_.equal })
    $positiveFail = @($positive.Keys | Where-Object { -not $positive[$_] })
    $result.gateMismatches = $gateFail.Count
    $result.positiveAxisFailures = $positiveFail
    $result.notRun = $notRun
    $rc = if ($gateFail.Count -eq 0 -and $positiveFail.Count -eq 0 -and $result.cost.verdict -eq 'PASS') { 0 } else { 1 }
} catch {
    $result.error = $_.Exception.Message
    Write-Host "주행 실패: $($result.error)" -ForegroundColor Red
} finally {
    $result.finished = Now-Iso
    $result.exitCode = $rc
    $result | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $here 'result.json') -Encoding utf8
    & pwsh -NoProfile -File (Join-Path $scripts 'down.ps1') *> (Join-Path $here 'down.log')
}
exit $rc
