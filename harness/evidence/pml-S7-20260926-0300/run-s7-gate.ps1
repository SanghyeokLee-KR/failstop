# ADR 0034 S7 관문 폐기용 스택 실험 Q1~Q16 실행기(마켓분할_리더_S7_설계 5-1~5-3). 코드 단계 초안 run-gate.ps1 에 단계 기록을 더했다.
#   Prep  : 셸 변수를 고정하고 up.ps1 -P3 -Obs 로 이미지를 한 번 굽는다. 참조 태그 s7ref 를 붙이고 images.json 을 남긴 뒤 스택을
#           -p exchange-pml 로 내린다.
#   Q*    : harness/scripts/s7-p3-gate.ps1 -Experiment <Q> -EvidenceDir <이 폴더>\<Q>[-Tag]
#   Demo  : harness/scripts/s7-p3-demo.ps1 -NoBuild -Down -EvidenceDir <이 폴더>\Demo[-Tag]
#   Final : -p exchange-pml down -v --remove-orphans, 남은 chaos-* 컨테이너와 exchange-pml 볼륨, 보존 볼륨을 확인한다.
# 단계마다 앞뒤로 보존 볼륨 생성 시각(volumes.txt, 바뀌면 멈춘다), 호스트 부하(host-<단계>.json), 주행 동안 5초 간격 CPU(cpu-<단계>.csv)를
# 남긴다. 결과 폴더는 -Tag 를 주면 <단계>-<Tag> 다(다시 잰 회차).
# 사용: pwsh -NoProfile -File run-s7-gate.ps1 -Phase Prep|Q1|...|Q16|Demo|Final [-Tag r2] [-GateArgs '-Q1Rounds','200']
param(
    [Parameter(Mandatory)][ValidateSet('Prep', 'Q1', 'Q2', 'Q3a', 'Q3b', 'Q4', 'Q5', 'Q6', 'Q7', 'Q8', 'Q8c', 'Q9', 'Q10', 'Q11', 'Q12', 'Q13',
        'Q14', 'Q15', 'Q16', 'Demo', 'Final')][string] $Phase,
    [string] $Tag = '',
    [string[]] $GateArgs = @()
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONIOENCODING = 'utf-8'
$image = 'exchange-chaos-app:pml'
$env:CHAOS_PROJECT = 'exchange-pml'
$env:COMPOSE_PROJECT_NAME = 'exchange-pml'
$env:CHAOS_APP_IMAGE = $image
$env:CHAOS_SPRING_PROFILES = 'dev,chaos-monkey,prod-timeouts'
Remove-Item Env:CHAOS_LB_CONF -ErrorAction SilentlyContinue
Remove-Item Env:CHAOS_COMPOSE_OVERLAYS -ErrorAction SilentlyContinue
$here = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $here '..\..\..')).Path
if ($repo -ne 'C:\dev\exchange-pml') { throw "작업 트리가 C:\dev\exchange-pml 이 아니다: $repo" }
$env:CHAOS_EVIDENCE_DIR = Join-Path $here 'chaos-runs'
. (Join-Path $repo 'harness\scripts\lib.ps1')
if ($Global:CHAOS.Project -ne 'exchange-pml') { throw "프로젝트 고정 실패: $($Global:CHAOS.Project)" }
$resolved = (Invoke-Compose config --format json | ConvertFrom-Json).name
if ($resolved -ne 'exchange-pml') { throw "compose 프로젝트 해석이 exchange-pml 이 아니다: $resolved" }

# pwsh -File 로 부르면 '-GateArgs a,b' 가 문자열 하나로 온다. 쉼표로 나눈다.
$GateArgs = @($GateArgs | ForEach-Object { $_.Split(',') } | Where-Object { $_ })
$log = Join-Path $here 'commands.log'
$name = $(if ($Tag) { "$Phase-$Tag" } else { $Phase })

function Write-Log {
    param([string] $Text)
    $line = '{0} [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'), $name, $Text
    Add-Content -Path $log -Value $line -Encoding utf8
    Write-Host $line
}
function Save-Text { param([string] $Path, [string] $Text) [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false)) }

# 보존 볼륨 생성 시각. 첫 기록과 다르면 멈춘다. 읽기만 한다.
function Test-PreservedVolumes {
    param([string] $When)
    $names = @('exchange-chaos_chaos-pgdata') + @(& docker volume ls -q --filter 'name=failstop-keep-' 2>$null)
    $row = [ordered]@{}
    foreach ($n in $names) { $row[$n] = (& docker volume inspect $n --format '{{.CreatedAt}}' 2>$null | Out-String).Trim() }
    $text = ($row.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' '
    Add-Content -Path (Join-Path $here 'volumes.txt') -Value ('{0} {1} {2} {3}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'), $name, $When, $text) -Encoding utf8
    $first = Join-Path $here 'volumes-first.txt'
    if (-not (Test-Path $first)) { Save-Text $first (($row.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join "`n") }
    $ref = [ordered]@{}
    foreach ($l in (Get-Content $first -Encoding utf8)) { $i = $l.IndexOf('='); if ($i -gt 0) { $ref[$l.Substring(0, $i)] = $l.Substring($i + 1) } }
    foreach ($k in $ref.Keys) {
        if (-not $ref[$k] -or $row[$k] -ne $ref[$k]) { throw "보존 볼륨 $k 의 생성 시각이 다르다(처음 $($ref[$k]), 지금 $($row[$k]))" }
    }
    Write-Log "VOLUMES $When $text"
}

# 호스트 부하. 5초 동안 전체 CPU 표본과 다른 프로젝트 컨테이너 수.
function Get-HostLoad {
    $cpu = @()
    try { $cpu = @((Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 5).CounterSamples | ForEach-Object { [math]::Round($_.CookedValue, 1) }) } catch { $cpu = @() }
    $all = @(& docker ps --format '{{.Names}}|{{.Label "com.docker.compose.project"}}' | Where-Object { $_ })
    $others = @($all | Where-Object { $_.Split('|')[1] -ne 'exchange-pml' -and $_ -notlike 'pml-s7-*' })
    return [ordered]@{ at = (Get-Date).ToUniversalTime().ToString('o'); cpuTotalPct = $cpu
        cpuAvg = $(if ($cpu.Count) { [math]::Round(($cpu | Measure-Object -Average).Average, 1) } else { $null })
        logicalCores = [Environment]::ProcessorCount; otherContainers = $others.Count; others = $others }
}

function Start-CpuSampler {
    $csv = Join-Path $here "cpu-$name.csv"
    Remove-Item $csv -Force -ErrorAction SilentlyContinue
    return Start-Process -FilePath 'typeperf' -ArgumentList @('"\Processor(_Total)\% Processor Time"', '-si', '5', '-o', "`"$csv`"", '-f', 'CSV', '-y') `
        -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path ([System.IO.Path]::GetTempPath()) "pml-s7-typeperf-$name.out")
}

function Invoke-Runner {
    param([string] $Script, [string[]] $ArgList)
    $runnerLog = Join-Path $here "$name-runner.log"
    Write-Log ("RUN pwsh -NoProfile -File {0} {1}" -f $Script, ($ArgList -join ' '))
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    Push-Location $repo
    try { & pwsh -NoProfile -File $Script @ArgList *> $runnerLog; $rc = $LASTEXITCODE }
    finally { Pop-Location; $ErrorActionPreference = $eap }
    Write-Log "RC $rc"
    return $rc
}

function Invoke-Experiment {
    param([scriptblock] $Body)
    Test-PreservedVolumes 'before'
    $hostBefore = Get-HostLoad
    $existing = @(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}')
    if ($existing.Count -gt 0) { throw ('chaos- 컨테이너가 이미 있다: {0}' -f ($existing -join ', ')) }
    $cpuProc = Start-CpuSampler
    try { $rc = & $Body }
    finally {
        if ($cpuProc -and -not $cpuProc.HasExited) { Stop-Process -Id $cpuProc.Id -Force -ErrorAction SilentlyContinue }
    }
    $hostAfter = Get-HostLoad
    Save-Text (Join-Path $here "host-$name.json") ([ordered]@{ before = $hostBefore; after = $hostAfter } | ConvertTo-Json -Depth 5)
    Write-Log ("HOST before cpuAvg={0} others={1} after cpuAvg={2} others={3}" -f $hostBefore.cpuAvg, $hostBefore.otherContainers, $hostAfter.cpuAvg, $hostAfter.otherContainers)
    $left = @(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}')
    Write-Log ("LEFT chaos- containers: {0}" -f $left.Count)
    Test-PreservedVolumes 'after'
    return $rc
}

$dir = Join-Path $here $name
switch -Regex ($Phase) {
    '^Prep$' {
        Test-PreservedVolumes 'start'
        Save-Text (Join-Path $here "docker-ps-before-$name.txt") ((& docker ps -a --format '{{.Names}}|{{.Image}}|{{.Status}}') -join "`n")
        Save-Text (Join-Path $here "host-$name.json") ((Get-HostLoad) | ConvertTo-Json -Depth 5)
        $existing = @(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}')
        if ($existing.Count -gt 0) { throw ('chaos- 컨테이너가 이미 있다: {0}' -f ($existing -join ', ')) }
        $before = (& docker image inspect $image --format '{{.Id}}' 2>$null | Out-String).Trim()
        Write-Log "IMAGE before $before"
        $rc = Invoke-Runner (Join-Path $repo 'harness\scripts\up.ps1') @('-P3', '-Obs')
        $after = (& docker image inspect $image --format '{{.Id}}' 2>$null | Out-String).Trim()
        Write-Log "IMAGE after $after (up rc=$rc)"
        Set-P3Overlay
        $downRc = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
        Remove-Item Env:CHAOS_COMPOSE_OVERLAYS -ErrorAction SilentlyContinue
        Write-Log "DOWN rc=$downRc"
        if ($rc -ne 0) { throw "up.ps1 실패(rc=$rc)" }
        $refTag = $(if ($Tag) { "exchange-chaos-app:s7ref-$Tag" } else { 'exchange-chaos-app:s7ref' })
        & docker tag $image $refTag
        $jar = Join-Path $repo 'harness\app.jar'
        $images = [ordered]@{ app = $after; appBefore = $before; tags = @($image, $refTag)
            head = (& git -C $repo rev-parse HEAD | Out-String).Trim()
            workingTreeClean = [string]::IsNullOrWhiteSpace((& git -C $repo status --short --untracked-files=no -- server web harness/scripts harness/scale harness/load harness/nginx harness/relay harness/sql harness/docker-compose.chaos.yml harness/docker-compose.relay.yml harness/docker-compose.p3.yml | Out-String))
            jarSha256 = $(if (Test-Path $jar) { (Get-FileHash $jar -Algorithm SHA256).Hash.ToLower() } else { $null })
            jarTime = $(if (Test-Path $jar) { (Get-Item $jar).LastWriteTimeUtc.ToString('o') } else { $null })
            broker = (& docker image inspect 'rabbitmq:4.3.6-alpine' --format '{{.Id}}' 2>$null | Out-String).Trim()
            k6 = (& docker image inspect 'grafana/k6:1.4.0' --format '{{.Id}}' 2>$null | Out-String).Trim()
            localTag = (& docker image inspect 'exchange-chaos-app:local' --format '{{.Id}}' 2>$null | Out-String).Trim() }
        Save-Text (Join-Path $here $(if ($Tag) { "images-$name.json" } else { 'images.json' })) ($images | ConvertTo-Json -Depth 4)
        Write-Log "PREP done app=$after"
        Test-PreservedVolumes 'after'
    }
    '^Q' {
        $rc = Invoke-Experiment {
            Invoke-Runner (Join-Path $repo 'harness\scripts\s7-p3-gate.ps1') (@('-Experiment', $Phase, '-EvidenceDir', $dir, '-Image', $image) + $GateArgs)
        }
        $g = Join-Path $dir 'gate.json'
        $v = $(if (Test-Path $g) { (Get-Content $g -Raw -Encoding utf8 | ConvertFrom-Json).verdict } else { $null })
        Write-Log "DONE rc=$rc verdict=$v"
    }
    '^Demo$' {
        $rc = Invoke-Experiment {
            Invoke-Runner (Join-Path $repo 'harness\scripts\s7-p3-demo.ps1') (@('-NoBuild', '-Down', '-EvidenceDir', $dir) + $GateArgs)
        }
        Write-Log "DONE rc=$rc"
    }
    '^Final$' {
        Set-P3Overlay
        $downRc = Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'
        Remove-Item Env:CHAOS_COMPOSE_OVERLAYS -ErrorAction SilentlyContinue
        Write-Log "DOWN rc=$downRc"
        $left = @(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}')
        $probeLeft = @(& docker ps -a --filter 'name=^pml-' --format '{{.Names}}')
        $vols = @(& docker volume ls -q --filter 'label=com.docker.compose.project=exchange-pml')
        $nets = @(& docker network ls -q --filter 'label=com.docker.compose.project=exchange-pml')
        Save-Text (Join-Path $here 'docker-ps-after.txt') ((& docker ps -a --format '{{.Names}}|{{.Image}}|{{.Status}}') -join "`n")
        Save-Text (Join-Path $here 'volume-ls-end.txt') ((& docker volume ls --format '{{.Name}}|{{.Label "com.docker.compose.project"}}') -join "`n")
        Write-Log ("FINAL chaos-={0} pml-*={1} exchange-pml volumes={2} networks={3}" -f $left.Count, $probeLeft.Count, $vols.Count, $nets.Count)
        Write-Log ("TAGS pml={0} s7ref={1} s6ref={2} local={3}" -f `
            (& docker image inspect 'exchange-chaos-app:pml' --format '{{.Id}}' | Out-String).Trim(),
            (& docker image inspect 'exchange-chaos-app:s7ref' --format '{{.Id}}' 2>$null | Out-String).Trim(),
            (& docker image inspect 'exchange-chaos-app:s6ref' --format '{{.Id}}' | Out-String).Trim(),
            (& docker image inspect 'exchange-chaos-app:local' --format '{{.Id}}' | Out-String).Trim())
        Test-PreservedVolumes 'end'
    }
}
