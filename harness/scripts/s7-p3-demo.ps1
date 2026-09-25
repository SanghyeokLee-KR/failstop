# ADR 0034 S7 데모 재현 스크립트(docs/reference/architecture/마켓분할_리더_S7_설계.md 4-12). 발표용 녹화 장면을 같은 결과로 다시
# 만든다. 2026-09-26 관문 주행 끝에 -NoBuild -Down 으로 한 번 돌려 확인했다(harness/evidence/pml-S7-20260926-0300/Demo).
# 녹화는 사람이 한다(Grafana 파티션 패널, 관리 화면 리스 표, 웹 거래 화면 KRW-BTC·KRW-ETH).
#
# 흐름. 가드(프로젝트 exchange-pml, 이미지 exchange-chaos-app:pml, 떠 있는 chaos-* 없음)를 확인하고 P3 스택을 세운다(up.ps1 -P3 -Obs,
# -NoBuild 면 굽지 않고 compose 로만 세운다). 전환 도구로 P3 에 올리고(--target 3 --latch-band) Q0 준비 판정을 적은 뒤, 봇과 힌트를 실은
# k6 부하(12마켓, MARKET_HINT=1)를 켠다. 두 노드의 파티션 표와 리더 epoch 를 화면에 적고, -KillAfterSec 뒤 api-2(p1 소유 노드)를
# docker kill 한다. p1 이 api-1 로 옮은 것을 본 뒤 p1 RTO(s7_axes.py partition-rto), p0 마켓 ACK 간격(s7_axes.py ack-interval), 권위 run
# 결과를 적는다. 판정기는 관문(Q2)과 같은 s7_axes.py 라 녹화 화면의 수치가 관문 수치와 같은 정의다. 스택은 녹화를 위해 남기고, -Down
# 을 주면 -p exchange-pml 이 붙은 down -v 로 내린다. 결과는 -EvidenceDir 의 demo.json 이다.
#
# 사용: pwsh -NoProfile -File harness/scripts/s7-p3-demo.ps1 [-NoBuild] [-KillAfterSec 60] [-EvidenceDir <폴더>] [-Down]
# 비밀 파일(harness/.env.chaos, harness/load/secrets/*.json)은 경로로만 넘기고 읽지 않는다.
param(
    [switch] $NoBuild,
    [int] $KillAfterSec = 60,
    [int] $LoadOps = 20,
    [string] $EvidenceDir = '',
    [switch] $Down
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONIOENCODING = 'utf-8'
. (Join-Path $PSScriptRoot 'lib.ps1')
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $EvidenceDir) { $EvidenceDir = Join-Path $repo "harness\evidence\pml-s7-demo-$(Get-Date -Format 'yyyyMMdd-HHmm')" }
$markets = 'KRW-BTC,KRW-ETH,KRW-SOL,KRW-XRP,KRW-DOGE,KRW-ADA,KRW-TRX,KRW-LINK,KRW-DOT,KRW-MATIC,KRW-AVAX,KRW-ATOM'

function Get-Rel { param([string] $Path) return [System.IO.Path]::GetRelativePath($repo, $Path) }

function Invoke-DemoPy {
    param([string[]] $ArgList, [string] $Log)
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    Push-Location $repo
    try { & python @ArgList *> $Log; return $LASTEXITCODE } finally { Pop-Location; $ErrorActionPreference = $eap }
}

function Show-Partitions {
    param([string] $Title)
    Write-Section $Title
    $parts = Get-NodePartitions
    foreach ($n in $parts.Keys) {
        foreach ($p in @($parts[$n].partitions)) {
            if ($p) { Write-Host ('{0,-12} {1,-12} {2,-10} epoch={3}' -f $n, $p.resource, $p.state, $p.epoch) }
        }
        if (-not $parts[$n]) { Write-Host ('{0,-12} (응답 없음)' -f $n) }
    }
    return $parts
}

$problems = @()
if ($PSVersionTable.PSVersion.Major -lt 7) { $problems += 'pwsh 7 로 실행한다' }
if ($Global:CHAOS.Project -ne 'exchange-pml') { $problems += "CHAOS_PROJECT 가 exchange-pml 이 아니다($($Global:CHAOS.Project))" }
if ($env:CHAOS_APP_IMAGE -ne 'exchange-chaos-app:pml') { $problems += 'CHAOS_APP_IMAGE 는 exchange-chaos-app:pml 이다' }
if (-not (Test-Path $Global:CHAOS.EnvFile)) { $problems += 'harness/.env.chaos 가 없다' }
$existing = @(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}')
if ($existing.Count -gt 0) { $problems += ('chaos- 컨테이너가 이미 있다({0})' -f ($existing -join ', ')) }
if ($problems.Count -gt 0) { Write-Host ('시작하지 않는다: {0}' -f ($problems -join '; ')); exit 5 }
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$demo = [ordered]@{ started = (Now-Iso); project = $Global:CHAOS.Project; image = $env:CHAOS_APP_IMAGE }

Write-Section 'P3 스택'
if ($NoBuild) {
    Set-P3Overlay
    $code = Invoke-ComposeQuiet '--profile obs up -d --wait'
    if ($code -ne 0) { throw "compose up 종료코드 $code" }
} else {
    & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'up.ps1') -P3 -Obs
    if ($LASTEXITCODE -ne 0) { throw "up.ps1 -P3 -Obs 종료코드 $LASTEXITCODE" }
    Set-P3Overlay
}

Write-Section 'P3 전환(--target 3 --latch-band)'
$rc = Invoke-DemoPy @('harness/scale/protocol_latch.py', 'transition', '--target', '3', '--latch-band', '--out', (Get-Rel (Join-Path $EvidenceDir 'protocol.json'))) (Join-Path $EvidenceDir 'protocol.log')
if ($rc -ne 0) { throw "P3 전환 실패(rc=$rc). $EvidenceDir\protocol.json 을 본다" }
$pre = Invoke-P3Preflight -Dir $EvidenceDir -TransitionFile (Join-Path $EvidenceDir 'protocol.json') -RunId "DEMO-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
$demo['preflight'] = [ordered]@{ ok = $pre['ok']; failed = $pre['failed'] }
Write-Host ("Q0 준비: ok={0} {1}" -f $pre['ok'], ($pre['failed'] -join ','))
if (-not $pre['ok']) { throw 'Q0 준비 판정 실패. p3-preflight.json 을 본다' }

Write-Section '부하(봇 + k6 12마켓 힌트)'
$keys = Initialize-LoadKeys -Markets $markets
if ($keys -lt 1) { throw '부하 계정 발급 실패' }
$k6 = Start-SurvivorLoad -Name 'pml-s7-k6-demo' -Markets $markets -Ops $LoadOps -DurationSec ($KillAfterSec + 300) -RunId 's7demo'
$demo['load'] = $k6
$split = Get-PartitionMarkets
$before = Show-Partitions '두 노드의 파티션 표(kill 전)'
$p1Row = @($before['chaos-api-2'].partitions | Where-Object { $_.resource -eq 'matching-p1' -and $_.state -eq 'ACCEPTING' })
if ($p1Row.Count -ne 1 -or $null -eq $p1Row[0].epoch) { throw 'api-2 가 matching-p1 을 ACCEPTING 으로 쥐지 않았다. Q0 준비를 다시 본다' }
$p1EpochBefore = $p1Row[0].epoch
$demo['p1EpochBefore'] = $p1EpochBefore

for ($s = $KillAfterSec; $s -gt 0; $s -= 10) { Write-Host "kill 까지 $s 초"; Start-Sleep -Seconds ([math]::Min(10, $s)) }
Write-Section 'api-2(p1 소유 노드) docker kill'
$t0 = (Get-Date).ToUniversalTime()
& docker kill chaos-api-2 | Out-Null
$t0Iso = $t0.ToString('o')
Write-Host "T0=$t0Iso killed=chaos-api-2"
$demo['killAt'] = $t0Iso
$moved = Wait-For { (Get-PartitionOwner -Partitions (Get-NodePartitions) -Resource 'matching-p1') -eq 'chaos-api-1' } -TimeoutSec 90 -PollSec 0.5
$demo['p1MovedAt'] = $(if ($moved) { $moved.ToString('o') } else { $null })
Start-Sleep -Seconds 15
Show-Partitions '두 노드의 파티션 표(kill 뒤)' | Out-Null

Write-Section 'p1 RTO, p0 ACK 간격, 권위 run'
Invoke-DemoPy @('harness/scale/s7_axes.py', 'partition-rto', '--resource', 'matching-p1', '--old-epoch', "$p1EpochBefore", '--inject-at', $t0Iso,
    '--out', (Get-Rel (Join-Path $EvidenceDir 'rto_p1.json'))) (Join-Path $EvidenceDir 'rto_p1.log') | Out-Null
Invoke-DemoPy @('harness/scale/s7_axes.py', 'ack-interval', '--survivor-markets', (@($split['matching-p0']) -join ','),
    '--affected-markets', (@($split['matching-p1']) -join ','), '--inject-at', $t0Iso, '--baseline-from', [string]$pre['at'],
    '--out', (Get-Rel (Join-Path $EvidenceDir 'ack_p0.json'))) `
    (Join-Path $EvidenceDir 'ack_p0.log') | Out-Null
$rto = Get-Content (Join-Path $EvidenceDir 'rto_p1.json') -Raw -Encoding utf8 | ConvertFrom-Json
$ack = Get-Content (Join-Path $EvidenceDir 'ack_p0.json') -Raw -Encoding utf8 | ConvertFrom-Json
Stop-SurvivorLoad -Name 'pml-s7-k6-demo' -LogPath (Join-Path ([System.IO.Path]::GetTempPath()) 'pml-s7-k6-demo.log')
$run = Invoke-InvariantRun -Port $Global:CHAOS.Api1Port -RunId "DEMO-post-$(Get-Date -Format 'HHmmss')" -Window 'post'
$demo['p1Rto'] = $rto.facts.rto_s
$demo['p0Ack'] = $ack.verdict
$demo['postRun'] = [ordered]@{ verdict = $run.verdict; gatedClean = (Test-ExperimentIntegrity $run); gatedViolations = (Get-GatedViolationSum $run) }
Write-Host ("p1 RTO          : {0} s" -f $rto.facts.rto_s)
Write-Host ("p0 ACK 간격     : {0}" -f $ack.verdict)
Write-Host ("권위 run        : {0} (게이팅 위반 {1})" -f $run.verdict, (Get-GatedViolationSum $run))
$demo['finished'] = (Now-Iso)
[System.IO.File]::WriteAllText((Join-Path $EvidenceDir 'demo.json'), ($demo | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))

if ($Down) {
    $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
    Write-Host "스택을 내렸다(docker compose -p $($Global:CHAOS.Project) ... down -v, rc=$code)"
} else {
    Write-Host "스택은 녹화를 위해 남겼다. 내릴 때: docker compose -p $($Global:CHAOS.Project) -f harness/docker-compose.chaos.yml -f harness/docker-compose.relay.yml -f harness/docker-compose.p3.yml --env-file harness/.env.chaos --profile obs down -v"
}
exit 0
