# ADR 0022 7단계 스냅샷 경계 재생 주행. 이미지 한 벌로 전역 모드(래치 없음)와 대역 모드(트래픽 전 래치)에서
# 장애 칸 E2·E4·E5·E6·E7 과 콜드 재기동 칸 COLD 를 칸마다 새 스택으로 돌리고 harness/scale/stage7_judge.py 로 가른다. 규약과
# 시간 예산은 docs/contracts/하네스_검증설계.md §2-4 의 「ADR 0022 7단계 스냅샷 경계 재생 판정 규약」이다. 실험 주행과 채집은
# adr0022-stage5-chaos.ps1 -SnapshotEvidence 가 하고 COLD 는 adr0022-stage7-cold-restart.ps1 이다.
# 증거는 harness/evidence/adr0022-stage7-snapshot-<YYYYMMDD>/<global|band>/<칸>/ 다.
# 사용: pwsh -NoProfile -File harness/scripts/adr0022-stage7-snapshot.ps1 (-Image <태그> | -Ref <커밋> [-SkipBuild])
#       [-Modes global,band] [-Experiments E2,E4,E5,E6,E7,COLD] [-SnapshotWaitSec 180]
# 종료코드: 판정기 값(0 스냅샷 경계 재생 성립, 1 FAIL, 2 NOT RUN, 4 증거 불완전), 5 준비·빌드·주행 실패.
# 대역 모드 래치는 되돌릴 수 없다. 칸마다 down -v 가 그 DB 를 버린다. 비밀 파일(harness/.env.chaos)은 경로로만 넘기고 읽지 않는다.
param(
    [string] $Image = '',
    [string] $Ref = '',
    [string] $Modes = 'global,band',
    [string] $Experiments = 'E2,E4,E5,E6,E7,COLD',
    [string] $EvidenceDir = '',
    [string] $WorktreeRoot = 'C:\dev',
    [int] $SnapshotWaitSec = 180,
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
# python 의 UTF-8 출력을 코드 페이지로 잘못 풀면 한글 뒤 ASCII 까지 먹힌다.
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:PYTHONIOENCODING = 'utf-8'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$date = Get-Date -Format 'yyyyMMdd'
if (-not $EvidenceDir) { $EvidenceDir = Join-Path $repo "harness\evidence\adr0022-stage7-snapshot-$date" }
$commandLog = Join-Path $EvidenceDir 'commands.log'

if ($PSVersionTable.PSVersion.Major -lt 7) { Write-Host 'pwsh 7 로 실행한다(CLAUDE.md 30절).'; exit 5 }
if ([bool]$Image -eq [bool]$Ref) { Write-Host '-Image 와 -Ref 중 하나만 준다. 두 모드가 같은 이미지여야 칸을 견줄 수 있다.'; exit 5 }
$modeList = @($Modes.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($modeList.Count -eq 0 -or @($modeList | Where-Object { $_ -notin @('global', 'band') }).Count -gt 0) {
    Write-Host "모드는 global, band 다: $Modes"; exit 5
}
if (Test-Path (Join-Path $EvidenceDir 'verdict.json')) { Write-Host '이미 verdict.json 이 있다. 증거를 덮지 않는다. -EvidenceDir 로 다른 경로를 준다.'; exit 5 }
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

# -Image 는 있는 태그를 그대로 쓴다. -Ref 는 6단계 절차와 같게 작업 트리에서 그 커밋의 Dockerfile.app 으로 굽고
# revision 라벨에 커밋을 박는다. 두 모드가 이 한 벌을 쓴다.
function Resolve-Stage7Image {
    if ($Image) {
        $id = (& docker image inspect $Image --format '{{.Id}}' 2>$null | Out-String).Trim()
        if (-not $id) { Stop-Blocked "이미지가 없다: $Image" }
        $labels = (& docker image inspect $Image --format '{{json .Config.Labels}}' 2>$null | Out-String).Trim()
        $rev = $null
        if ($labels -and $labels -ne 'null') { $rev = ($labels | ConvertFrom-Json).'org.opencontainers.image.revision' }
        return [ordered]@{ ref = $null; commit = $rev; tag = $Image; imageId = $id; jarSha256 = $null; recordedAt = (Get-Date -Format o) }
    }
    $sha = (& git -C $repo rev-parse --verify --quiet "$Ref^{commit}" | Out-String).Trim()
    if ($sha -notmatch '^[0-9a-f]{40}$') { Stop-Blocked "ref 를 커밋으로 해석하지 못했다: $Ref" }
    $name = 'adr0022-stage7-{0}' -f $sha.Substring(0, 7)
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
    return [ordered]@{ ref = $Ref; commit = $sha; tag = $tag; imageId = $imageId; jarSha256 = $jarSha; recordedAt = (Get-Date -Format o) }
}

Write-CommandLog ("RUN adr0022-stage7-snapshot Image=$Image Ref=$Ref SkipBuild=$SkipBuild Modes=$($modeList -join ',') " +
    "Experiments=$Experiments SnapshotWaitSec=$SnapshotWaitSec")
if (-not (Test-Path (Join-Path $repo 'harness\.env.chaos'))) { Stop-Blocked 'harness/.env.chaos 가 없다. 마스터키 없이 뜨면 전면 500 이다' }
& docker info *> $null
if ($LASTEXITCODE -ne 0) { Stop-Blocked '도커 데몬 미응답' }
# 실험마다 down -v 를 한다. 남의 카오스 스택을 파기하거나 거기에 래치를 걸지 않도록 시작 전에 비어 있어야 한다.
$existing = @(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}')
if ($existing.Count -gt 0) { Stop-Blocked ('chaos- 컨테이너가 이미 있다({0}). 다른 작업의 스택일 수 있다' -f ($existing -join ', ')) }

$img = Resolve-Stage7Image
[ordered]@{ image = $img; harnessCommit = (& git -C $repo rev-parse HEAD | Out-String).Trim(); modes = $modeList
    experiments = $Experiments; snapshotWaitSec = $SnapshotWaitSec } |
    ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $EvidenceDir 'images.json')

$chaos = Join-Path $PSScriptRoot 'adr0022-stage5-chaos.ps1'
foreach ($mode in $modeList) {
    $modeDir = Join-Path $EvidenceDir $mode
    $chaosArgs = @('-NoProfile', '-File', $chaos, '-Image', $img.tag, '-Experiments', $Experiments, '-EvidenceDir', $modeDir,
        '-LocalLogDir', (Join-Path ([System.IO.Path]::GetTempPath()) "adr0022-stage7-snapshot-$date\$mode"),
        '-SnapshotEvidence', '-SnapshotWaitSec', "$SnapshotWaitSec")
    if ($mode -eq 'band') { $chaosArgs += '-LatchBeforeTraffic' }
    $rc = Invoke-Logged "chaos-$mode" 'pwsh' $chaosArgs
    # 1 은 실험 판정이나 연결 판정이 PASS 가 아닌 주행이고 판정기가 가른다. 5 는 시작 전 조건 실패다.
    if ($rc -eq 5 -or -not (Test-Path (Join-Path $modeDir 'summary.json'))) {
        Stop-Blocked "$mode 모드 주행이 요약을 남기지 못했다(rc=$rc). chaos-$mode.log 와 $mode/commands.log 를 본다"
    }
}

$verdictPath = Join-Path $EvidenceDir 'verdict.json'
$judgeArgs = @('harness/scale/stage7_judge.py', '--modes', ($modeList -join ','), '--experiments', $Experiments, '--out', $verdictPath)
foreach ($mode in $modeList) { $judgeArgs += @("--$mode", (Join-Path $EvidenceDir $mode)) }
$rc = Invoke-Logged 'judge' 'python' $judgeArgs
# 파이썬 예외 종료도 1 이라 FAIL 과 겹친다. 판정 파일이 있어야 판정기 종료코드로 인정한다.
if (-not (Test-Path $verdictPath) -or $rc -notin @(0, 1, 2, 4)) { Stop-Blocked "판정기가 판정 파일을 쓰지 못했다(rc=$rc)" }
$verdict = Get-Content $verdictPath -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
Write-CommandLog ('VERDICT {0} rc={1}' -f $verdict['최종 판정'], $rc)
exit $rc
