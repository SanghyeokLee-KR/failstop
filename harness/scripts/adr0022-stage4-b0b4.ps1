# ADR 0022 4단계 판정 주행. 같은 날 같은 카오스 스택에서 B0(main 기준) 다음 B4(4단계 브랜치 기준)를 제시 부하
# 600/s 로 돌리고 harness/scale/stage4_judge.py 로 판정한다. 증거는 harness/evidence/adr0022-stage4-b0b4-<YYYYMMDD>/ 다.
# 사용: pwsh -NoProfile -File harness/scripts/adr0022-stage4-b0b4.ps1 -B4Ref <4단계 브랜치> [-B0Ref main] [-SkipBuild]
# 종료코드: 판정기 값(0 평평·명제 성립, 1 반증, 2 NOT RUN, 4 연결 고갈), 5 BLOCKED(준비·빌드·주행 실패).
# 비밀 파일(harness/.env.chaos, harness/load/secrets/k6_keys.json)은 경로로만 넘기고 읽지 않는다.
param(
    [string] $B4Ref = '',
    [string] $B0Ref = 'main',
    [string] $WorktreeRoot = 'C:\dev',
    [string] $EvidenceDir = '',
    [int] $B0AppendPool = 2,
    [int] $B4AppendPool = 14,
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
# psql·python 의 UTF-8 출력을 코드 페이지로 잘못 풀면 한글 뒤 ASCII 까지 먹힌다.
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONIOENCODING = 'utf-8'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $EvidenceDir) { $EvidenceDir = Join-Path $repo ("harness\evidence\adr0022-stage4-b0b4-{0}" -f (Get-Date -Format 'yyyyMMdd')) }
$commandLog = Join-Path $EvidenceDir 'commands.log'

if ($PSVersionTable.PSVersion.Major -lt 7) { Write-Host 'pwsh 7 로 실행한다(CLAUDE.md 30절).'; exit 5 }
if (-not $B4Ref) { Write-Host '-B4Ref 가 필요하다. 4단계 구현 브랜치 또는 커밋이다.'; exit 5 }
foreach ($f in 'B0.json', 'B4.json', 'verdict.json') {
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

function Resolve-Commit {
    param([string] $Ref)
    $sha = (& git -C $repo rev-parse --verify --quiet "$Ref^{commit}" | Out-String).Trim()
    if ($sha -notmatch '^[0-9a-f]{40}$') { Stop-Blocked "ref 를 커밋으로 해석하지 못했다: $Ref" }
    return $sha
}

# 이미지는 작업 트리마다 그 커밋의 Dockerfile.app 으로 굽고 revision 라벨에 커밋을 박는다.
function Build-CellImage {
    param([string] $Cell, [string] $Ref)
    $sha = Resolve-Commit $Ref
    $name = 'adr0022-{0}-{1}' -f $Cell.ToLower(), $sha.Substring(0, 7)
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
            if ((Invoke-Logged "worktree-$Cell" 'git' @('-C', $repo, 'worktree', 'add', '--detach', $wt, $sha)) -ne 0) { Stop-Blocked "작업 트리 생성 실패: $wt" }
        }
        $head = (& git -C $wt rev-parse HEAD | Out-String).Trim()
        if ($head -ne $sha) { Stop-Blocked "작업 트리 $wt 의 HEAD($head)가 $sha 가 아니다" }
        $dirty = @(& git -C $wt status --porcelain -- server harness).Count
        if ($dirty -ne 0) { Stop-Blocked "작업 트리 $wt 에 커밋되지 않은 server·harness 경로가 $dirty 개 있다" }
        $server = Join-Path $wt 'server'
        if ((Invoke-Logged "bootjar-$Cell" (Join-Path $server 'gradlew.bat') @(':app:bootJar', '-q') $server) -ne 0) { Stop-Blocked "bootJar 실패: $Cell" }
        $jar = Get-ChildItem (Join-Path $server 'app\build\libs') -Filter '*.jar' | Where-Object { $_.Name -notmatch 'plain' } | Select-Object -First 1
        if (-not $jar) { Stop-Blocked "bootJar 산출물 없음: $Cell" }
        Copy-Item $jar.FullName (Join-Path $wt 'harness\app.jar') -Force
        $jarSha = (Get-FileHash $jar.FullName -Algorithm SHA256).Hash.ToLower()
        $buildArgs = @('build', '-f', 'Dockerfile.app', '-t', $tag,
            '--label', "org.opencontainers.image.revision=$sha", '--label', "org.opencontainers.image.ref.name=$Ref", '.')
        if ((Invoke-Logged "image-$Cell" 'docker' $buildArgs (Join-Path $wt 'harness')) -ne 0) { Stop-Blocked "이미지 빌드 실패: $Cell" }
    }
    $imageId = (& docker image inspect $tag --format '{{.Id}}' | Out-String).Trim()
    return [ordered]@{ cell = $Cell; ref = $Ref; commit = $sha; worktree = $wt; tag = $tag
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
    param($Image, [string] $Label)
    # compose 는 셸 환경변수를 --env-file 값보다 앞세운다. 두 칸이 같은 compose·같은 ENV 에서 이미지만 다르다.
    $env:CHAOS_APP_IMAGE = $Image.tag
    $stop = Join-Path $EvidenceDir "stop-host-$Label"
    $job = Start-HostSampler (Join-Path $EvidenceDir "host_samples_$Label.csv") $stop
    try {
        $rc = Invoke-Logged "probe-$Label" 'python' @('harness/scale/roundtrip_probe.py', '--label', $Label,
            '--rate', '600', '--out', (Join-Path $EvidenceDir "$Label.json"))
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

Write-CommandLog "RUN adr0022-stage4-b0b4 B0Ref=$B0Ref B4Ref=$B4Ref SkipBuild=$SkipBuild"
if (-not (Test-Path (Join-Path $repo 'harness\.env.chaos'))) { Stop-Blocked 'harness/.env.chaos 가 없다. 마스터키 없이 뜨면 전면 500 이다' }
& docker info *> $null
if ($LASTEXITCODE -ne 0) { Stop-Blocked '도커 데몬 미응답' }
# 남의 카오스 스택을 파기하지 않는다. 프로브가 칸마다 down -v 를 하므로 시작 전에 비어 있어야 한다.
$existing = @(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}')
if ($existing.Count -gt 0) { Stop-Blocked ('chaos- 컨테이너가 이미 있다({0}). 다른 작업의 스택일 수 있다' -f ($existing -join ', ')) }
(& docker ps -a --format '{{.Names}} {{.Image}} {{.Status}}' | Out-String) | Set-Content -Encoding utf8 (Join-Path $EvidenceDir 'docker-ps-before.txt')

$b0 = Build-CellImage 'B0' $B0Ref
$b4 = Build-CellImage 'B4' $B4Ref
if ($b0.imageId -eq $b4.imageId) { Stop-Blocked 'B0 와 B4 이미지가 같다' }

$composeText = [System.IO.File]::ReadAllText((Join-Path $repo 'harness\docker-compose.chaos.yml'))
$maxConnections = 100
if ($composeText -match 'max_connections=(\d+)') { $maxConnections = [int]$Matches[1] }
[ordered]@{ B0 = $b0; B4 = $b4; maxConnections = $maxConnections; harnessCommit = (& git -C $repo rev-parse HEAD | Out-String).Trim() } |
    ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $EvidenceDir 'images.json')

# 연결 예산 사전 점검. 노드마다 요청 32(roundtrip_probe ENV), 리스 2, append 풀, 봇 요청 10, exporter 2, 표본기 4.
foreach ($cell in @(@('B0', $B0AppendPool), @('B4', $B4AppendPool))) {
    $rc = Invoke-Logged "budget-$($cell[0])" 'python' @('harness/scale/pg_samplers.py', 'budget', '--api-nodes', '2',
        '--request-pool', '32', '--lease-pool', '2', '--append-pool', "$($cell[1])", '--bots-request-pool', '10',
        '--exporter', '2', '--tooling', '4', '--max-connections', "$maxConnections", '--superuser-reserved', '3',
        '--out', (Join-Path $EvidenceDir "budget_$($cell[0]).json"))
    if ($rc -ne 0) { Stop-Blocked "연결 예산 부족($($cell[0])). harness/docker-compose.chaos.yml 의 max_connections 를 올린다" }
}

$judgeRc = 5
try {
    if ((Invoke-Cell $b0 'B0') -ne 0) { Stop-Blocked 'B0 프로브 실패. probe-B0.log 를 본다' }
    if ((Invoke-Cell $b4 'B4') -ne 0) { Stop-Blocked 'B4 프로브 실패. probe-B4.log 를 본다' }
    $rc = Invoke-Logged 'judge' 'python' @('harness/scale/stage4_judge.py', '--b0', (Join-Path $EvidenceDir 'B0.json'),
        '--b4', (Join-Path $EvidenceDir 'B4.json'), '--out', (Join-Path $EvidenceDir 'verdict.json'))
    $verdictPath = Join-Path $EvidenceDir 'verdict.json'
    # 파이썬 예외 종료도 1 이라 반증과 겹친다. 판정 파일이 있어야 판정기 종료코드로 인정한다.
    if (-not (Test-Path $verdictPath)) { Stop-Blocked "판정기가 판정 파일을 쓰지 못했다(rc=$rc)" }
    $final = (Get-Content $verdictPath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable).'최종 판정'
    Write-CommandLog "VERDICT $final rc=$rc"
    $judgeRc = $rc
} finally {
    Invoke-Logged 'down' 'docker' @('compose', '-f', 'harness/docker-compose.chaos.yml', '-f', 'harness/docker-compose.scale.yml',
        '--env-file', 'harness/.env.chaos', '--profile', 'obs', 'down', '-v', '--remove-orphans') | Out-Null
}
exit $judgeRc
