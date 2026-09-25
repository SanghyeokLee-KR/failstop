# ADR 0034 S7 관문 실행기(docs/reference/architecture/마켓분할_리더_S7_설계.md 5-2·5-3, 하네스 검증설계 §3 P3 실험). 실험 하나(Q1~Q16)를
# 새 폐기용 스택에서 돌리고 판정 파일(gate.json)을 남긴다. 판정 규칙과 첫 관문 주행 기록은
# harness/evidence/pml-S7-20260926-0300/README.md 에 있다.
#
# 공통 흐름. 시작 전에 가드(프로젝트 exchange-pml, 이미지 exchange-chaos-app:pml, harness/.env.chaos, 도커 데몬)와 chaos-* 컨테이너가
# 없음을 본다. 스택을 새로 세우고 Q0 준비(전환 도구 --target 3 --latch-band, lib.ps1 Invoke-P3Preflight)를 한 뒤 실험을 하고, 봇을 멈춰
# 비운 뒤 사후 권위 run·summary.sql·구간 경계 증분을 떠서 판정 파일을 남기고 -p exchange-pml 이 붙은 down -v 로 내린다. Q0 이 거짓이면
# 그 실험은 BLOCKED 다. 한 실험의 FAIL 은 다음 실험을 막지 않는다(실험마다 이 스크립트를 따로 부른다).
#   Q2·Q3a·Q3b·Q5·Q6·Q7   adr0022-stage5-chaos.ps1 -Protocol 3 -P3 -Resource matching-p1 로 E4·E7·E5·E2·E6 을 돌린다(스택·Q0·공통 판정은
#                          그 실행기가 한다). Q2 는 웹을 빌드해 띄우고 W6 웹 관찰을 붙인다. Q3b 는 두 파티션을 api-1 로 모은 뒤 멈춘다.
#   Q1 s7_double_spend.py, Q8 s7_fee_coupling.py(Q8c 는 대조 칸), Q9 s7_axes.py relocate, Q10 s7_market_fatal.py, Q11 s7_axes.py
#   cold-assign, Q16 s5_route_probe.py q16 + s7_axes.py handoff, Q13 k6 관찰자·웹 관찰 + s6_relay_axes.py judge --experiment W7,
#   Q15 힌트 없는 12마켓 k6 + s7_axes.py lb-hintless(기록), Q4 되돌리기와 재진입, Q12 roundtrip_probe.py 네 칸(P2a, P3a, P2b, P3b,
#   기록), Q14 s7_c22_cost.py(폐기용 PostgreSQL, 스택 없음).
#
# 사용: pwsh -NoProfile -File harness/scripts/s7-p3-gate.ps1 -Experiment Q2 -EvidenceDir <폴더>
# 종료코드: 0 PASS(Q12·Q15·Q8c 는 기록 완료), 1 FAIL, 3 NOT RUN, 5 BLOCKED(시작 전 조건이나 Q0).
# 비밀 파일(harness/.env.chaos, harness/load/secrets/*.json)은 경로로만 넘기고 읽지 않는다.
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Q1', 'Q2', 'Q3a', 'Q3b', 'Q4', 'Q5', 'Q6', 'Q7', 'Q8', 'Q8c', 'Q9', 'Q10', 'Q11', 'Q12', 'Q13', 'Q14', 'Q15', 'Q16')]
    [string] $Experiment,
    [Parameter(Mandatory = $true)][string] $EvidenceDir,
    [string] $Image = 'exchange-chaos-app:pml',
    [int] $SurvivorLoadOps = 20,
    [int] $Q1Rounds = 1000,
    [int] $WebPort = 3000,
    [switch] $NoWeb,
    [int] $Q4LoadSec = 300,
    [int] $Q13LoadSec = 600,
    [int] $Q12WarmupSec = 240,
    [int] $Q12DurationSec = 130,
    [int] $Q14Rows = 1000000,
    [int] $Q15LoadSec = 180,
    [int] $Q15Ops = 40
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONIOENCODING = 'utf-8'
. (Join-Path $PSScriptRoot 'lib.ps1')
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runner = Join-Path $PSScriptRoot 'adr0022-stage5-chaos.ps1'
$localLogDir = Join-Path ([System.IO.Path]::GetTempPath()) "pml-s7-gate-$Experiment"
# 실행기 실험(설계 5-2). 자원은 모두 p1(api-2 소유)이다. Q3b 만 두 파티션을 api-1 로 모은 뒤 api-1 을 멈춘다.
$runnerPlan = @{
    Q2 = @{ exp = 'E4'; extra = @('-PartitionAxes', '-WsGateAxes') }
    Q3a = @{ exp = 'E7'; extra = @('-PartitionAxes') }
    Q3b = @{ exp = 'E7'; extra = @('-PartitionAxes', '-ConsolidateOn', 'chaos-api-1') }
    Q5 = @{ exp = 'E5'; extra = @('-PartitionAxes') }
    Q6 = @{ exp = 'E2'; extra = @('-PartitionAxes') }
    Q7 = @{ exp = 'E6'; extra = @('-PartitionAxes') }
}
# Q2 의 p1 RTO 상한(설계 5-2 Q2, 결정 11): P2 기준 최대 10.507초 + 비선호 획득 유예 1×TTL(스택 6초).
$Q2RtoLimitSec = 10.507 + 6
$web = $null

function Write-GateLog {
    param([string] $Text)
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'), $Text
    Add-Content -Path (Join-Path $EvidenceDir 'gate.log') -Value $line -Encoding utf8
    Write-Host $line
}

function Save-Json {
    param([string] $Path, $Object)
    [System.IO.File]::WriteAllText($Path, ($Object | ConvertTo-Json -Depth 12), [System.Text.UTF8Encoding]::new($false))
}

function Read-Json {
    param([string] $Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    try { return (Get-Content -LiteralPath $Path -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable) } catch { return $null }
}

function Get-Rel {
    param([string] $Path)
    return [System.IO.Path]::GetRelativePath($repo, $Path)
}

# 파이썬 도구 하나를 저장소 루트에서 부르고 출력을 로그 파일에 남긴다. 반환은 종료코드다.
function Invoke-GatePy {
    param([string[]] $ArgList, [string] $Log, [hashtable] $Env = @{})
    Write-GateLog ('RUN python {0}' -f ($ArgList -join ' '))
    $saved = @{}
    foreach ($k in $Env.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $Env[$k]) }
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    Push-Location $repo
    try { & python @ArgList *> $Log; $rc = $LASTEXITCODE }
    finally {
        Pop-Location; $ErrorActionPreference = $eap
        foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) }
    }
    Write-GateLog "RC $rc"
    return $rc
}

# 시작 전 가드(S7 설계 5-0). 하나라도 있으면 아무것도 세우거나 지우지 않는다.
function Get-GateProblems {
    param([bool] $NeedsStack)
    $p = @()
    if ($PSVersionTable.PSVersion.Major -lt 7) { $p += 'pwsh 7 로 실행한다' }
    if ($Global:CHAOS.Project -ne 'exchange-pml') { $p += "CHAOS_PROJECT 가 exchange-pml 이 아니다($($Global:CHAOS.Project))" }
    if ($env:CHAOS_APP_IMAGE -ne 'exchange-chaos-app:pml' -or $Image -ne 'exchange-chaos-app:pml') { $p += 'CHAOS_APP_IMAGE 와 -Image 는 exchange-chaos-app:pml 이다' }
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) { $p += '도커 데몬 미응답' }
    if ($NeedsStack) {
        if (-not (Test-Path $Global:CHAOS.EnvFile)) { $p += 'harness/.env.chaos 가 없다' }
        if (-not (& docker image inspect $Image --format '{{.Id}}' 2>$null)) { $p += "이미지가 없다: $Image" }
        $existing = @(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}')
        if ($existing.Count -gt 0) { $p += ('chaos- 컨테이너가 이미 있다({0}). S6 관문 등 다른 실험이 끝난 뒤에 돈다' -f ($existing -join ', ')) }
    }
    return , $p
}

# 새 P3 스택과 Q0 준비(설계 5-2 Q0). 반환의 ok 가 거짓이면 그 실험은 BLOCKED 다.
function Start-P3Stack {
    param([string] $Dir)
    $res = [ordered]@{ ok = $false }
    Set-P3Overlay
    $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
    $code = Invoke-ComposeQuiet '--profile obs up -d --wait'
    $res['upRc'] = $code
    if ($code -ne 0) { $res['사유'] = "compose up 종료코드 $code"; return $res }
    $imageId = (& docker image inspect $Image --format '{{.Id}}' 2>$null | Out-String).Trim()
    foreach ($c in 'chaos-api-1', 'chaos-api-2', 'chaos-bots') {
        if ((& docker inspect -f '{{.Image}}' $c 2>$null | Out-String).Trim() -ne $imageId) { $res['사유'] = "$c 가 고정한 이미지로 뜨지 않았다"; return $res }
    }
    $rc = Invoke-GatePy @('harness/scale/protocol_latch.py', 'transition', '--target', '3', '--latch-band', '--out',
        (Get-Rel (Join-Path $Dir 'protocol.json'))) (Join-Path $Dir 'protocol.log')
    $res['transitionRc'] = $rc
    if ($rc -ne 0) { $res['사유'] = "P3 전환 실패(rc=$rc). protocol.json 을 본다"; return $res }
    $pre = Invoke-P3Preflight -Dir $Dir -TransitionFile (Join-Path $Dir 'protocol.json') -RunId "Q0-$Experiment-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    $res['preflight'] = [ordered]@{ ok = $pre['ok']; failed = $pre['failed']; relocated = $pre['relocated'] }
    if (-not $pre['ok']) { $res['사유'] = 'Q0 준비 판정 실패. p3-preflight.json 을 본다'; return $res }
    $res['ok'] = $true
    return $res
}

# 사후 판정(설계 5-3). 봇을 멈추고 비운 뒤 권위 run, summary.sql, 구간 경계 증분(전환 표본마다)을 뜬다. 반환의 ok 가 공통 무결성 축이다.
function Invoke-GatePost {
    param([string] $Dir, [string[]] $SegmentPre)
    $res = [ordered]@{}
    $res['botsStopped'] = Stop-BotLoad
    $res['drained'] = [bool](Wait-DrainZero -Sql $Global:CHAOS_DRAIN_SQL -TimeoutSec 120 -RequiredStreak 2)
    $run = $null
    try {
        # 모드의 첫 리스 자원으로 산 노드를 찾는다(P3 는 matching-p0, 되돌린 P2 는 matching-engine).
        $port = Get-LiveNodePort -Resource @(Get-ModeLeaseResources)[0]
        $run = Invoke-InvariantRun -Port $port -RunId "$Experiment-$(Get-Date -Format 'yyyyMMdd-HHmmss')" -Window 'post'
    } catch { $res['runError'] = $_.Exception.Message }
    if ($run) { Save-Json (Join-Path $Dir 'post_run.json') $run }
    $res['integrity'] = [bool]($run -and (Test-ExperimentIntegrity $run))
    Invoke-GatePy @('-c', 'import sys, json; sys.path.insert(0, "harness/scale"); import s3_common_axes as s; print(json.dumps(s.run_summary()))') `
        (Join-Path $Dir 'summary.json') | Out-Null
    $summary = Read-Json (Join-Path $Dir 'summary.json')
    $res['summaryOk'] = [bool]($summary -and $summary['ok'])
    Invoke-GatePy @('harness/scale/s7_axes.py', 'segment-count', '--out', (Get-Rel (Join-Path $Dir 'segment_post.json'))) (Join-Path $Dir 'segment_post.log') | Out-Null
    $segArgs = @('harness/scale/s7_axes.py', 'segment-judge')
    foreach ($p in $SegmentPre) { $segArgs += @('--pre', (Get-Rel $p)) }
    $segArgs += @('--post', (Get-Rel (Join-Path $Dir 'segment_post.json')), '--out', (Get-Rel (Join-Path $Dir 'segment.json')))
    Invoke-GatePy $segArgs (Join-Path $Dir 'segment.log') | Out-Null
    $seg = Read-Json (Join-Path $Dir 'segment.json')
    $res['segment'] = $(if ($seg) { $seg['verdict'] } else { $null })
    [System.IO.File]::WriteAllText((Join-Path $Dir 'leader_lease.json'), (Invoke-Psql "SELECT coalesce(json_agg(l), '[]'::json) FROM leader_lease l"),
        [System.Text.UTF8Encoding]::new($false))
    $res['ok'] = $res['drained'] -and $res['integrity'] -and $res['summaryOk'] -and $res['segment'] -eq 'PASS'
    return $res
}

# 스택을 내리기 전에 앱·LB 로그를 저장소 밖에 떨어뜨리고 해시만 남긴다(요청 식별자와 계정 흔적을 증거에 싣지 않는다).
function Save-ContainerLogs {
    param([string] $Dir)
    New-Item -ItemType Directory -Force -Path $localLogDir | Out-Null
    $logs = @()
    foreach ($c in 'chaos-api-1', 'chaos-api-2', 'chaos-bots', 'chaos-pg', 'chaos-lb', 'chaos-broker') {
        $path = Join-Path $localLogDir "$c.log"
        & cmd /c "docker logs $c > `"$path`" 2>&1"
        if (Test-Path $path) { $logs += [ordered]@{ container = $c; bytes = (Get-Item $path).Length; sha256 = (Get-FileHash $path -Algorithm SHA256).Hash.ToLower() } }
    }
    Save-Json (Join-Path $Dir 'container_logs.json') @($logs)
}

function Stop-P3Stack {
    $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
    Write-GateLog "DOWN rc=$code"
}

# 웹 관찰(설계 4-13). NEXT_PUBLIC_* 를 주고 웹을 한 번 빌드해 정적 export(web/out)를 harness/scale/web_out_server.py 로 띄운다.
# web/next.config.ts 가 output: "export" 라 next start 는 기동하지 않는다. 웹은 p0 소유 노드(api-1)에 직결한다. 막히면 사유를 적고
# ok 거짓을 돌려주며, 그 경우 W6·W7 은 NOT RUN(웹 관찰)이다.
function Start-GateWeb {
    param([string] $Dir)
    $res = [ordered]@{ ok = $false; port = $WebPort }
    if ($NoWeb) { $res['사유'] = '-NoWeb'; return $res }
    # dev 프로파일의 REST 교차 출처 허용이 http://localhost:3000 하나다(dev/DevConfig.java). 다른 포트면 거래 화면의 REST 가 막힌다.
    if ($WebPort -ne 3000) { $res['사유'] = "웹 포트 $WebPort 는 dev CORS 허용 출처(http://localhost:3000) 밖이다"; return $res }
    if (-not (Test-Path (Join-Path $repo 'web\node_modules'))) { $res['사유'] = 'web/node_modules 가 없다(npm ci 나 정션을 먼저 둔다)'; return $res }
    # 다른 서버가 이미 3000 번에 답하면 그 서버를 관찰하게 된다. 시작 전에 비어 있어야 한다.
    $busy = $false
    $probe = [System.Net.Sockets.TcpClient]::new()
    try { $busy = $probe.ConnectAsync('127.0.0.1', $WebPort).Wait(1000) -and $probe.Connected } catch { $busy = $false } finally { $probe.Dispose() }
    if ($busy) { $res['사유'] = "포트 $WebPort 를 다른 프로세스가 쓰고 있다"; return $res }
    $envs = @{ NEXT_PUBLIC_DATASOURCE = 'http'; NEXT_PUBLIC_API_BASE = "http://localhost:$($Global:CHAOS.Api1Port)"
        NEXT_PUBLIC_WS_BASE = "ws://localhost:$($Global:CHAOS.Api1Port)" }
    $res['env'] = $envs
    $saved = @{}
    foreach ($k in $envs.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $envs[$k]) }
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & npm --prefix (Join-Path $repo 'web') run build *> (Join-Path $Dir 'web_build.log'); $res['buildRc'] = $LASTEXITCODE }
    finally {
        $ErrorActionPreference = $eap
        foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) }
    }
    if ($res['buildRc'] -ne 0) { $res['사유'] = "웹 빌드 실패(rc=$($res['buildRc'])). web_build.log 를 본다"; return $res }
    # Start-Process 는 인자 배열을 공백으로만 잇는다. 저장소 경로에 공백이 있어도 한 인자로 가도록 따옴표로 감싼다.
    $out = '"{0}"' -f (Join-Path $repo 'web\out')
    $proc = Start-Process -FilePath 'python' -WorkingDirectory $repo -NoNewWindow -PassThru `
        -ArgumentList @('-B', 'harness/scale/web_out_server.py', '--root', $out, '--port', "$WebPort") `
        -RedirectStandardOutput (Join-Path $Dir 'web_serve.out') -RedirectStandardError (Join-Path $Dir 'web_serve.err')
    $res['pid'] = $proc.Id
    $up = Wait-For { (-not $proc.HasExited) -and $(try { (Invoke-WebRequest -Uri "http://localhost:$WebPort/exchange/KRW-BTC" -TimeoutSec 5).StatusCode -eq 200 } catch { $false }) } -TimeoutSec 30 -PollSec 1
    $res['ok'] = [bool]$up
    if (-not $up) {
        $res['사유'] = $(if ($proc.HasExited) { "정적 서버가 끝났다(rc=$($proc.ExitCode)). web_serve.err 를 본다" } else { "웹이 $WebPort 번에서 30초 안에 거래 화면을 내지 않았다" })
    }
    return $res
}

function Stop-GateWeb {
    param($Web)
    if ($Web -and $Web['pid']) { & taskkill /PID $Web['pid'] /T /F *> $null }
}

# 파티션 스냅샷 두 장의 그 노드 강등 증분(파티션별). 스냅샷은 s4_partition_axes.py snapshot 출력이다.
function Get-DemotionDelta {
    param($Pre, $Post, [string] $Node)
    $out = [ordered]@{ 'matching-p0' = 0.0; 'matching-p1' = 0.0 }
    if (-not $Pre -or -not $Post) { return $null }
    $after = $Post['nodes'][$Node]['metrics']; $before = $Pre['nodes'][$Node]['metrics']
    if (-not $after -or $after.ContainsKey('error') -or -not $before -or $before.ContainsKey('error')) { return $null }
    foreach ($k in $after.Keys) {
        if ($k -notlike 'exchange_partition_demotions_total*') { continue }
        $m = [regex]::Match($k, 'partition="([^"]+)"')
        if (-not $m.Success -or -not $out.Contains($m.Groups[1].Value)) { continue }
        $b = $(if ($before.ContainsKey($k)) { [double]$before[$k] } else { 0.0 })
        $out[$m.Groups[1].Value] += [double]$after[$k] - $b
    }
    return $out
}

# 실행기 실험(Q2·Q3a·Q3b·Q5·Q6·Q7). 실행기의 분리 판정에 Q 마다 더 거는 축을 붙인다.
function Invoke-RunnerExperiment {
    param([string] $Dir)
    $plan = $runnerPlan[$Experiment]
    $res = [ordered]@{ runner = 'adr0022-stage5-chaos.ps1'; experiment = $plan.exp }
    $runArgs = @('-NoProfile', '-File', $runner, '-Image', $Image, '-Experiments', $plan.exp, '-Protocol', '3', '-P3', '-Resource', 'matching-p1',
        '-EvidenceDir', (Join-Path $Dir 'runner'), '-SurvivorLoadOps', "$SurvivorLoadOps") + $plan.extra
    if ($Experiment -eq 'Q2') {
        $script:web = Start-GateWeb -Dir $Dir
        $res['web'] = $script:web
        if ($script:web['ok']) { $runArgs += @('-WebObserveUrl', "http://localhost:$WebPort/exchange/KRW-BTC") }
    }
    Write-GateLog ('RUN pwsh {0}' -f ($runArgs -join ' '))
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & pwsh @runArgs *> (Join-Path $Dir 'runner.log'); $res['runnerRc'] = $LASTEXITCODE }
    finally { $ErrorActionPreference = $eap; Stop-GateWeb $script:web }
    $summary = Read-Json (Join-Path $Dir 'runner\summary.json')
    $row = $(if ($summary -and $summary['experiments']) { @($summary['experiments'])[0] } else { $null })
    $res['runnerVerdict'] = $(if ($row) { $row['분리 판정'] } else { 'BLOCKED' })
    $expDir = Join-Path $Dir "runner\$($plan.exp)"
    $axes = [ordered]@{}
    $verdictFile = Read-Json (Join-Path $expDir 'verdict.json')
    $rto = $(if ($verdictFile -and $verdictFile['failover']) { $verdictFile['failover']['rtoSeconds'] } else { $null })
    $res['p1RtoSec'] = $rto
    $pre = Read-Json (Join-Path $expDir 'partition_pre.json')
    $post = Read-Json (Join-Path $expDir 'partition_post.json')
    switch ($Experiment) {
        'Q2' {
            $axes['p1_rto_le_p2_max_plus_ttl'] = $null -ne $rto -and [double]$rto -le $Q2RtoLimitSec
            $pf = Read-Json (Join-Path $expDir 'p3-preflight.json')
            $lease = $null
            try { $lease = Get-Content (Join-Path $expDir 'leader_lease.json') -Raw -Encoding utf8 | ConvertFrom-Json } catch { $lease = $null }
            $p0Before = $(if ($pf) { @($pf['partitions']['chaos-api-1']['partitions'] | Where-Object { $_['resource'] -eq 'matching-p0' })[0]['epoch'] } else { $null })
            $p0After = $(if ($lease) { @($lease | Where-Object { $_.resource -eq 'matching-p0' })[0].epoch } else { $null })
            $res['p0Epoch'] = [ordered]@{ before = $p0Before; after = $p0After }
            $axes['p0_epoch_unchanged'] = $null -ne $p0Before -and $null -ne $p0After -and [long]$p0Before -eq [long]$p0After
            $axes['p3_epoch_rule'] = [bool]($verdictFile -and $verdictFile['metrics'] -and $verdictFile['metrics']['epochOk'])
        }
        'Q3a' {
            $d = Get-DemotionDelta -Pre $pre -Post $post -Node 'chaos-api-2'
            $res['api2Demotions'] = $d
            $axes['api2_p1_demoted'] = $null -ne $d -and $d['matching-p1'] -ge 1
            $axes['api2_p0_not_demoted'] = $null -ne $d -and $d['matching-p0'] -eq 0
        }
        'Q3b' {
            $d = Get-DemotionDelta -Pre $pre -Post $post -Node 'chaos-api-1'
            $res['api1Demotions'] = $d
            $axes['api1_both_partitions_demoted'] = $null -ne $d -and $d['matching-p0'] -ge 1 -and $d['matching-p1'] -ge 1
            $vw = Read-Json (Join-Path $expDir 'victim_writes.json')
            $take = $(if ($vw -and $vw['facts']) { $vw['facts']['takeover_after_inject_s'] } else { $null })
            $res['takeoverAfterInjectSec'] = $take
            $axes['preferred_p1_before_p0'] = $take -and $take.ContainsKey('matching-p1') -and $take.ContainsKey('matching-p0') -and
                [double]$take['matching-p1'] -lt [double]$take['matching-p0']
            $rtoP0 = Read-Json (Join-Path $expDir 'rto_matching-p0.json')
            $res['p0Rto'] = $(if ($rtoP0) { $rtoP0['facts']['rto_s'] } else { $null })
            $axes['p0_rto_lt_30s'] = [bool]($rtoP0 -and $rtoP0['verdict'] -eq 'PASS')
        }
        'Q5' {
            $d = Get-DemotionDelta -Pre $pre -Post $post -Node 'chaos-api-2'
            $res['api2Demotions'] = $d
            $axes['api2_p1_demoted'] = $null -ne $d -and $d['matching-p1'] -ge 1
        }
    }
    $res['axes'] = $axes
    $failed = @($axes.Keys | Where-Object { -not $axes[$_] })
    $res['failed'] = $failed
    $rv = [string]$res['runnerVerdict']
    $res['verdict'] = $(if ($rv -eq 'PASS' -and $failed.Count -eq 0) { 'PASS' }
        elseif ($rv -eq 'PASS') { "FAIL($($failed -join ','))" }
        else { $rv })
    return $res
}

# 생존 파티션(p0) 마켓만 치는 k6 개방 루프(설계 5-2 공통 판정의 ACK 간격 부하). 봇만으로는 p0 마켓마다 판정 창(약 20초)에 NEW 10건을
# 채우지 못해 Q9·Q10 의 ACK 양성 축이 모자랐다(2026-09-26 첫 Q9). 실행기 실험의 -SurvivorLoadOps 와 같은 부하다. 반환은 기록이다.
function Start-GateSurvivorLoad {
    param([string] $Dir, [int] $DurationSec)
    $split = Get-PartitionMarkets
    $markets = $(if ($split) { @($split['matching-p0']) -join ',' } else { '' })
    $keys = $(if ($markets) { Initialize-LoadKeys -Markets $markets } else { 0 })
    if ($keys -lt 1) { return [ordered]@{ ok = $false; '사유' = '생존 파티션 부하 계정 발급 실패' } }
    $k6 = Start-SurvivorLoad -Name "pml-s7-k6-$Experiment" -Markets $markets -Ops $SurvivorLoadOps -DurationSec $DurationSec -RunId "s7surv$Experiment"
    Write-GateLog "K6 start $($k6.name) rc=$($k6.rc) markets=$markets ops=$SurvivorLoadOps"
    $k6['ok'] = $k6.rc -eq 0
    return $k6
}

function Stop-GateSurvivorLoad {
    param($Load)
    if ($Load -and $Load['name']) {
        New-Item -ItemType Directory -Force -Path $localLogDir | Out-Null
        Stop-SurvivorLoad -Name $Load['name'] -LogPath (Join-Path $localLogDir "$($Load['name']).log")
        Write-GateLog "K6 stop $($Load['name'])"
    }
}
function Get-JudgeVerdict {
    param([string] $Path)
    $j = Read-Json $Path
    return $(if ($j) { [string]$j['verdict'] } else { 'NOT RUN' })
}

# Q4 되돌리기(3->2)와 재진입(2->3). 설계 5-2 Q4.
function Invoke-Q4 {
    param([string] $Dir)
    $res = [ordered]@{}
    $axes = [ordered]@{}
    $feeSql = "SELECT json_build_object('s0', (SELECT b.balance FROM balances b JOIN accounts a ON a.id = b.account_id JOIN assets s ON s.id = a.asset_id " +
        "WHERE a.owner_type = 'SYSTEM' AND a.purpose = 'FEE_INCOME' AND a.shard = 0 AND s.symbol = 'KRW'), 's1', (SELECT b.balance FROM balances b " +
        "JOIN accounts a ON a.id = b.account_id JOIN assets s ON s.id = a.asset_id WHERE a.owner_type = 'SYSTEM' AND a.purpose = 'FEE_INCOME' " +
        "AND a.shard = 1 AND s.symbol = 'KRW'))"
    Write-GateLog "Q4 P3 봇 부하 $Q4LoadSec 초"
    Start-Sleep -Seconds $Q4LoadSec
    $rc = Invoke-GatePy @('harness/scale/protocol_latch.py', 'transition', '--target', '2', '--allow-downgrade', '--out',
        (Get-Rel (Join-Path $Dir 'protocol_down.json'))) (Join-Path $Dir 'protocol_down.log')
    $res['downRc'] = $rc
    $down = Read-Json (Join-Path $Dir 'protocol_down.json')
    $axes['rollback_ok'] = $rc -eq 0 -and $down -and $down['ok']
    $history = $(if ($down) { @($down['history']) } else { @() })
    $axes['history_last_is_p2'] = $history.Count -ge 2 -and [int]$history[-1]['protocol'] -eq 2 -and [int]$history[-2]['protocol'] -eq 3
    $parts = Get-NodePartitions
    $axes['api1_engine_leader'] = (Get-PartitionOwner -Partitions $parts -Resource 'matching-engine') -eq 'chaos-api-1'
    $axes['api2_follower'] = $parts['chaos-api-2'] -and @($parts['chaos-api-2'].partitions | Where-Object { $_.state -eq 'ACCEPTING' }).Count -eq 0
    foreach ($n in 'chaos-api-1', 'chaos-api-2') {
        $port = $(if ($n -eq 'chaos-api-1') { $Global:CHAOS.Api1Port } else { $Global:CHAOS.Api2Port })
        $boot = Get-AppMetric -Port $port -Pattern 'exchange_leader_protocol_boot(?:\{[^}]*\})?\s+([-0-9.eE]+)'
        $axes["protocol_boot_2_$n"] = $null -ne $boot -and [double]$boot -eq 2
    }
    $feeAfterDown = $null; $feeLater = $null
    try { $feeAfterDown = Invoke-Psql $feeSql | ConvertFrom-Json } catch { $feeAfterDown = $null }
    Write-GateLog "Q4 P2 봇 부하 $Q4LoadSec 초"
    Start-Sleep -Seconds $Q4LoadSec
    try { $feeLater = Invoke-Psql $feeSql | ConvertFrom-Json } catch { $feeLater = $null }
    $res['fee'] = [ordered]@{ afterRollback = $feeAfterDown; afterP2Load = $feeLater }
    $axes['shard1_unchanged_after_rollback'] = $null -ne $feeAfterDown -and $null -ne $feeLater -and [decimal]$feeLater.s1 -eq [decimal]$feeAfterDown.s1
    $axes['new_fees_on_shard0'] = $null -ne $feeAfterDown -and $null -ne $feeLater -and [decimal]$feeLater.s0 -gt [decimal]$feeAfterDown.s0
    # 샤드 1 이 전환 전과 같음: 3->2 호출 직전 값은 전환 도구가 봇을 멈추고 드레인한 뒤이므로 되돌린 직후 값과 같아야 한다(되돌리기는 잔고를
    # 옮기지 않는다). 되돌린 뒤 P2 부하가 샤드 1 을 바꾸지 않았는지를 위 축이 본다.
    $post = Invoke-GatePost -Dir $Dir -SegmentPre @((Join-Path $Dir 'protocol.json'), (Join-Path $Dir 'protocol_down.json'))
    $res['postAfterRollback'] = $post
    $axes['post_rollback_integrity_and_segment'] = $post['ok']
    $postRun = Read-Json (Join-Path $Dir 'post_run.json')
    $c22c30 = $(if ($postRun) { @($postRun['checks'] | Where-Object { $_['checkName'] -in @('C22_FENCING_MONOTONIC', 'C30_FENCING_RESOURCE_MATCH') }) } else { @() })
    $axes['c22_c30_zero'] = $c22c30.Count -eq 2 -and @($c22c30 | Where-Object { [long]$_['violatedRows'] -ne 0 }).Count -eq 0
    Restore-BotLoad -WasStopped $true
    $rc = Invoke-GatePy @('harness/scale/protocol_latch.py', 'transition', '--target', '3', '--out',
        (Get-Rel (Join-Path $Dir 'protocol_reentry.json'))) (Join-Path $Dir 'protocol_reentry.log')
    $re = Read-Json (Join-Path $Dir 'protocol_reentry.json')
    $axes['reentry_ok'] = $rc -eq 0 -and $re -and $re['ok']
    $axes['reentry_assignment_ok'] = $re -and $re['assignment'] -eq 'ok'
    $post2Dir = Join-Path $Dir 'reentry'
    New-Item -ItemType Directory -Force -Path $post2Dir | Out-Null
    $post2 = Invoke-GatePost -Dir $post2Dir -SegmentPre @((Join-Path $Dir 'protocol.json'), (Join-Path $Dir 'protocol_down.json'), (Join-Path $Dir 'protocol_reentry.json'))
    $res['postAfterReentry'] = $post2
    $axes['post_reentry_integrity_and_segment'] = $post2['ok']
    $res['axes'] = $axes
    $res['failed'] = @($axes.Keys | Where-Object { -not $axes[$_] })
    $res['verdict'] = $(if ($res['failed'].Count -eq 0) { 'PASS' } else { 'FAIL' })
    return $res
}

# Q13 W7 한 연결로 두 파티션 마켓 수신(설계 5-2 Q13). 관찰자와 웹 관찰을 같은 창에 돌린다.
function Invoke-Q13 {
    param([string] $Dir)
    $res = [ordered]@{}
    $split = Get-PartitionMarkets
    $observed = @('KRW-BTC', 'KRW-ETH', 'KRW-XRP')
    $p0 = @($observed | Where-Object { $split -and @($split['matching-p0']) -contains $_ })
    $p1 = @($observed | Where-Object { $split -and @($split['matching-p1']) -contains $_ })
    $script:web = Start-GateWeb -Dir $Dir
    $res['web'] = $script:web
    $watch = Start-RelayWatch -Name 'pml-s7-watch-q13' -OutDir $Dir -DurationSec ($Q13LoadSec + 120) -Urls 'ws://chaos-api-1:8080/ws/v1' -Markets ($observed -join ',')
    $res['watch'] = $watch
    $webProc = $null
    try {
        $from = (Get-Date).ToUniversalTime()
        if ($script:web['ok']) {
            $webProc = Start-Process -FilePath 'node' -WorkingDirectory $repo -NoNewWindow -PassThru `
                -ArgumentList @('harness/scripts/s6-web-resync.mjs', "--url=http://localhost:$WebPort/exchange/KRW-BTC", "--duration=$Q13LoadSec",
                    "--out=$(Get-Rel (Join-Path $Dir 'web_resync.json'))") `
                -RedirectStandardOutput (Join-Path $Dir 'web_resync.out') -RedirectStandardError (Join-Path $Dir 'web_resync.err')
        }
        Write-GateLog "Q13 봇 부하 $Q13LoadSec 초"
        Start-Sleep -Seconds $Q13LoadSec
        $to = (Get-Date).ToUniversalTime()
        if ($webProc -and -not $webProc.WaitForExit(120000)) { Stop-Process -Id $webProc.Id -Force }
    } finally {
        Stop-RelayWatch -Name 'pml-s7-watch-q13' -LogPath (Join-Path $Dir 'watch.log')
        Stop-GateWeb $script:web
    }
    Invoke-GatePy @('harness/scale/s6_relay_axes.py', 'watch', '--log', (Get-Rel (Join-Path $Dir 'watch.log')), '--out', (Get-Rel (Join-Path $Dir 'watch.json'))) `
        (Join-Path $Dir 'watch_merge.log') | Out-Null
    $rc = Invoke-GatePy @('harness/scale/s6_relay_axes.py', 'judge', '--experiment', 'W7', '--evidence', (Get-Rel $Dir), '--node', 'chaos-api-1',
        '--window-from', $from.AddSeconds(30).ToString('o'), '--window-to', $to.ToString('o'), '--p0-markets', ($p0 -join ','),
        '--p1-markets', ($p1 -join ','), '--out', (Get-Rel (Join-Path $Dir 'w7.json'))) (Join-Path $Dir 'w7.log')
    $res['w7Rc'] = $rc
    $res['verdict'] = Get-JudgeVerdict (Join-Path $Dir 'w7.json')
    return $res
}

# Q15 힌트 없는 쓰기(기록, 설계 5-2 Q15). 12마켓 부하를 MARKET_HINT=0 으로 돌리고 LB 로그를 요약한다.
function Invoke-Q15 {
    param([string] $Dir)
    $res = [ordered]@{}
    $markets = 'KRW-BTC,KRW-ETH,KRW-SOL,KRW-XRP,KRW-DOGE,KRW-ADA,KRW-TRX,KRW-LINK,KRW-DOT,KRW-MATIC,KRW-AVAX,KRW-ATOM'
    $keys = Initialize-LoadKeys -Markets $markets
    $res['keys'] = $keys
    if ($keys -lt 1) { $res['verdict'] = 'NOT RUN'; $res['사유'] = '부하 계정 발급 실패'; return $res }
    $from = (Get-Date).ToUniversalTime()
    $k6 = Start-SurvivorLoad -Name 'pml-s7-k6-q15' -Markets $markets -Ops $Q15Ops -DurationSec $Q15LoadSec -RunId 's7q15nohint' -Hint 0
    $res['k6'] = $k6
    Start-Sleep -Seconds ($Q15LoadSec + 5)
    $to = (Get-Date).ToUniversalTime()
    New-Item -ItemType Directory -Force -Path $localLogDir | Out-Null
    Stop-SurvivorLoad -Name 'pml-s7-k6-q15' -LogPath (Join-Path $localLogDir 'k6-q15.log')
    $lbLog = Join-Path $localLogDir 'chaos-lb-q15.log'
    $since = ([DateTimeOffset]$from).ToUnixTimeSeconds()
    & cmd /c "docker logs --since $since chaos-lb > `"$lbLog`" 2>&1"
    $rc = Invoke-GatePy @('harness/scale/s7_axes.py', 'lb-hintless', '--log', $lbLog, '--window-from', $from.ToString('o'),
        '--window-to', $to.ToString('o'), '--out', (Get-Rel (Join-Path $Dir 'hintless.json'))) (Join-Path $Dir 'hintless.log')
    $res['verdict'] = $(if ($rc -eq 0) { 'RECORD' } else { 'NOT RUN' })
    return $res
}

# Q12 처리량(기록, 설계 5-2 Q12). 같은 날 P2 스택(중계 오버레이)과 P3 스택에서 600/s 칸을 P2a, P3a, P2b, P3b 순서로 돈다. roundtrip_probe
# 가 칸마다 스택을 새로 세운다(clean_suite, CHAOS_EXTRA_COMPOSE). 판정은 무결성 축이고 칸 값은 기록이다.
function Invoke-Q12 {
    param([string] $Dir)
    $res = [ordered]@{ cells = [ordered]@{} }
    $relay = [System.IO.Path]::GetFullPath((Join-Path $repo 'harness\docker-compose.relay.yml'))
    $p3 = [System.IO.Path]::GetFullPath((Join-Path $repo 'harness\docker-compose.p3.yml'))
    foreach ($cell in @(@('P2a', 2), @('P3a', 3), @('P2b', 2), @('P3b', 3))) {
        $label = $cell[0]; $protocol = $cell[1]
        $extra = $(if ($protocol -eq 3) { "$relay;$p3" } else { $relay })
        $rc = Invoke-GatePy @('harness/scale/roundtrip_probe.py', '--label', $label, '--rate', '600', '--protocol-before-traffic', "$protocol",
            '--warmup', "$Q12WarmupSec", '--duration', "$Q12DurationSec", '--out', (Get-Rel (Join-Path $Dir "$label.json"))) (Join-Path $Dir "$label.log") @{ CHAOS_EXTRA_COMPOSE = $extra; SCALE_LOAD_KEYS = '16' }
        $res['cells'][$label] = [ordered]@{ rc = $rc; file = "$label.json" }
    }
    $res['verdict'] = $(if (@($res['cells'].Values | Where-Object { $_['rc'] -ne 0 }).Count -eq 0) { 'RECORD' } else { 'NOT RUN' })
    return $res
}

# ---------------------------------------------------------------------------- 시작
if (Test-Path (Join-Path $EvidenceDir 'gate.json')) { Write-Host '이미 gate.json 이 있다. 증거를 덮지 않는다.'; exit 5 }
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$needsStack = $Experiment -ne 'Q14'
$problems = Get-GateProblems -NeedsStack $needsStack
$gate = [ordered]@{ experiment = $Experiment; started = (Now-Iso); image = $Image; project = $Global:CHAOS.Project
    harnessCommit = (& git -C $repo rev-parse HEAD | Out-String).Trim() }
if ($problems.Count -gt 0) {
    $gate['verdict'] = 'BLOCKED'; $gate['사유'] = $problems
    Save-Json (Join-Path $EvidenceDir 'gate.json') $gate
    Write-GateLog ('BLOCKED {0}' -f ($problems -join '; '))
    exit 5
}
Write-GateLog "START $Experiment"

$stackUp = $false
try {
    if ($runnerPlan.ContainsKey($Experiment)) {
        # 실행기가 스택·Q0·공통 판정·down -v 를 한다. 오버레이 목록은 실행기가 -P3 로 채우므로 여기서는 비운다.
        Remove-Item Env:CHAOS_COMPOSE_OVERLAYS -ErrorAction SilentlyContinue
        $gate['result'] = Invoke-RunnerExperiment -Dir $EvidenceDir
    } elseif ($Experiment -eq 'Q14') {
        $rc = Invoke-GatePy @('harness/scale/s7_c22_cost.py', '--rows', "$Q14Rows", '--out', (Get-Rel (Join-Path $EvidenceDir 'c22_cost.json'))) (Join-Path $EvidenceDir 'c22_cost.log')
        $gate['result'] = [ordered]@{ rc = $rc; verdict = (Get-JudgeVerdict (Join-Path $EvidenceDir 'c22_cost.json')) }
    } elseif ($Experiment -eq 'Q12') {
        $stackUp = $true
        $gate['result'] = Invoke-Q12 -Dir $EvidenceDir
    } else {
        $stackUp = $true
        $stack = Start-P3Stack -Dir $EvidenceDir
        $gate['stack'] = $stack
        if (-not $stack['ok']) {
            $gate['result'] = [ordered]@{ verdict = 'BLOCKED'; '사유' = $stack['사유'] }
        } else {
            $segPre = @((Join-Path $EvidenceDir 'protocol.json'))
            $r = [ordered]@{}
            switch ($Experiment) {
                'Q1' {
                    $r['rc'] = Invoke-GatePy @('harness/scale/s7_double_spend.py', '--rounds', "$Q1Rounds", '--out', (Get-Rel (Join-Path $EvidenceDir 'double_spend.json'))) (Join-Path $EvidenceDir 'double_spend.log')
                    Invoke-GatePy @('harness/scale/s7_axes.py', 'double-spend', '--in', (Get-Rel (Join-Path $EvidenceDir 'double_spend.json')), '--out', (Get-Rel (Join-Path $EvidenceDir 'double_spend_verdict.json'))) (Join-Path $EvidenceDir 'double_spend_verdict.log') | Out-Null
                    $r['judge'] = Get-JudgeVerdict (Join-Path $EvidenceDir 'double_spend_verdict.json')
                }
                'Q4' { $r = Invoke-Q4 -Dir $EvidenceDir }
                { $_ -in @('Q8', 'Q8c') } {
                    $feeArgs = @('harness/scale/s7_fee_coupling.py', '--pause-s', '3', '--latency-ms', '100', '--out', (Get-Rel (Join-Path $EvidenceDir 'fee.json')))
                    if ($Experiment -eq 'Q8c') { $feeArgs += '--control' }
                    $r['rc'] = Invoke-GatePy $feeArgs (Join-Path $EvidenceDir 'fee.log')
                    if ($Experiment -eq 'Q8') {
                        Invoke-GatePy @('harness/scale/s7_axes.py', 'fee-coupling', '--in', (Get-Rel (Join-Path $EvidenceDir 'fee.json')), '--out', (Get-Rel (Join-Path $EvidenceDir 'fee_verdict.json'))) (Join-Path $EvidenceDir 'fee_verdict.log') | Out-Null
                        $r['judge'] = Get-JudgeVerdict (Join-Path $EvidenceDir 'fee_verdict.json')
                    } else { $r['judge'] = $(if ($r['rc'] -eq 0) { 'RECORD' } else { 'NOT RUN' }) }
                }
                'Q9' {
                    $r['survivorLoad'] = Start-GateSurvivorLoad -Dir $EvidenceDir -DurationSec 600
                    try {
                        Start-Sleep -Seconds 150   # 생존 파티션 ACK 기준선(주입 전 120초)을 채운다
                        $r['rc'] = Invoke-GatePy @('harness/scale/s7_axes.py', 'relocate', '--out', (Get-Rel (Join-Path $EvidenceDir 'relocate.json'))) (Join-Path $EvidenceDir 'relocate.log')
                    } finally { Stop-GateSurvivorLoad $r['survivorLoad'] }
                    $r['judge'] = Get-JudgeVerdict (Join-Path $EvidenceDir 'relocate.json')
                }
                'Q10' {
                    $r['survivorLoad'] = Start-GateSurvivorLoad -Dir $EvidenceDir -DurationSec 600
                    try {
                        Start-Sleep -Seconds 150
                        $r['rc'] = Invoke-GatePy @('harness/scale/s7_market_fatal.py', '--market', 'KRW-ETH', '--stop-before-post', "pml-s7-k6-$Experiment", '--out', (Get-Rel (Join-Path $EvidenceDir 'fatal.json'))) (Join-Path $EvidenceDir 'fatal.log')
                    } finally { Stop-GateSurvivorLoad $r['survivorLoad'] }
                    Invoke-GatePy @('harness/scale/s7_axes.py', 'market-fatal', '--in', (Get-Rel (Join-Path $EvidenceDir 'fatal.json')), '--out', (Get-Rel (Join-Path $EvidenceDir 'fatal_verdict.json'))) (Join-Path $EvidenceDir 'fatal_verdict.log') | Out-Null
                    $r['judge'] = Get-JudgeVerdict (Join-Path $EvidenceDir 'fatal_verdict.json')
                }
                'Q11' {
                    $r['rc'] = Invoke-GatePy @('harness/scale/s7_axes.py', 'cold-assign', '--out', (Get-Rel (Join-Path $EvidenceDir 'cold.json'))) (Join-Path $EvidenceDir 'cold.log')
                    $r['judge'] = Get-JudgeVerdict (Join-Path $EvidenceDir 'cold.json')
                }
                'Q13' { $r = Invoke-Q13 -Dir $EvidenceDir; $r['judge'] = $r['verdict'] }
                'Q15' { $r = Invoke-Q15 -Dir $EvidenceDir; $r['judge'] = $r['verdict'] }
                'Q16' {
                    # 회원 폐쇄는 관리 평면(chaos-admin, 루트 compose.yaml 의 admin 프로파일)이 한다. P3 스택 파일에는 이 서비스가 없어 첫 Q16
                    # 이 '관리 세션 발급 실패'로 NOT RUN 이었다(2026-09-26). S5 설계 5절의 명령 그대로 같은 프로젝트에 --no-deps 로만 띄운다.
                    # 스택을 내릴 때 down --remove-orphans 가 함께 지운다.
                    $adminCmd = "docker compose -p $($Global:CHAOS.Project) -f `"$(Join-Path $repo 'compose.yaml')`" --env-file `"$($Global:CHAOS.EnvFile)`" --profile admin up -d --no-deps --wait chaos-admin"
                    Write-GateLog "RUN $adminCmd"
                    & cmd /c "$adminCmd >nul 2>nul"
                    $r['adminUpRc'] = $LASTEXITCODE
                    Write-GateLog "RC $($r['adminUpRc'])"
                    $r['rc'] = Invoke-GatePy @('harness/scale/s5_route_probe.py', 'q16', '--out', (Get-Rel (Join-Path $EvidenceDir 'q16.json'))) (Join-Path $EvidenceDir 'q16.log')
                    Invoke-GatePy @('harness/scale/s7_axes.py', 'handoff', '--in', (Get-Rel (Join-Path $EvidenceDir 'q16.json')), '--out', (Get-Rel (Join-Path $EvidenceDir 'handoff.json'))) (Join-Path $EvidenceDir 'handoff.log') | Out-Null
                    $r['judge'] = Get-JudgeVerdict (Join-Path $EvidenceDir 'handoff.json')
                }
            }
            if ($Experiment -ne 'Q4') {
                $r['post'] = Invoke-GatePost -Dir $EvidenceDir -SegmentPre $segPre
                $judge = [string]$r['judge']
                $r['verdict'] = $(if ($judge -in @('RECORD', 'NOT RUN', 'BLOCKED')) { $judge }
                    elseif ($judge -ne 'PASS') { "FAIL($judge)" }
                    elseif (-not $r['post']['ok']) { 'FAIL(사후 판정)' }
                    else { 'PASS' })
            }
            $gate['result'] = $r
        }
        Save-ContainerLogs -Dir $EvidenceDir
    }
} catch {
    $gate['result'] = [ordered]@{ verdict = 'BLOCKED'; '사유' = $_.Exception.Message }
} finally {
    Stop-GateWeb $script:web
    if ($stackUp) { Stop-P3Stack }
    Remove-Item Env:CHAOS_COMPOSE_OVERLAYS -ErrorAction SilentlyContinue
}
$gate['finished'] = (Now-Iso)
$gate['verdict'] = [string]$gate['result']['verdict']
Save-Json (Join-Path $EvidenceDir 'gate.json') $gate
Write-GateLog "END $Experiment verdict=$($gate['verdict'])"
switch -Wildcard ($gate['verdict']) {
    'PASS' { exit 0 }
    'RECORD' { exit 0 }
    'NOT RUN*' { exit 3 }
    'BLOCKED*' { exit 5 }
    default { exit 1 }
}
