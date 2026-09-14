# ADR 0022 6단계 판정 주행. 6단계 이미지 한 벌로 같은 날 같은 카오스 스택에서 B4(래치 없음) 다음 B6(트래픽 전 래치)를
# 요청 600/s 로 돌리고 harness/scale/stage6_judge.py 로 판정한다. 증거는 harness/evidence/adr0022-stage6-b4b6-<YYYYMMDD>/ 다.
# 사용: pwsh -NoProfile -File harness/scripts/adr0022-stage6-b4b6.ps1 -Ref <6단계 브랜치 또는 커밋> [-SkipBuild]
# 종료코드: 판정기 값(0 채번 분할 효과·평평, 1 래치 무효, 2 NOT RUN, 4 연결 고갈), 5 BLOCKED(준비·빌드·주행 실패).
# 래치는 되돌릴 수 없다. B6 프로브가 볼륨을 파기하고 새로 세운 chaos-pg 에만 걸고, 끝의 down -v 가 그 DB 를 버린다.
# 비밀 파일(harness/.env.chaos, harness/load/secrets/k6_keys.json)은 경로로만 넘기고 읽지 않는다.
param(
    [string] $Ref = '',
    [string] $WorktreeRoot = 'C:\dev',
    [string] $EvidenceDir = '',
    [int] $AppendPool = 14,
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
# psql·python 의 UTF-8 출력을 코드 페이지로 잘못 풀면 한글 뒤 ASCII 까지 먹힌다.
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONIOENCODING = 'utf-8'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $EvidenceDir) { $EvidenceDir = Join-Path $repo ("harness\evidence\adr0022-stage6-b4b6-{0}" -f (Get-Date -Format 'yyyyMMdd')) }
$commandLog = Join-Path $EvidenceDir 'commands.log'
$downArgs = @('compose', '-f', 'harness/docker-compose.chaos.yml', '-f', 'harness/docker-compose.scale.yml',
    '--env-file', 'harness/.env.chaos', '--profile', 'obs', 'down', '-v', '--remove-orphans')

if ($PSVersionTable.PSVersion.Major -lt 7) { Write-Host 'pwsh 7 로 실행한다(CLAUDE.md 30절).'; exit 5 }
if (-not $Ref) { Write-Host '-Ref 가 필요하다. 6단계 구현 브랜치 또는 커밋이다.'; exit 5 }
foreach ($f in 'B4.json', 'B6.json', 'verdict.json') {
    if (Test-Path (Join-Path $EvidenceDir $f)) { Write-Host "이미 $f 가 있다. 증거를 덮지 않는다. -EvidenceDir 로 다른 경로를 준다."; exit 5 }
}
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null

function Write-CommandLog {
    param([string] $Text)
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'), $Text
    Add-Content -Path $commandLog -Value $line -Encoding utf8
    Write-Host $line
}

function Stop-Blocked {
    param([string] $Reason)
    Write-CommandLog "BLOCKED $Reason"
    exit 5
}

# 네이티브 명령 하나를 돌린다. 출력은 단계 로그로, 명령줄과 종료코드는 명령 기록으로 간다.
function Invoke-Logged {
    param([string] $Name, [string] $Exe, [string[]] $ArgList, [string] $WorkDir = $repo)
    $log = Join-Path $EvidenceDir "$Name.log"
    Write-CommandLog ('START {0} | cwd={1} | {2} {3}' -f $Name, $WorkDir, $Exe, ($ArgList -join ' '))
    Push-Location $WorkDir
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Exe @ArgList *> $log; $rc = $LASTEXITCODE }
    finally { $ErrorActionPreference = $eap; Pop-Location }
    Write-CommandLog ('END {0} rc={1}' -f $Name, $rc)
    return $rc
}

# 이미지는 작업 트리에서 그 커밋의 Dockerfile.app 으로 굽고 revision 라벨에 커밋을 박는다. 두 칸이 이 한 벌을 쓴다.
function Build-Stage6Image {
    $sha = (& git -C $repo rev-parse --verify --quiet "$Ref^{commit}" | Out-String).Trim()
    if ($sha -notmatch '^[0-9a-f]{40}$') { Stop-Blocked "ref 를 커밋으로 해석하지 못했다: $Ref" }
    $name = 'adr0022-stage6-{0}' -f $sha.Substring(0, 7)
    $tag = "exchange-chaos-app:$name"
    $wt = Join-Path $WorktreeRoot "exchange-$name"
    $jarSha = $null
    if ($SkipBuild) {
        $labels = (& docker image inspect $tag --format '{{json .Config.Labels}}' 2>$null | Out-String).Trim()
        if (-not $labels) { Stop-Blocked "-SkipBuild 인데 이미지가 없다: $tag" }
        $rev = ($labels | ConvertFrom-Json).'org.opencontainers.image.revision'
        if ($rev -ne $sha) { Stop-Blocked "이미지 $tag 의 revision 라벨($rev)이 $Ref($sha)와 다르다" }
    } else {
        # 서버 빌드는 ASCII 경로에서 한다. 한글 경로에서 가짜 ClassNotFound 가 난다(CLAUDE.md 30절).
        if ($wt -notmatch '^[\x20-\x7E]+$') { Stop-Blocked "작업 트리 경로가 ASCII 가 아니다: $wt" }
        if (-not (Test-Path $wt)) {
            if ((Invoke-Logged 'worktree' 'git' @('-C', $repo, 'worktree', 'add', '--detach', $wt, $sha)) -ne 0) { Stop-Blocked "작업 트리 생성 실패: $wt" }
        }
        $head = (& git -C $wt rev-parse HEAD | Out-String).Trim()
        if ($head -ne $sha) { Stop-Blocked "작업 트리 $wt 의 HEAD($head)가 $sha 가 아니다" }
        $dirty = @(& git -C $wt status --porcelain -- server harness).Count
        if ($dirty -ne 0) { Stop-Blocked "작업 트리 $wt 에 커밋되지 않은 server·harness 경로가 $dirty 개 있다" }
        $server = Join-Path $wt 'server'
        if ((Invoke-Logged 'bootjar' (Join-Path $server 'gradlew.bat') @(':app:bootJar', '-q') $server) -ne 0) { Stop-Blocked 'bootJar 실패' }
        $jar = Get-ChildItem (Join-Path $server 'app\build\libs') -Filter '*.jar' | Where-Object { $_.Name -notmatch 'plain' } | Select-Object -First 1
        if (-not $jar) { Stop-Blocked 'bootJar 산출물 없음' }
        Copy-Item $jar.FullName (Join-Path $wt 'harness\app.jar') -Force
        $jarSha = (Get-FileHash $jar.FullName -Algorithm SHA256).Hash.ToLower()
        $buildArgs = @('build', '-f', 'Dockerfile.app', '-t', $tag,
            '--label', "org.opencontainers.image.revision=$sha", '--label', "org.opencontainers.image.ref.name=$Ref", '.')
        if ((Invoke-Logged 'image' 'docker' $buildArgs (Join-Path $wt 'harness')) -ne 0) { Stop-Blocked '이미지 빌드 실패' }
    }
    $imageId = (& docker image inspect $tag --format '{{.Id}}' | Out-String).Trim()
    return [ordered]@{ ref = $Ref; commit = $sha; worktree = $wt; tag = $tag
        imageId = $imageId; jarSha256 = $jarSha; recordedAt = (Get-Date -Format o) }
}

# 공유 호스트의 스택 밖 CPU 를 함께 남긴다. Get-Counter 카운터 이름은 로캘마다 달라 프로세스 CPU 시간 차로 잰다.
function Start-HostSampler {
    param([string] $OutCsv, [string] $StopFile)
    return Start-Job -ArgumentList $OutCsv, $StopFile -ScriptBlock {
        param($out, $stop)
        $cpus = [Environment]::ProcessorCount
        'ts,process,cpu_pct_of_logical' | Set-Content -Encoding utf8 $out
        $deadline = (Get-Date).AddHours(2)
        while (-not (Test-Path $stop) -and (Get-Date) -lt $deadline) {
            $before = @{}
            foreach ($p in Get-Process) { if ($null -ne $p.CPU) { $before[$p.Id] = $p.CPU } }
            Start-Sleep -Seconds 5
            $ts = Get-Date -Format 'HH:mm:ss'
            Get-Process | Where-Object { $null -ne $_.CPU -and $before.ContainsKey($_.Id) } |
                ForEach-Object { [pscustomobject]@{ Name = $_.ProcessName; Pct = ($_.CPU - $before[$_.Id]) / 5 / $cpus * 100 } } |
                Sort-Object Pct -Descending | Select-Object -First 8 |
                ForEach-Object { '{0},{1},{2:F1}' -f $ts, $_.Name, $_.Pct } | Add-Content -Encoding utf8 $out
            Start-Sleep -Seconds 10
        }
    }
}

function Invoke-Cell {
    param($Image, [string] $Label, [string[]] $ExtraArgs = @())
    $stop = Join-Path $EvidenceDir "stop-host-$Label"
    $job = Start-HostSampler (Join-Path $EvidenceDir "host_samples_$Label.csv") $stop
    try {
        $rc = Invoke-Logged "probe-$Label" 'python' (@('harness/scale/roundtrip_probe.py', '--label', $Label,
            '--rate', '600', '--out', (Join-Path $EvidenceDir "$Label.json")) + $ExtraArgs)
    } finally {
        New-Item -ItemType File -Force -Path $stop | Out-Null
        Wait-Job $job -Timeout 60 | Out-Null
        Remove-Job $job -Force
        Remove-Item $stop -Force -ErrorAction SilentlyContinue
    }
    $running = (& docker inspect -f '{{.Image}}' chaos-api-1 2>$null | Out-String).Trim()
    Write-CommandLog ('IMAGE {0} want={1} running={2}' -f $Label, $Image.imageId, $running)
    if ($running -ne $Image.imageId) { Stop-Blocked "$Label 칸이 고정한 이미지로 돌지 않았다" }
    return $rc
}

Write-CommandLog "RUN adr0022-stage6-b4b6 Ref=$Ref SkipBuild=$SkipBuild"
if (-not (Test-Path (Join-Path $repo 'harness\.env.chaos'))) { Stop-Blocked 'harness/.env.chaos 가 없다. 마스터키 없이 뜨면 전면 500 이다' }
& docker info *> $null
if ($LASTEXITCODE -ne 0) { Stop-Blocked '도커 데몬 미응답' }
# 남의 카오스 스택을 파기하거나 거기에 래치를 걸지 않는다. 프로브가 칸마다 down -v 를 하므로 시작 전에 비어 있어야 한다.
$existing = @(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}')
if ($existing.Count -gt 0) { Stop-Blocked ('chaos- 컨테이너가 이미 있다({0}). 다른 작업의 스택일 수 있다' -f ($existing -join ', ')) }
(& docker ps -a --format '{{.Names}} {{.Image}} {{.Status}}' | Out-String) | Set-Content -Encoding utf8 (Join-Path $EvidenceDir 'docker-ps-before.txt')

$image = Build-Stage6Image
# compose 는 셸 환경변수를 --env-file 값보다 앞세운다. 두 칸이 같은 compose·같은 ENV·같은 이미지다.
$env:CHAOS_APP_IMAGE = $image.tag

$composeText = [System.IO.File]::ReadAllText((Join-Path $repo 'harness\docker-compose.chaos.yml'))
$maxConnections = 100
if ($composeText -match 'max_connections=(\d+)') { $maxConnections = [int]$Matches[1] }
[ordered]@{ image = $image; maxConnections = $maxConnections; harnessCommit = (& git -C $repo rev-parse HEAD | Out-String).Trim() } |
    ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $EvidenceDir 'images.json')

# 연결 예산 사전 점검. 노드마다 요청 32(roundtrip_probe ENV), 리스 2, append 풀, 봇 요청 10, exporter 2, 표본기 4.
$rc = Invoke-Logged 'budget' 'python' @('harness/scale/pg_samplers.py', 'budget', '--api-nodes', '2',
    '--request-pool', '32', '--lease-pool', '2', '--append-pool', "$AppendPool", '--bots-request-pool', '10',
    '--exporter', '2', '--tooling', '4', '--max-connections', "$maxConnections", '--superuser-reserved', '3',
    '--out', (Join-Path $EvidenceDir 'budget.json'))
if ($rc -ne 0) { Stop-Blocked '연결 예산 부족. harness/docker-compose.chaos.yml 의 max_connections 를 올린다' }

$judgeRc = 5
try {
    if ((Invoke-Cell $image 'B4') -ne 0) { Stop-Blocked 'B4 프로브 실패. probe-B4.log 를 본다' }
    # B6 프로브도 기동 전에 볼륨을 파기하지만, 래치 칸이 B4 DB 를 이어받지 않았다는 사실을 명령 기록에 남긴다.
    if ((Invoke-Logged 'down-B4' 'docker' $downArgs) -ne 0) { Stop-Blocked 'B4 뒤 down -v 실패. down-B4.log 를 본다' }
    if ((Invoke-Cell $image 'B6' @('--latch-before-traffic')) -ne 0) { Stop-Blocked 'B6 프로브 실패(래치 거부·실패 포함). probe-B6.log 를 본다' }
    $rc = Invoke-Logged 'judge' 'python' @('harness/scale/stage6_judge.py', '--b4', (Join-Path $EvidenceDir 'B4.json'),
        '--b6', (Join-Path $EvidenceDir 'B6.json'), '--out', (Join-Path $EvidenceDir 'verdict.json'))
    $verdictPath = Join-Path $EvidenceDir 'verdict.json'
    # 파이썬 예외 종료도 1 이라 래치 무효와 겹친다. 판정 파일이 있어야 판정기 종료코드로 인정한다.
    if (-not (Test-Path $verdictPath)) { Stop-Blocked "판정기가 판정 파일을 쓰지 못했다(rc=$rc)" }
    $verdict = Get-Content $verdictPath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
    Write-CommandLog ('VERDICT {0} | {1} rc={2}' -f $verdict['최종 판정'], $verdict['분기(B6)']['분기'], $rc)
    $judgeRc = $rc
} finally {
    Invoke-Logged 'down' 'docker' $downArgs | Out-Null
}
exit $judgeRc
