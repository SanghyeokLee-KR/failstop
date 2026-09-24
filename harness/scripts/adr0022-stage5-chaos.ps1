# ADR 0022 5단계 장애 시험 주행. 4단계 이미지(B4)로 E2, E4, E5, E6, E7 을 차례로 돌린다.
# -LatchBeforeTraffic 이면 6단계 대역 모드 주행이다. 6단계 이미지로 실험마다 기동 직후, 실험 스크립트 전에 래치를 건다
# (ADR 0022 7절 6-b 래치 전 확인 5). 래치는 되돌릴 수 없고 실험마다 down -v 가 그 DB 를 버린다.
# 실험마다 볼륨을 파기하고 새로 세운다(e4·e5·e7 머리 주석의 실행 전제). down -v 전에 판정 파일, DB 쪽 증거,
# 도커 사건, 연결 표본을 harness/evidence/adr0022-stage5-<YYYYMMDD>/<실험>/ 로 떨어뜨린다. 대역 모드는 adr0022-stage6-chaos-<YYYYMMDD>/ 다.
# -SnapshotEvidence 이면 ADR 0022 7단계 스냅샷 경계 재생 주행이다. 실험 스크립트 전과 뒤에 harness/scale/snapshot_evidence.py 로
# 스냅샷·복구 증거를 뜨고 전역 모드도 래치 상태를 남긴다. 증거 기본 폴더는 adr0022-stage7-snapshot-<YYYYMMDD>/<global|band>/ 다.
# 실험 COLD 는 7단계 콜드 재기동 칸(adr0022-stage7-cold-restart.ps1)이다. 두 api 노드를 멈췄다가 올리므로 도달 사건은 stop·start 다.
# -Protocol 2 이면 ADR 0034 S3 관문 주행이다(마켓분할_리더_S3_설계 7-3 X2~X4). 실험마다 스택을 세운 직후, 실험 스크립트 전에
# harness/scale/protocol_latch.py 로 P2 로 전환하고 전환 JSON 을 실험 폴더에 남긴다. 전환이 실패하면 그 실험은 BLOCKED 다.
# 실험 동안 잔고 행 대기를 표본으로 뜨고, 실험 스크립트가 끝나면 스택을 내리기 전에 harness/scale/s3_common_axes.py judge 로
# 공통 판정 축(설계 7-3: summary.sql, 정산 23514·잠금 계획 이탈·접수 40P01·접수 500·교착 0, 예약 양성 축, 드레인 뒤 정산 전
# 예약 0, 리더 노드의 정지·강등 문구 0, 장애 창 밖 ReservationUnsettledStale 0)을 s3_common.json 에 남긴다. 실험 판정이 PASS 여도
# 공통 판정이 거짓이면 분리 판정은 FAIL(공통 판정)이다.
# -Protocol 2 의 분리 판정에는 설계 7-3 X2·X3 의 사전 규칙(rpo_records 0, 사후 권위 run C13A 0)도 건다(FAIL(rpo_records)).
# 사용: pwsh -NoProfile -File harness/scripts/adr0022-stage5-chaos.ps1 -Image exchange-chaos-app:<태그> [-LatchBeforeTraffic] [-SnapshotEvidence] [-Protocol 2]
# 실험마다 down -v 를 하므로 보존 스택(CHAOS_PROJECT 미설정 또는 exchange-chaos)에서는 시작하지 않는다(하네스 검증설계 §2-4 보존
# 스택 가드). 환경 변수가 빠지면 프로젝트가 exchange-chaos 가 되어 보존 볼륨을 지우기 때문이다. 이미지 태그는 절차가 정한다
# (7단계 절차는 -Ref 로 구운 태그를 넘긴다). -Protocol 2 는 CHAOS_PROJECT=exchange-pml 과 -Image exchange-chaos-app:pml 에서만 돈다(S3 설계 7-0).
# 종료코드: 0 전 실험 PASS 이고 연결 고갈 없음, 1 그 밖, 5 BLOCKED(시작 전 조건).
# 비밀 파일(harness/.env.chaos)은 compose 에 경로로만 넘기고 읽지 않는다.
param(
    [string] $Image = '',
    [string] $Experiments = 'E2,E4,E5,E6,E7',
    [string] $EvidenceDir = '',
    [string] $LocalLogDir = '',
    [int] $AppendPool = 14,
    [switch] $LatchBeforeTraffic,
    [switch] $SnapshotEvidence,
    [int] $SnapshotWaitSec = 180,
    [ValidateSet(1, 2)][int] $Protocol = 1
)

$ErrorActionPreference = 'Stop'
# psql·python 의 UTF-8 출력을 코드 페이지로 잘못 풀면 한글 뒤 ASCII 표지까지 먹힌다.
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONIOENCODING = 'utf-8'
. (Join-Path $PSScriptRoot 'lib.ps1')
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$date = Get-Date -Format 'yyyyMMdd'
$runName = $(if ($SnapshotEvidence) { 'adr0022-stage7-snapshot' } elseif ($LatchBeforeTraffic) { 'adr0022-stage6-chaos' } elseif ($Protocol -eq 2) { 'pml-s3-chaos' } else { 'adr0022-stage5' })
$runDir = "$runName-$date"
if ($SnapshotEvidence) { $runDir = Join-Path $runDir $(if ($LatchBeforeTraffic) { 'band' } else { 'global' }) }
if (-not $EvidenceDir) { $EvidenceDir = Join-Path $repo "harness\evidence\$runDir" }
# 컨테이너 로그는 요청 식별자와 계정 흔적을 담아 저장소 밖에 두고 해시만 증거에 남긴다.
if (-not $LocalLogDir) { $LocalLogDir = Join-Path ([System.IO.Path]::GetTempPath()) $runDir }
$runLog = Join-Path $EvidenceDir 'commands.log'

$scripts = [ordered]@{ E2 = 'e2-db-cut.ps1'; E4 = 'e4-leader-kill.ps1'; E5 = 'e5-net-partition.ps1'
    E6 = 'e6-spike-failover.ps1'; E7 = 'e7-gray-pause.ps1'; COLD = 'adr0022-stage7-cold-restart.ps1' }
# 목표 계층 도달을 보이는 도커 사건. E2 는 Toxiproxy 프록시 계층 주입이라 도커 사건이 없다.
$reachActions = @{ E2 = @(); E4 = @('kill', 'die'); E5 = @('disconnect', 'connect'); E6 = @('kill', 'die'); E7 = @('pause', 'unpause')
    COLD = @('stop', 'start') }
$eventActions = @('kill', 'die', 'stop', 'start', 'restart', 'oom', 'pause', 'unpause', 'disconnect', 'connect')
$apiNodes = @('chaos-api-1', 'chaos-api-2')
# 스냅샷 채집기가 로그와 지표를 뜨는 노드와 포트. lib.ps1 의 포트를 그대로 넘긴다.
$snapshotApi = 'chaos-api-1={0},chaos-api-2={1}' -f $Global:CHAOS.Api1Port, $Global:CHAOS.Api2Port

# 같은 마켓 안에서는 접수 락이 append 를 통째로 직렬화하므로 seq 순서의 created_at 역행은 DB 서버 시계 역행이다.
$inversionSql = "SELECT json_build_object('rows', count(*), 'inversions', count(*) FILTER (WHERE prev > created_at), " +
    "'max_regression_ms', max(extract(epoch FROM (prev - created_at)) * 1000) FILTER (WHERE prev > created_at)) " +
    "FROM (SELECT created_at, lag(created_at) OVER (PARTITION BY market_id ORDER BY seq) AS prev FROM command_journal) t"

function Write-RunLog {
    param([string] $Text)
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'), $Text
    Add-Content -Path $runLog -Value $line -Encoding utf8
    Write-Host $line
}

function Save-Text {
    param([string] $Path, [string] $Text)
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false))
}

function Invoke-Logged {
    param([string] $Exe, [string[]] $ArgList, [string] $Log)
    Write-RunLog ('RUN {0} {1}' -f $Exe, ($ArgList -join ' '))
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    Push-Location $repo
    try { & $Exe @ArgList *> $Log; $rc = $LASTEXITCODE }
    finally { Pop-Location; $ErrorActionPreference = $eap }
    Write-RunLog "RC $rc"
    return $rc
}

function Get-RelPath {
    param([string] $Path)
    return [System.IO.Path]::GetRelativePath($repo, $Path)
}

# invariants.sql 을 psql 로 통째로 돌린 출력에서 C22 두 팔의 행 수를 센다. 술어를 이 파일에 복제하지 않는다.
function Get-C22Arms {
    param([string] $Text, $Rc)
    $inC22 = $false; $seen = $false; $failed = $false; $time = 0; $market = 0
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -like '== C22 *') { $inC22 = $true; $seen = $true; continue }
        if (-not $inC22) { continue }
        if ($line -like '== *') { break }
        if ($line -match 'ERROR:') { $failed = $true }
        elseif ($line -like 'time|*') { $time++ }
        elseif ($line -like 'market|*') { $market++ }
    }
    if ($Rc -ne 0 -or -not $seen -or $failed) { return [ordered]@{ ok = $false; timeArm = $null; marketArm = $null } }
    return [ordered]@{ ok = $true; timeArm = $time; marketArm = $market }
}

# 사건 줄은 실험 전에 띄운 docker events 스트림이 파일로 흘린 것이다. 네트워크 사건의 컨테이너 id 는 down 전 이름으로 바꾼다.
function Get-DockerEvents {
    param([string] $RawPath)
    $names = @{}
    foreach ($line in @(& docker ps -a --no-trunc --format '{{.ID}} {{.Names}}' 2>$null)) {
        $parts = "$line".Split(' ')
        if ($parts.Count -eq 2) { $names[$parts[0]] = $parts[1] }
    }
    $events = @()
    foreach ($line in @(Get-Content $RawPath -Encoding utf8)) {
        try { $e = $line | ConvertFrom-Json } catch { continue }
        if ($eventActions -notcontains $e.Action) { continue }
        $target = if ($e.Type -eq 'network') { $names[[string]$e.Actor.Attributes.container] } else { [string]$e.Actor.Attributes.name }
        $events += [ordered]@{ time = [DateTimeOffset]::FromUnixTimeSeconds([long]$e.time).ToString('o')
            type = $e.Type; action = $e.Action; container = $target }
    }
    return , $events
}

function Invoke-Experiment {
    param([string] $Exp)
    $dir = Join-Path $EvidenceDir $Exp
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $row = [ordered]@{ experiment = $Exp; script = "harness/scripts/$($scripts[$Exp])"; image = $Image; imageId = $imageId }
    $sampler = $null
    $eventsProc = $null
    $balanceSampler = $null
    $stop = Join-Path $dir 'stop-conn'
    $stopBalance = Join-Path $dir 'stop-balance'
    try {
        Write-RunLog "START $Exp"
        $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
        # 공통 판정의 프로메테우스 창 시작. 스택을 세우기 전 시각이라 전환의 노드 재기동까지 덮는다.
        $stackFrom = [DateTimeOffset]::UtcNow
        $code = Invoke-ComposeQuiet '--profile obs up -d --wait'
        if ($code -ne 0) { $row['분리 판정'] = 'BLOCKED'; $row['사유'] = "compose up 종료코드 $code"; return $row }
        $running = [ordered]@{}
        foreach ($c in 'chaos-api-1', 'chaos-api-2', 'chaos-bots') { $running[$c] = (& docker inspect -f '{{.Image}}' $c 2>$null | Out-String).Trim() }
        $row['containerImages'] = $running
        if (@($running.Values | Where-Object { $_ -ne $imageId }).Count -gt 0) {
            $row['분리 판정'] = 'BLOCKED'; $row['사유'] = '고정한 이미지로 뜨지 않았다'; return $row
        }
        if ($Protocol -eq 2) {
            # 실험 스크립트 전에 P2 로 전환한다(S3 설계 7-2). 도구가 봇을 멈추고 드레인한 뒤 두 노드를 다시 띄우고 봇을 되살린다.
            $protocolRc = Invoke-Logged 'python' @('harness/scale/protocol_latch.py', 'transition', '--target', '2', '--out',
                (Get-RelPath (Join-Path $dir 'protocol.json'))) (Join-Path $dir 'protocol.log')
            $row['protocolTransition'] = [ordered]@{ rc = $protocolRc; file = 'protocol.json' }
            if ($protocolRc -ne 0) { $row['분리 판정'] = 'BLOCKED'; $row['사유'] = "P2 전환 실패(rc=$protocolRc). protocol.json 을 본다"; return $row }
        }
        if ($LatchBeforeTraffic) {
            # 실험 스크립트가 봇 부하를 정하기 전에 건다. 봇은 compose 기동과 함께 돌므로 호출 전 저널 행 수가 latch.json 에 남는다.
            $latchRc = Invoke-Logged 'python' @('harness/scale/band_cutover.py', 'latch', '--out', (Get-RelPath (Join-Path $dir 'latch.json'))) (Join-Path $dir 'latch.log')
            if ($latchRc -ne 0) { $row['분리 판정'] = 'BLOCKED'; $row['사유'] = "래치 거부 또는 실패(rc=$latchRc). latch.json 을 본다"; return $row }
        }
        # 연결 예산 사전 점검. max_connections 는 떠 있는 chaos-pg 에서 읽는다. 요청 풀 32 는 chaos compose 값이다.
        $budgetRc = Invoke-Logged 'python' @('harness/scale/pg_samplers.py', 'budget', '--live', '--api-nodes', '2',
            '--request-pool', '32', '--lease-pool', '2', '--append-pool', "$AppendPool", '--bots-request-pool', '10',
            '--exporter', '2', '--tooling', '4', '--out', (Get-RelPath (Join-Path $dir 'budget.json'))) (Join-Path $dir 'budget.log')
        if ($budgetRc -ne 0) { $row['분리 판정'] = 'BLOCKED'; $row['사유'] = "연결 예산 부족 또는 설정 읽기 실패(rc=$budgetRc)"; return $row }
        if ($SnapshotEvidence) {
            # 주입 전 증거. 활성 마켓마다 스냅샷이 생기기를 기다리되 못 채워도 실험은 돈다. 판정은 stage7_judge.py 가 한다.
            $preRc = Invoke-Logged 'python' @('harness/scale/snapshot_evidence.py', 'pre', '--dir', (Get-RelPath $dir),
                '--api', $snapshotApi, '--wait-sec', "$SnapshotWaitSec") (Join-Path $dir 'snapshot_pre.log')
            $row['snapshotEvidence'] = [ordered]@{ pre = $preRc; post = $null }
        }
        $row['clockBefore'] = Test-ClockSkew

        Remove-Item $stop -Force -ErrorAction SilentlyContinue
        $samplesPath = Join-Path $dir 'conn_samples.jsonl'
        $sampler = Start-Process -FilePath 'python' -WorkingDirectory $repo -NoNewWindow -PassThru `
            -ArgumentList @('harness/scale/pg_samplers.py', 'connections', '--out', (Get-RelPath $samplesPath),
                '--stop-file', (Get-RelPath $stop), '--interval', '5') `
            -RedirectStandardOutput (Join-Path $dir 'conn_sampler.out') -RedirectStandardError (Join-Path $dir 'conn_sampler.err')
        if ($Protocol -eq 2) {
            Remove-Item $stopBalance -Force -ErrorAction SilentlyContinue
            $balanceSampler = Start-Process -FilePath 'python' -WorkingDirectory $repo -NoNewWindow -PassThru `
                -ArgumentList @('harness/scale/s3_common_axes.py', 'sample', '--out', (Get-RelPath (Join-Path $dir 'balance_samples.jsonl')),
                    '--stop-file', (Get-RelPath $stopBalance), '--interval', '2') `
                -RedirectStandardOutput (Join-Path $dir 'balance_sampler.out') -RedirectStandardError (Join-Path $dir 'balance_sampler.err')
        }

        # 사건 스트림을 실험 스크립트보다 먼저 띄운다. 끝난 뒤 --since 로 되물으면 주입 사건이 데몬 버퍼에 남아 있지 않았다(5단계 E5·E7).
        $eventsPath = Join-Path $dir 'docker_events.jsonl'
        $eventsProc = Start-Process -FilePath 'docker' -NoNewWindow -PassThru `
            -ArgumentList @('events', '--since', "$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())", '--format', '"{{json .}}"',
                '--filter', 'type=container', '--filter', 'type=network') `
            -RedirectStandardOutput $eventsPath -RedirectStandardError (Join-Path $dir 'docker_events.err')

        $t0 = [DateTimeOffset]::UtcNow
        $console = Join-Path $dir 'console.log'
        Write-RunLog "EXPERIMENT $Exp pwsh -NoProfile -File $($row.script)"
        $eap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { & pwsh -NoProfile -File (Join-Path $PSScriptRoot $scripts[$Exp]) *> $console; $expRc = $LASTEXITCODE }
        finally { $ErrorActionPreference = $eap }
        $t1 = [DateTimeOffset]::UtcNow
        Write-RunLog "EXPERIMENT $Exp rc=$expRc"
        $row['exitCode'] = $expRc
        $row['window'] = [ordered]@{ from = $t0.ToString('o'); to = $t1.ToString('o') }
        New-Item -ItemType File -Force -Path $stop | Out-Null
        if (-not $sampler.WaitForExit(30000)) { Stop-Process -Id $sampler.Id -Force }
        if ($balanceSampler) {
            New-Item -ItemType File -Force -Path $stopBalance | Out-Null
            if (-not $balanceSampler.WaitForExit(30000)) { Stop-Process -Id $balanceSampler.Id -Force }
        }
        # 마지막 복귀 사건이 스트림에 닿을 틈을 두고 멈춘다.
        Start-Sleep -Seconds 2
        Stop-Process -Id $eventsProc.Id -Force
        $eventsProc.WaitForExit(10000) | Out-Null

        # 판정 파일은 실험 스크립트가 harness/evidence/chaos-runs 에 쓴다. 판정 전에 예외로 끝나면 없다.
        $runId = $null
        $hit = Select-String -Path $console -Pattern 'runId=((?:E\d|COLD)-\d{8}-\d{6})' | Select-Object -First 1
        if ($hit) { $runId = $hit.Matches[0].Groups[1].Value }
        $row['runId'] = $runId
        $verdict = $null
        if ($runId) {
            $src = Join-Path $Global:CHAOS.EvidenceDir "$runId.json"
            if (Test-Path $src) {
                Copy-Item $src (Join-Path $dir 'verdict.json') -Force
                $verdict = Get-Content $src -Raw -Encoding utf8 | ConvertFrom-Json
            }
        }

        # 여기부터 down -v 전 증거. 카오스 볼륨과 컨테이너가 사라지면 다시 뜰 수 없다.
        if ($Protocol -eq 2) {
            # 공통 판정(S3 설계 7-3). 봇을 멈추고 드레인한 뒤 판정하므로 뒤의 증거 수집은 무부하 상태에서 돈다.
            $commonRc = Invoke-Logged 'python' @('harness/scale/s3_common_axes.py', 'judge', '--window-from', $stackFrom.ToString('o'),
                '--fault-from', $t0.ToString('o'), '--fault-to', $t1.ToString('o'),
                '--samples', (Get-RelPath (Join-Path $dir 'balance_samples.jsonl')),
                '--out', (Get-RelPath (Join-Path $dir 's3_common.json'))) (Join-Path $dir 's3_common.log')
            $common = $null
            $commonPath = Join-Path $dir 's3_common.json'
            if (Test-Path $commonPath) { $common = Get-Content $commonPath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable }
            $row['s3Common'] = [ordered]@{ rc = $commonRc; ok = $(if ($common) { $common['ok'] } else { $false })
                failed = $(if ($common -and $common['axes']) { @($common['axes'].Keys | Where-Object { -not $common['axes'][$_] }) } else { @('판정 파일 없음') })
                reservations = $(if ($common -and $common['state']) { $common['state']['reservations'] } else { $null })
                reservedSettledByLaterEpoch = $(if ($common -and $common['state']) { $common['state']['reserved_settled_by_later_epoch'] } else { $null })
                file = 's3_common.json' }
        }
        if ($SnapshotEvidence) {
            # 판정은 재생 적용 줄의 구간 안 행 수로 세므로 실험 뒤에 쌓인 새 명령이 이 증거를 흐리지 않는다.
            $row['snapshotEvidence']['post'] = Invoke-Logged 'python' @('harness/scale/snapshot_evidence.py', 'post',
                '--dir', (Get-RelPath $dir), '--api', $snapshotApi) (Join-Path $dir 'snapshot_post.log')
        }
        Save-Text (Join-Path $dir 'failover_runs.json') (Invoke-Psql "SELECT coalesce(json_agg(f ORDER BY f.id), '[]'::json) FROM harness.failover_runs f")
        if ($runId) {
            Save-Text (Join-Path $dir 'invariant_checks.json') (Invoke-Psql ("SELECT coalesce(json_agg(i ORDER BY i.checked_at, i.check_name), '[]'::json) " +
                "FROM invariant_checks i WHERE i.details->>'run_id' = '$runId'"))
        }
        Save-Text (Join-Path $dir 'leader_lease.json') (Invoke-Psql "SELECT coalesce(json_agg(l), '[]'::json) FROM leader_lease l")
        if ($Protocol -eq 2) {
            # 주행 뒤 모드 상태(예약 행 수, 정산 전 예약, 퇴역 행). 위 공통 판정이 봇을 멈추고 드레인한 뒤의 값이다.
            Invoke-Logged 'python' @('harness/scale/protocol_latch.py', 'state', '--out',
                (Get-RelPath (Join-Path $dir 'protocol_state.json'))) (Join-Path $dir 'protocol_state.log') | Out-Null
        }
        $inversionsRaw = Invoke-Psql $inversionSql
        Save-Text (Join-Path $dir 'created_at_inversions.json') $inversionsRaw
        $inversions = $null
        if ($inversionsRaw) { try { $inversions = $inversionsRaw | ConvertFrom-Json } catch { $inversions = $null } }

        $eap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $sqlText = [System.IO.File]::ReadAllText((Join-Path $repo 'harness\sql\invariants.sql'))
            $psqlOut = ($sqlText | & docker exec -i $Global:CHAOS.Pg psql -U $Global:CHAOS.PgUser -d $Global:CHAOS.PgDb -X -q -t -A -F '|' 2>&1 | Out-String)
            $psqlRc = $LASTEXITCODE
        } finally { $ErrorActionPreference = $eap }
        Save-Text (Join-Path $dir 'invariants_psql.txt') $psqlOut
        $c22 = Get-C22Arms $psqlOut $psqlRc

        $events = Get-DockerEvents $eventsPath
        $states = [ordered]@{}
        foreach ($c in 'chaos-api-1', 'chaos-api-2', 'chaos-bots', 'chaos-pg') {
            $raw = (& docker inspect -f '{{json .State}}' $c 2>$null | Out-String).Trim()
            $states[$c] = $(if ($raw) { $raw | ConvertFrom-Json } else { $null })
        }
        Save-Text (Join-Path $dir 'containers.json') ($states | ConvertTo-Json -Depth 6)
        try {
            $proxies = Invoke-RestMethod -Uri "$($Global:CHAOS.ToxiUrl)/proxies" -UserAgent $Global:CHAOS_UA -TimeoutSec 10
            Save-Text (Join-Path $dir 'toxiproxy.json') ($proxies | ConvertTo-Json -Depth 6)
        } catch { Write-RunLog "toxiproxy 조회 실패: $($_.Exception.Message)" }

        New-Item -ItemType Directory -Force -Path $LocalLogDir | Out-Null
        $logs = @()
        foreach ($c in 'chaos-api-1', 'chaos-api-2', 'chaos-bots', 'chaos-pg', 'chaos-toxiproxy') {
            $path = Join-Path $LocalLogDir "$Exp-$c.log"
            & cmd /c "docker logs $c > `"$path`" 2>&1"
            if (Test-Path $path) {
                $logs += [ordered]@{ container = $c; path = $path; bytes = (Get-Item $path).Length
                    sha256 = (Get-FileHash $path -Algorithm SHA256).Hash.ToLower() }
            }
        }
        Save-Text (Join-Path $dir 'container_logs.json') (ConvertTo-Json @($logs) -Depth 4)

        # pg_samplers summarize 가 chaos-pg 로그의 연결 거절 문구를 함께 본다. down 전에 불러야 한다.
        $connPath = Join-Path $dir 'connections.json'
        Invoke-Logged 'python' @('harness/scale/pg_samplers.py', 'summarize', '--samples', (Get-RelPath $samplesPath),
            '--out', (Get-RelPath $connPath)) (Join-Path $dir 'connections.log') | Out-Null
        $connState = 'NOT RUN'
        if (Test-Path $connPath) { $connState = (Get-Content $connPath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable).'판정'.'상태' }

        $cutover = $null
        $statePath = Join-Path $dir 'band_state.json'
        # 스냅샷 채집 주행은 전역 모드에서도 래치 상태를 남긴다. 7단계 판정기의 모드 식별 증거다.
        if ($LatchBeforeTraffic -or $SnapshotEvidence) {
            Invoke-Logged 'python' @('harness/scale/band_cutover.py', 'state', '--out', (Get-RelPath $statePath)) (Join-Path $dir 'band_state.log') | Out-Null
        }
        if ($LatchBeforeTraffic) {
            $latch = Get-Content (Join-Path $dir 'latch.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
            $latchRows = @(); $marketRows = @(); $c28 = $null
            if (Test-Path $statePath) {
                $state = Get-Content $statePath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
                if ($state['래치']['래치 행']) { $latchRows = @($state['래치']['래치 행']) }
                if ($state['대역 행']['마켓']) { $marketRows = @($state['대역 행']['마켓']) }
                if ($state['C28']['ok']) { $c28 = [long]$state['C28']['위반 행'] }
            }
            $bandTotal = 0; $legacyTotal = 0; $bandMarkets = 0
            foreach ($m in $marketRows) {
                $bandTotal += [long]$m['band_rows']; $legacyTotal += [long]$m['legacy_rows']
                if ([long]$m['band_rows'] -gt 0) { $bandMarkets++ }
            }
            $authC28 = $null
            if ($verdict) {
                $authC28 = @($verdict.invariants | Where-Object { $_.window -eq 'post' -and "$($_.check)" -like 'C28*' } |
                    ForEach-Object { [ordered]@{ check = $_.check; ok = $_.ok; violatedRows = $_.violatedRows } })
            }
            $cutover = [ordered]@{
                '래치' = [ordered]@{ ok = $latch['ok']; '호출 전 저널 행' = $latch['사전']['journal_rows']; '래치 행' = $latchRows }
                '대역 모드 성립' = ($latchRows.Count -gt 0 -and $bandTotal -gt 0)
                '대역 행 합' = $bandTotal; '대역 행 > 0 마켓' = $bandMarkets; '레거시 행 합' = $legacyTotal
                'C28(하네스 SQL)' = $c28
                '사후 권위 run C28' = [ordered]@{ '포함' = $(if ($null -ne $authC28) { $authC28.Count -gt 0 } else { $null }); '행' = $authC28 }
                '주' = $(if ($c28 -gt 0 -and $inversions -and $inversions.inversions -gt 0) { 'C28 위반과 마켓 안 created_at 역행이 함께 있다. 시계 역행 뒤 거짓 양성 후보(ADR 0022 9-17)' } else { $null })
            }
            $row['cutover'] = $cutover
        }
        $row['clockAfter'] = Test-ClockSkew

        $expVerdict = $null
        if ($verdict) { $expVerdict = [string]$verdict.verdict }
        $row['실험 판정'] = $expVerdict
        $row['연결 판정'] = $connState
        # 연결 고갈은 장애 판정과 섞지 않고 떼어 적는다. 실험 판정 값은 그대로 둔다.
        $row['분리 판정'] = $(if ($connState -eq '증거 불완전(연결 고갈)') { '증거 불완전(연결 고갈)' }
            elseif (-not $expVerdict) { 'NOT RUN' }
            elseif ($expVerdict -eq 'SKIP') { 'NOT RUN(시계 스큐 SKIP)' }
            else { $expVerdict })
        if ($Protocol -eq 2 -and $row['분리 판정'] -eq 'PASS' -and -not $row['s3Common'].ok) { $row['분리 판정'] = 'FAIL(공통 판정)' }
        # 설계 7-3 X2·X3 의 사전 규칙(rpo_records 0, 사후 권위 run C13A 0)을 분리 판정에 건다. 실험 스크립트의 판정은 C13A 를 게이팅에서
        # 빼고(lib.ps1 CHAOS_GATING_EXEMPT_CHECKS) E7 은 부하를 멈추지 않은 채 사후 run 을 뜬다. 이 축이 없으면 rpo_records 가 1 인
        # 주행이 PASS 로 남는다(S3 구현 검토, 관문 X3 반복 r1·r2). 물질화 전 NEW 를 센 것인지는 판정을 바꾸지 않고 증거에 적는다.
        if ($Protocol -eq 2 -and $row['분리 판정'] -eq 'PASS') {
            $rpoRecords = $(if ($verdict -and $verdict.failover) { $verdict.failover.rpoRecords } else { $null })
            $postC13a = @($verdict.invariants | Where-Object { $_.window -eq 'post' -and "$($_.check)" -eq 'C13A_ORDER_MATERIALIZED' -and -not $_.ok }).Count
            $row['rpoAxis'] = [ordered]@{ rpoRecords = $rpoRecords; postC13A = $postC13a }
            if ($null -eq $rpoRecords) { $row['분리 판정'] = 'NOT RUN(rpo_records 없음)' }
            elseif ([long]$rpoRecords -ne 0 -or $postC13a -gt 0) { $row['분리 판정'] = 'FAIL(rpo_records)' }
        }
        # 대역 모드에서 래치 행이나 대역 행이 없으면 대역 채번을 시험하지 않은 주행이다. C28 은 사후 권위 run 에 없을 수 있어 하네스 SQL 값으로 막는다.
        if ($cutover -and $row['분리 판정'] -eq 'PASS') {
            $row['분리 판정'] = $(if (-not $cutover['대역 모드 성립']) { 'NOT RUN(대역 모드 미성립)' }
                elseif ($null -eq $cutover['C28(하네스 SQL)']) { 'NOT RUN(C28 미확인)' }
                elseif ($cutover['C28(하네스 SQL)'] -gt 0) { 'FAIL(C28 섞임)' }
                else { 'PASS' })
        }

        $expected = $reachActions[$Exp]
        $apiEvents = @($events | Where-Object { $apiNodes -contains $_.container })
        $missing = @($expected | Where-Object { $a = $_; @($apiEvents | Where-Object { $_.action -eq $a }).Count -eq 0 })
        $fo = $null; $metrics = $null; $postNotOk = @()
        if ($verdict) {
            $fo = $verdict.failover
            $metrics = $verdict.metrics
            $postNotOk = @($verdict.invariants | Where-Object { $_.window -eq 'post' -and -not $_.ok } | ForEach-Object { '{0}={1}' -f $_.check, $_.violatedRows })
        }
        $c22Note = $null
        if ($c22.ok -and $c22.timeArm -gt 0 -and $inversions -and $inversions.inversions -gt 0) {
            $c22Note = 'C22 시간 축 팔 위반과 마켓 안 created_at 역행이 함께 있다. 시계 역행 뒤 거짓 양성 후보(ADR 0022 9-17). 판정은 바꾸지 않는다'
        }
        $selfRecovery = switch ($Exp) {
            'E2' { [ordered]@{ promotionStable = $metrics.promotionStable; standdownDelta = $metrics.standdownDelta } }
            'E4' { [ordered]@{ firstTradeTs = $fo.firstTradeTs; '주' = 'docker kill 은 재기동 정책을 건너뛰어 구 리더가 돌아오지 않는다. 자력 복구는 스탠바이 체결 재개로 본다' } }
            'E5' { [ordered]@{ firstTradeTs = $fo.firstTradeTs; rejoin = $fo.details.rejoin } }
            'E6' { [ordered]@{ firstTradeTs = $fo.firstTradeTs; spikeClean = $metrics.spikeClean } }
            'E7' { [ordered]@{ firstTradeTs = $fo.firstTradeTs; zombieShutdown = $metrics.zombieShutdown } }
            'COLD' { [ordered]@{ acceptResumedAt = $metrics.acceptResumedAt; firstTradeTs = $fo.firstTradeTs; nodes = $metrics.nodes
                stopOrder = $metrics.stopOrder; startOrder = $metrics.startOrder; epochAfterRejoin = $metrics.epochAfterRejoin
                '주' = '재기동 소요는 기록만 한다(검증설계 §2-4 7단계 규약)' } }
        }
        $row['criteria'] = [ordered]@{
            '1 목표 계층 도달' = [ordered]@{ '기대 사건' = $expected; '빠진 사건' = $missing; '사건' = $apiEvents
                '확인' = $(if ($expected.Count -eq 0) { '간접' } else { $missing.Count -eq 0 })
                '주' = $(if ($Exp -eq 'E2') { 'Toxiproxy 프록시 계층 주입이다. 커널 주입이 아니고 도커 사건이 없어 리스 상실 뒤 승격(epoch 증가)으로 간접 확인한다' } else { $null }) }
            '2 계약된 반응' = [ordered]@{ oldEpoch = $fo.oldEpoch; newEpoch = $fo.newEpoch; oldLeader = $fo.oldLeader
                newLeader = $fo.newLeader; metrics = $metrics }
            '3 복구 시간' = [ordered]@{ rtoSeconds = $fo.rtoSeconds; downtimeSeconds = $fo.downtimeSeconds
                '목표(초)' = $(if ($Exp -eq 'COLD') { $null } else { 30 }) }
            '4 무결성' = [ordered]@{ 'post 창 위반 체크' = $postNotOk; rpoRecords = $fo.rpoRecords; c22 = $c22
                createdAtInversions = $inversions; '주' = $c22Note }
            '5 자력 복구' = $selfRecovery
        }
        # 콜드 재기동 칸의 CRITICAL 위반은 게이팅 면제를 뺀 값이라 post 창 위반 체크와 따로 싣는다. 7단계 판정기가 읽는다.
        if ($Exp -eq 'COLD') { $row['criteria']['4 무결성']['사후 권위 run CRITICAL'] = $metrics.postCritical }
        return $row
    }
    catch {
        $row['분리 판정'] = 'BLOCKED'
        $row['사유'] = $_.Exception.Message
        return $row
    }
    finally {
        if ($sampler -and -not $sampler.HasExited) {
            New-Item -ItemType File -Force -Path $stop | Out-Null
            if (-not $sampler.WaitForExit(30000)) { Stop-Process -Id $sampler.Id -Force }
        }
        if ($balanceSampler -and -not $balanceSampler.HasExited) {
            New-Item -ItemType File -Force -Path $stopBalance | Out-Null
            if (-not $balanceSampler.WaitForExit(30000)) { Stop-Process -Id $balanceSampler.Id -Force }
        }
        if ($eventsProc -and -not $eventsProc.HasExited) { Stop-Process -Id $eventsProc.Id -Force }
        $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
        Write-RunLog "END $Exp down rc=$code"
    }
}

if ($PSVersionTable.PSVersion.Major -lt 7) { Write-Host 'pwsh 7 로 실행한다(CLAUDE.md 30절).'; exit 5 }
if (-not $Image) { Write-Host '-Image 가 필요하다. 5단계는 4단계 B4 태그, 대역 모드는 adr0022-stage6-b4b6.ps1 가 구운 6단계 태그, S3 관문(-Protocol 2)은 exchange-chaos-app:pml 이다.'; exit 5 }
# 보존 스택 가드(하네스 검증설계 §2-4). 실험마다 down -v 를 하므로 보존 스택에서는 아무것도 지우거나 세우지 않는다. lib.ps1 은
# CHAOS_PROJECT 가 없으면 exchange-chaos 를 쓰므로 미설정도 여기서 걸린다. 이미지 태그는 S3 경로에서만 묶는다(S3 설계 7-0).
if ($Global:CHAOS.Project -eq 'exchange-chaos') { Write-Host 'CHAOS_PROJECT 가 보존 스택 exchange-chaos 다(미설정 포함). 보존 볼륨을 지울 수 있어 시작하지 않는다. 폐기용 이름(예: exchange-pml)을 준다.'; exit 5 }
if ($Protocol -eq 2 -and ($Global:CHAOS.Project -ne 'exchange-pml' -or $Image -ne 'exchange-chaos-app:pml')) {
    Write-Host "S3 경로(-Protocol 2)는 CHAOS_PROJECT=exchange-pml, -Image exchange-chaos-app:pml 에서만 돈다(지금 $($Global:CHAOS.Project), $Image)."; exit 5
}
$wanted = @($Experiments.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$unknown = @($wanted | Where-Object { -not $scripts.Contains($_) })
if ($unknown.Count -gt 0) { Write-Host ('이 절차에 없는 실험: {0}' -f ($unknown -join ', ')); exit 5 }
if (Test-Path (Join-Path $EvidenceDir 'summary.json')) { Write-Host '이미 summary.json 이 있다. 증거를 덮지 않는다.'; exit 5 }
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
Write-RunLog "RUN adr0022-stage5-chaos Image=$Image Experiments=$($wanted -join ',') LatchBeforeTraffic=$LatchBeforeTraffic SnapshotEvidence=$SnapshotEvidence Protocol=$Protocol Project=$($Global:CHAOS.Project)"
if (-not (Test-Path $Global:CHAOS.EnvFile)) { Write-RunLog 'BLOCKED harness/.env.chaos 가 없다'; exit 5 }
& docker info *> $null
if ($LASTEXITCODE -ne 0) { Write-RunLog 'BLOCKED 도커 데몬 미응답'; exit 5 }
$imageId = (& docker image inspect $Image --format '{{.Id}}' 2>$null | Out-String).Trim()
if (-not $imageId) { Write-RunLog "BLOCKED 이미지가 없다: $Image"; exit 5 }
# 실험마다 down -v 를 한다. 남의 카오스 스택을 파기하지 않도록 시작 전에 비어 있어야 한다.
$existing = @(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}')
if ($existing.Count -gt 0) { Write-RunLog ('BLOCKED chaos- 컨테이너가 이미 있다({0})' -f ($existing -join ', ')); exit 5 }
$env:CHAOS_APP_IMAGE = $Image

$labels = (& docker image inspect $Image --format '{{json .Config.Labels}}' 2>$null | Out-String).Trim()
$summary = [ordered]@{
    image = $Image; imageId = $imageId
    imageRevision = $(if ($labels -and $labels -ne 'null') { ($labels | ConvertFrom-Json).'org.opencontainers.image.revision' } else { $null })
    harnessCommit = (& git -C $repo rev-parse HEAD | Out-String).Trim()
    latchBeforeTraffic = [bool]$LatchBeforeTraffic
    protocol = $Protocol
    project = $Global:CHAOS.Project
    springProfiles = $env:CHAOS_SPRING_PROFILES
    snapshotEvidence = [bool]$SnapshotEvidence
    localLogDir = $LocalLogDir
    experiments = @()
}
foreach ($exp in $wanted) {
    $summary.experiments += (Invoke-Experiment $exp)
    Save-Text (Join-Path $EvidenceDir 'summary.json') ($summary | ConvertTo-Json -Depth 12)
}
$clean = @($summary.experiments | Where-Object { $_['분리 판정'] -ne 'PASS' -or $_['연결 판정'] -ne '고갈 없음' }).Count -eq 0
Write-RunLog ('DONE {0}' -f (($summary.experiments | ForEach-Object { '{0}={1}' -f $_.experiment, $_['분리 판정'] }) -join ' '))
if ($clean) { exit 0 }
exit 1
