# ADR 0034 S4 관문 X6 접수 경합(마켓분할_리더_S4_설계 7-2 X6, API 설계 §2-5-b). 떠 있는 폐기용 스택(P2 로 전환한 X1 스택)에서
# 변형 a~d 를 차례로 돌린다. 구동과 변형별 판정은 harness/scale/s4_intake_contention.py 가 하고, 이 스크립트는 스택 가드, 봇 부하,
# 공통 판정(LB 의 no live upstreams·502 0, 리더 강등 0, 정산 재시도 소진 0)을 맡는다. 공통 판정은 s4_partition_axes.py 를
# --lb-gate 로 부른다.
# 사용: pwsh -NoProfile -File harness/scripts/s4-intake-contention.ps1 -OutDir <증거 폴더> [-Variants a,b,c,d]
# 전제: CHAOS_PROJECT=exchange-pml, 앱 이미지 exchange-chaos-app:pml, leader_protocol 2. 부하 계정 키는 구동기가 메모리에만 둔다.
# 종료코드: 0 전 변형 PASS, 1 FAIL, 5 BLOCKED(시작 전 조건).
param(
    [Parameter(Mandatory = $true)][string] $OutDir,
    [string] $Variants = 'a,b,c,d',
    [int] $BotOps = 20
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONIOENCODING = 'utf-8'
. (Join-Path $PSScriptRoot 'lib.ps1')
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

# 폐기용 스택 가드(S4 설계 7-0). 보존 스택에서는 봇을 다시 띄우거나 잠금을 걸지 않는다.
if ($Global:CHAOS.Project -ne 'exchange-pml') { Write-Host "CHAOS_PROJECT 가 exchange-pml 이 아니다($($Global:CHAOS.Project))."; exit 5 }
$apiImage = (& docker inspect -f '{{.Config.Image}}' chaos-api-1 2>$null | Out-String).Trim()
if ($apiImage -ne 'exchange-chaos-app:pml') { Write-Host "chaos-api-1 이미지가 exchange-chaos-app:pml 이 아니다($apiImage)."; exit 5 }
$protocol = Get-Scalar 'SELECT protocol FROM leader_protocol' -Required
if ([int]$protocol -ne 2) { Write-Host "leader_protocol 이 2 가 아니다($protocol). protocol_latch.py transition --target 2 뒤에 돈다."; exit 5 }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$wanted = @($Variants.Split(',') | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ })
$results = [ordered]@{}
$exhaustedPattern = 'exchange_settlement_transient_exhausted_total(?:\{[^}]*\})?\s+([0-9.eE+-]+)'

function Save-Text {
    param([string] $Path, [string] $Text)
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false))
}

function Get-ExhaustedSum {
    $sum = 0.0
    foreach ($p in @($Global:CHAOS.Api1Port, $Global:CHAOS.Api2Port)) {
        $v = Get-AppMetric -Port $p -Pattern $exhaustedPattern
        if ($null -ne $v) { $sum += [double]$v }
    }
    return $sum
}

foreach ($v in $wanted) {
    $dir = Join-Path $OutDir "X6$v"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $row = [ordered]@{ variant = $v }
    try {
        # d 는 저트래픽 장기 잠금이라 봇 목표를 0 으로 내린다(설계 7-2 X6d).
        Set-BotOps -Ops $(if ($v -eq 'd') { 0 } else { $BotOps }) -SettleSec 10
        $from = [DateTimeOffset]::UtcNow
        $exhaustedBefore = Get-ExhaustedSum
        & python (Join-Path $repo 'harness/scale/s4_partition_axes.py') snapshot --out (Join-Path $dir 'partition_pre.json') *> (Join-Path $dir 'partition_pre.log')
        & python (Join-Path $repo 'harness/scale/s4_intake_contention.py') --variant $v --out (Join-Path $dir 'contention.json') *> (Join-Path $dir 'contention.log')
        $row['driverRc'] = $LASTEXITCODE
        & python (Join-Path $repo 'harness/scale/s4_partition_axes.py') snapshot --out (Join-Path $dir 'partition_post.json') *> (Join-Path $dir 'partition_post.log')
        # 경합이 이 실험의 목적이라 창 전체를 장애 창으로 두고, 창 밖 경합 상한 대신 LB·강등 축을 본다.
        $to = [DateTimeOffset]::UtcNow
        & python (Join-Path $repo 'harness/scale/s4_partition_axes.py') judge --window-from $from.ToString('o') `
            --fault-from $from.ToString('o') --fault-to $to.ToString('o') --settle-sec 0 --lb-gate `
            --pre (Join-Path $dir 'partition_pre.json') --post (Join-Path $dir 'partition_post.json') `
            --out (Join-Path $dir 'partition_axes.json') *> (Join-Path $dir 'partition_axes.log')
        $axes = Get-Content (Join-Path $dir 'partition_axes.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
        $driver = Get-Content (Join-Path $dir 'contention.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
        $exhaustedDelta = (Get-ExhaustedSum) - $exhaustedBefore
        $row['driver'] = [ordered]@{ ok = $driver['ok']; failed = @($driver['axes'].Keys | Where-Object { -not $driver['axes'][$_] })
            record = $driver['record']; contentionDelta = $driver['contention_delta'] }
        $row['common'] = [ordered]@{
            lbZero = $axes['axes']['lb_no_live_upstreams_and_502_0']
            leaderDemotionsZero = $axes['axes']['leader_node_demotions_0']
            settlementExhaustedDelta = $exhaustedDelta
        }
        $commonOk = [bool]$row['common'].lbZero -and [bool]$row['common'].leaderDemotionsZero -and ($exhaustedDelta -eq 0)
        $row['판정'] = $(if ($driver['ok'] -and $commonOk) { 'PASS' } else { 'FAIL' })
    }
    catch {
        $row['판정'] = 'BLOCKED'
        $row['사유'] = $_.Exception.Message
    }
    $results[$v] = $row
    Write-Host ("X6{0}: {1}" -f $v, $row['판정'])
    Save-Text (Join-Path $OutDir 'x6_summary.json') ($results | ConvertTo-Json -Depth 8)
}
Set-BotOps -Ops $BotOps -SettleSec 5
$failed = @($results.Values | Where-Object { $_['판정'] -ne 'PASS' }).Count
if ($failed -eq 0) { exit 0 }
exit 1
