# ADR 0034 S6 관문 실험 W1~W4(마켓분할_리더_S6_설계 7-2, 하네스 검증설계 §3 E13). 2026-09-25 관문 주행에서 처음 돌렸다
# (harness/evidence/pml-S6-20260925-1954/).
#   W1 중계 기본. P2, 봇 부하, 주입 없이 -W1Sec 동안 관찰한다.
#   W2 브로커 정지. docker stop chaos-broker 60초 뒤 docker start.
#   W3 브로커 멈춤. docker pause chaos-broker 40초 뒤 unpause.
#   W4 브로커 메모리 경보. rabbitmqctl set_vm_memory_high_watermark absolute 1MiB 30초 뒤 192MiB 로 되돌린다.
# 실험마다 스택을 새로 세운다. lib.ps1 의 compose 호출이 CHAOS_COMPOSE_OVERLAYS(중계 오버레이)를 겹친다. P2 로 전환한 뒤 오버레이 확인
# (chaos-broker healthy, 두 앱 컨테이너의 EXCHANGE_WS_RELAY_MODE=broker, chaos-lb 의 nginx.relay.conf 마운트)을 통과해야 봇 부하와
# k6 관찰자(pml-s6-watch-<실험>), 미러 신선도 표본기를 띄운다. 하나라도 아니면 BLOCKED 다. 주입이 끝나면 관찰자를 멈춰 마지막 상태를 받고
# harness/scale/s6_relay_axes.py judge 로 중계 축을, s3_common_axes.py judge 로 공통 판정을 남긴 뒤 down -v 한다.
#
# 안전 수칙(설계 7-0): compose 프로젝트 exchange-pml, 이미지 exchange-chaos-app:pml 에서만 돈다. 보존 볼륨(exchange-chaos_chaos-pgdata,
# failstop-keep-*)은 생성 시각만 읽는다. 시작 전에 chaos- 컨테이너가 없어야 한다. 비밀 파일(harness/.env.chaos)은 compose 에 경로로만
# 넘기고 열지 않는다.
# rabbitmqctl 은 rabbitmq 사용자로 부른다(-u rabbitmq). root 로 부르면 Erlang 쿠키가 맞지 않아 실패한다(서버 시험 RelayBroker 확인).
# 사용: pwsh -NoProfile -File harness/scripts/s6-relay-outage.ps1 -Image exchange-chaos-app:pml -Experiment W2 -EvidenceDir <폴더>\W2
# 종료코드: 0 PASS, 1 FAIL 또는 NOT RUN, 5 BLOCKED(시작 전 조건).
param(
    [Parameter(Mandatory)][string] $Image,
    [Parameter(Mandatory)][ValidateSet('W1', 'W2', 'W3', 'W4')][string] $Experiment,
    [Parameter(Mandatory)][string] $EvidenceDir,
    [int] $BotOps = 300,
    [int] $PreSec = 60,
    [int] $PostSec = 90,
    [int] $W1Sec = 600,
    [string] $LocalLogDir = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONIOENCODING = 'utf-8'
. (Join-Path $PSScriptRoot 'lib.ps1')
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$faultSec = @{ W1 = 0; W2 = 60; W3 = 40; W4 = 30 }[$Experiment]
if (-not $LocalLogDir) { $LocalLogDir = Join-Path ([System.IO.Path]::GetTempPath()) "pml-s6-$Experiment" }
$runLog = Join-Path $EvidenceDir 'commands.log'

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

function Get-RelPath {
    param([string] $Path)
    return [System.IO.Path]::GetRelativePath($repo, $Path)
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

# 보존 볼륨의 생성 시각. 읽기만 한다. 주행 앞뒤 값이 같아야 보존 볼륨을 건드리지 않은 것이다.
function Get-PreservedVolumes {
    $names = @('exchange-chaos_chaos-pgdata') + @(& docker volume ls -q --filter 'name=failstop-keep-' 2>$null)
    $out = [ordered]@{}
    foreach ($n in $names) {
        $out[$n] = (& docker volume inspect $n --format '{{.CreatedAt}}' 2>$null | Out-String).Trim()
    }
    return $out
}

# 모든 파티션이 ACCEPTING 인 노드. P2 전환 뒤 리더를 판정 인자로 넘긴다.
function Get-LeaderNode {
    $found = @()
    foreach ($pair in @(@('chaos-api-1', $Global:CHAOS.Api1Port), @('chaos-api-2', $Global:CHAOS.Api2Port))) {
        try {
            $parts = Invoke-RestMethod -Uri "http://localhost:$($pair[1])/actuator/partitions" -TimeoutSec 3
            $rows = @($parts.partitions)
            if ($rows.Count -gt 0 -and @($rows | Where-Object { $_.state -ne 'ACCEPTING' }).Count -eq 0) { $found += $pair[0] }
        } catch { }
    }
    if ($found.Count -eq 1) { return $found[0] }
    return $null
}

function Invoke-Fault {
    param([string] $Phase)
    if ($Experiment -eq 'W2') {
        $cmd = $(if ($Phase -eq 'inject') { @('stop', 'chaos-broker') } else { @('start', 'chaos-broker') })
    } elseif ($Experiment -eq 'W3') {
        $cmd = $(if ($Phase -eq 'inject') { @('pause', 'chaos-broker') } else { @('unpause', 'chaos-broker') })
    } else {
        $level = $(if ($Phase -eq 'inject') { '1MiB' } else { '192MiB' })
        $cmd = @('exec', '-u', 'rabbitmq', 'chaos-broker', 'rabbitmqctl', '-q', 'set_vm_memory_high_watermark', 'absolute', $level)
    }
    Write-RunLog ('FAULT {0} docker {1}' -f $Phase, ($cmd -join ' '))
    & docker @cmd *> $null
    $rc = $LASTEXITCODE
    Write-RunLog "FAULT $Phase rc=$rc"
    return $rc
}

if ($PSVersionTable.PSVersion.Major -lt 7) { Write-Host 'pwsh 7 로 실행한다.'; exit 5 }
if ($Global:CHAOS.Project -ne 'exchange-pml' -or $Image -ne 'exchange-chaos-app:pml') {
    Write-Host "CHAOS_PROJECT=exchange-pml, -Image exchange-chaos-app:pml 에서만 돈다(지금 $($Global:CHAOS.Project), $Image)."; exit 5
}
if (Test-Path (Join-Path $EvidenceDir 'summary.json')) { Write-Host '이미 summary.json 이 있다. 증거를 덮지 않는다.'; exit 5 }
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
Write-RunLog "RUN s6-relay-outage Experiment=$Experiment Image=$Image BotOps=$BotOps Project=$($Global:CHAOS.Project)"
if (-not (Test-Path $Global:CHAOS.EnvFile)) { Write-RunLog 'BLOCKED harness/.env.chaos 가 없다'; exit 5 }
& docker info *> $null
if ($LASTEXITCODE -ne 0) { Write-RunLog 'BLOCKED 도커 데몬 미응답'; exit 5 }
$imageId = (& docker image inspect $Image --format '{{.Id}}' 2>$null | Out-String).Trim()
if (-not $imageId) { Write-RunLog "BLOCKED 이미지가 없다: $Image"; exit 5 }
$existing = @(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}')
if ($existing.Count -gt 0) { Write-RunLog ('BLOCKED chaos- 컨테이너가 이미 있다({0})' -f ($existing -join ', ')); exit 5 }
try { Set-RelayOverlay } catch { Write-RunLog "BLOCKED $($_.Exception.Message)"; exit 5 }
Write-RunLog "OVERLAYS $env:CHAOS_COMPOSE_OVERLAYS"
$env:CHAOS_APP_IMAGE = $Image

# 조기 return 이 finally(관찰자 정지, down -v, 요약)를 거쳐 이 함수에서 나오게 한다. 스크립트 수준 try 안의 return 은 종료코드 0 으로
# 스크립트를 끝내 BLOCKED 가 통과로 보인다. 종료코드는 스크립트 범위 변수로 넘긴다.
$script:exitCode = 1
function Invoke-OutageRun {
    $row = [ordered]@{ experiment = $Experiment; image = $Image; imageId = $imageId; botOps = $BotOps; faultSec = $faultSec
        volumesBefore = (Get-PreservedVolumes) }
    $watch = $null; $freshness = $null; $balanceSampler = $null
    $stopFresh = Join-Path $EvidenceDir 'stop-freshness'
    $stopBalance = Join-Path $EvidenceDir 'stop-balance'
    $script:exitCode = 1
    try {
        $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
        $stackFrom = [DateTimeOffset]::UtcNow
        $code = Invoke-ComposeQuiet '--profile obs up -d --wait'
        if ($code -ne 0) { $row['판정'] = 'BLOCKED'; $row['사유'] = "compose up 종료코드 $code"; $script:exitCode = 5; return }
        $protocolRc = Invoke-Logged 'python' @('harness/scale/protocol_latch.py', 'transition', '--target', '2', '--out',
            (Get-RelPath (Join-Path $EvidenceDir 'protocol.json'))) (Join-Path $EvidenceDir 'protocol.log')
        if ($protocolRc -ne 0) { $row['판정'] = 'BLOCKED'; $row['사유'] = "P2 전환 실패(rc=$protocolRc)"; $script:exitCode = 5; return }
        $preflight = Test-RelayOverlay
        Save-Text (Join-Path $EvidenceDir 'relay-preflight.json') ($preflight | ConvertTo-Json -Depth 6)
        $row['relayPreflight'] = $preflight
        if (-not $preflight['ok']) { $row['판정'] = 'BLOCKED'; $row['사유'] = '중계 오버레이 확인 실패. relay-preflight.json 을 본다'; $script:exitCode = 5; return }
        $images = [ordered]@{ app = $imageId; head = (& git -C $repo rev-parse HEAD | Out-String).Trim()
            broker = (& docker inspect -f '{{.Image}}' chaos-broker 2>$null | Out-String).Trim()
            jarSha256 = $(if (Test-Path (Join-Path $repo 'harness\app.jar')) { (Get-FileHash (Join-Path $repo 'harness\app.jar') -Algorithm SHA256).Hash.ToLower() } else { $null }) }
        Save-Text (Join-Path $EvidenceDir 'images.json') ($images | ConvertTo-Json -Depth 4)
        $leader = Get-LeaderNode
        if (-not $leader) { $row['판정'] = 'BLOCKED'; $row['사유'] = 'P2 전환 뒤 리더 노드를 가리지 못했다'; $script:exitCode = 5; return }
        $follower = $(if ($leader -eq 'chaos-api-1') { 'chaos-api-2' } else { 'chaos-api-1' })
        $row['leader'] = $leader; $row['follower'] = $follower

        Set-BotOps $BotOps
        $total = $(if ($Experiment -eq 'W1') { $W1Sec } else { $PreSec + $faultSec + $PostSec }) + 60
        $watch = Start-RelayWatch -Name "pml-s6-watch-$Experiment" -OutDir $EvidenceDir -DurationSec $total
        $row['relayWatch'] = $watch
        if ($watch.rc -ne 0) { $row['판정'] = 'BLOCKED'; $row['사유'] = "관찰자 기동 실패(rc=$($watch.rc))"; $script:exitCode = 5; return }
        Remove-Item $stopFresh, $stopBalance -Force -ErrorAction SilentlyContinue
        $freshness = Start-Process -FilePath 'python' -WorkingDirectory $repo -NoNewWindow -PassThru `
            -ArgumentList @('harness/scale/s6_relay_axes.py', 'freshness', '--out', (Get-RelPath (Join-Path $EvidenceDir 'freshness.jsonl')),
                '--stop-file', (Get-RelPath $stopFresh)) `
            -RedirectStandardOutput (Join-Path $EvidenceDir 'freshness.out') -RedirectStandardError (Join-Path $EvidenceDir 'freshness.err')
        $balanceSampler = Start-Process -FilePath 'python' -WorkingDirectory $repo -NoNewWindow -PassThru `
            -ArgumentList @('harness/scale/s3_common_axes.py', 'sample', '--out', (Get-RelPath (Join-Path $EvidenceDir 'balance_samples.jsonl')),
                '--stop-file', (Get-RelPath $stopBalance), '--interval', '2') `
            -RedirectStandardOutput (Join-Path $EvidenceDir 'balance_sampler.out') -RedirectStandardError (Join-Path $EvidenceDir 'balance_sampler.err')

        $windowFrom = [DateTimeOffset]::UtcNow
        if ($Experiment -eq 'W1') {
            Start-Sleep -Seconds $W1Sec
            $faultFrom = $null; $faultTo = $null
        } else {
            Start-Sleep -Seconds $PreSec
            $faultFrom = [DateTimeOffset]::UtcNow
            if ((Invoke-Fault 'inject') -ne 0) { $row['판정'] = 'BLOCKED'; $row['사유'] = '주입 명령 실패'; $script:exitCode = 5; Invoke-Fault 'restore' | Out-Null; return }
            Start-Sleep -Seconds $faultSec
            # 되돌리기 명령은 부른 뒤 몇 초 걸려 돌아온다. 장애가 풀린 시각은 그 사이다. 부른 시각을 따로 남긴다(판정 --restore-at).
            $restoreAt = [DateTimeOffset]::UtcNow
            $restoreRc = Invoke-Fault 'restore'
            $faultTo = [DateTimeOffset]::UtcNow
            $row['restoreRc'] = $restoreRc
            Start-Sleep -Seconds $PostSec
        }
        $row['window'] = [ordered]@{ stackFrom = $stackFrom.ToString('o'); from = $windowFrom.ToString('o')
            faultFrom = $(if ($faultFrom) { $faultFrom.ToString('o') } else { $null }); faultTo = $(if ($faultTo) { $faultTo.ToString('o') } else { $null })
            restoreAt = $(if ($faultFrom) { $restoreAt.ToString('o') } else { $null })
            to = [DateTimeOffset]::UtcNow.ToString('o') }

        # 관찰자와 표본기를 멈춰 마지막 상태를 받은 뒤 판정한다.
        New-Item -ItemType File -Force -Path $stopFresh | Out-Null
        if (-not $freshness.WaitForExit(30000)) { Stop-Process -Id $freshness.Id -Force }
        Stop-RelayWatch -Name $watch.name -LogPath (Join-Path $EvidenceDir 'watch.log')
        $watch = $null
        Invoke-Logged 'python' @('harness/scale/s6_relay_axes.py', 'watch', '--log', (Get-RelPath (Join-Path $EvidenceDir 'watch.log')),
            '--out', (Get-RelPath (Join-Path $EvidenceDir 'watch.json'))) (Join-Path $EvidenceDir 'watch_merge.log') | Out-Null
        $judgeArgs = @('harness/scale/s6_relay_axes.py', 'judge', '--experiment', $Experiment, '--evidence', (Get-RelPath $EvidenceDir),
            '--window-from', $windowFrom.ToString('o'), '--leader', $leader, '--follower', $follower,
            '--out', (Get-RelPath (Join-Path $EvidenceDir 'relay_axes.json')))
        if ($faultFrom) { $judgeArgs += @('--fault-from', $faultFrom.ToString('o'), '--fault-to', $faultTo.ToString('o'), '--restore-at', $restoreAt.ToString('o')) }
        $relayRc = Invoke-Logged 'python' $judgeArgs (Join-Path $EvidenceDir 'relay_axes.log')

        # 공통 판정(S3 설계 7-3, S4b 까지 그대로). 봇을 멈추고 드레인한 뒤 판정한다. 장애 창이 없으면(W1) 끝 시각을 창으로 넘긴다.
        New-Item -ItemType File -Force -Path $stopBalance | Out-Null
        if (-not $balanceSampler.WaitForExit(30000)) { Stop-Process -Id $balanceSampler.Id -Force }
        $cf = $(if ($faultFrom) { $faultFrom } else { [DateTimeOffset]::UtcNow })
        $ct = $(if ($faultTo) { $faultTo } else { $cf })
        $commonRc = Invoke-Logged 'python' @('harness/scale/s3_common_axes.py', 'judge', '--window-from', $stackFrom.ToString('o'),
            '--fault-from', $cf.ToString('o'), '--fault-to', $ct.ToString('o'),
            '--samples', (Get-RelPath (Join-Path $EvidenceDir 'balance_samples.jsonl')),
            '--out', (Get-RelPath (Join-Path $EvidenceDir 's3_common.json'))) (Join-Path $EvidenceDir 's3_common.log')

        New-Item -ItemType Directory -Force -Path $LocalLogDir | Out-Null
        $logs = @()
        foreach ($c in 'chaos-api-1', 'chaos-api-2', 'chaos-bots', 'chaos-lb', 'chaos-broker') {
            $path = Join-Path $LocalLogDir "$Experiment-$c.log"
            & cmd /c "docker logs $c > `"$path`" 2>&1"
            if (Test-Path $path) {
                $logs += [ordered]@{ container = $c; path = $path; bytes = (Get-Item $path).Length
                    sha256 = (Get-FileHash $path -Algorithm SHA256).Hash.ToLower() }
            }
        }
        Save-Text (Join-Path $EvidenceDir 'container_logs.json') (ConvertTo-Json @($logs) -Depth 4)

        $relay = $null; $common = $null
        if (Test-Path (Join-Path $EvidenceDir 'relay_axes.json')) { $relay = Get-Content (Join-Path $EvidenceDir 'relay_axes.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable }
        if (Test-Path (Join-Path $EvidenceDir 's3_common.json')) { $common = Get-Content (Join-Path $EvidenceDir 's3_common.json') -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable }
        $row['relayAxes'] = [ordered]@{ rc = $relayRc; ok = $(if ($relay) { $relay['ok'] } else { $false })
            failed = $(if ($relay -and $relay['axes']) { @($relay['axes'].Keys | Where-Object { -not $relay['axes'][$_] }) } else { @('판정 파일 없음') }) }
        $row['s3Common'] = [ordered]@{ rc = $commonRc; ok = $(if ($common) { $common['ok'] } else { $false })
            failed = $(if ($common -and $common['axes']) { @($common['axes'].Keys | Where-Object { -not $common['axes'][$_] }) } else { @('판정 파일 없음') }) }
        $row['판정'] = $(if (-not $relay -or -not $common) { 'NOT RUN(판정 파일 없음)' }
            elseif (-not $row['relayAxes'].ok) { 'FAIL(중계 축)' }
            elseif (-not $row['s3Common'].ok) { 'FAIL(공통 판정)' }
            else { 'PASS' })
        $script:exitCode = $(if ($row['판정'] -eq 'PASS') { 0 } else { 1 })
    }
    catch {
        $row['판정'] = 'BLOCKED'
        $row['사유'] = $_.Exception.Message
        $script:exitCode = 5
    }
    finally {
        if ($freshness -and -not $freshness.HasExited) {
            New-Item -ItemType File -Force -Path $stopFresh | Out-Null
            if (-not $freshness.WaitForExit(30000)) { Stop-Process -Id $freshness.Id -Force }
        }
        if ($balanceSampler -and -not $balanceSampler.HasExited) {
            New-Item -ItemType File -Force -Path $stopBalance | Out-Null
            if (-not $balanceSampler.WaitForExit(30000)) { Stop-Process -Id $balanceSampler.Id -Force }
        }
        # 관찰자가 실험 네트워크에 붙어 있으면 down 이 네트워크를 지우지 못한다
        if ($watch) { Stop-RelayWatch -Name $watch.name -LogPath (Join-Path $EvidenceDir 'watch.log') }
        $code = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
        Write-RunLog "END $Experiment down rc=$code"
        $row['volumesAfter'] = Get-PreservedVolumes
        $row['volumesUnchanged'] = (($row['volumesBefore'] | ConvertTo-Json -Compress) -eq ($row['volumesAfter'] | ConvertTo-Json -Compress))
        Save-Text (Join-Path $EvidenceDir 'summary.json') ($row | ConvertTo-Json -Depth 10)
        Write-RunLog "DONE $Experiment $($row['판정'])"
    }
}

Invoke-OutageRun
exit $script:exitCode
