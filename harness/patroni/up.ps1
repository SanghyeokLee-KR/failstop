# Patroni 실험 스택 기동(Patroni 설계 3-7). 카오스 스택(harness/scripts/up.ps1)과 같은 순서다.
#   1) 호스트에서 :app:bootJar 빌드(프로젝트는 ASCII 경로에 있어야 한다)
#   2) 앱 이미지(exchange-chaos-app:patroni)와 DB 이미지(exchange-patroni-db:local) 빌드
#   3) compose up --wait
# 사용: pwsh -NoProfile -File harness/patroni/up.ps1 [-Obs] [-SkipBuild]
# 회차마다 새 스택으로 시작한다. 이미 떠 있으면 먼저 down.ps1 로 지운다(설계 8-1).
param([switch] $Obs, [switch] $SkipBuild)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$harness = Resolve-Path (Join-Path $here '..')
$server = Resolve-Path (Join-Path $here '..\..\server')
$envFile = Join-Path $here '.env.patroni'
$compose = Join-Path $here 'docker-compose.patroni.yml'
$project = 'exchange-patroni'

function Invoke-Native([scriptblock] $Command, [string] $FailMessage) {
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Command } finally { $ErrorActionPreference = $eap }
    if ($LASTEXITCODE -ne 0) { throw $FailMessage }
}

if (-not (Test-Path $envFile)) {
    throw ".env.patroni 부재. harness/patroni/.env.patroni.example 를 harness/patroni/.env.patroni 로 복사한다(실험 전용 값)."
}
$running = docker ps -a --filter "label=com.docker.compose.project=$project" --format '{{.Names}}'
if ($running) {
    throw "이미 $project 컨테이너가 있다. 회차마다 새 스택으로 시작하므로 down.ps1 로 먼저 지운다: $($running -join ', ')"
}

$appImage = 'exchange-chaos-app:patroni'
foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*PATRONI_APP_IMAGE\s*=\s*(.+?)\s*$') { $appImage = $Matches[1] }
}
if ($appImage -notmatch ':patroni$') {
    # 같은 PC 의 다른 작업이 쓰는 태그(:local, :pml)를 덮지 않는다.
    throw "앱 이미지 태그는 이 스택 전용(:patroni)이어야 한다: $appImage"
}

if (-not $SkipBuild) {
    Write-Host '== 1/3 bootJar 빌드'
    Push-Location $server
    try { Invoke-Native { & .\gradlew.bat :app:bootJar -q } 'bootJar 빌드 실패' } finally { Pop-Location }
    $jar = Get-ChildItem (Join-Path $server 'app\build\libs') -Filter '*.jar' |
        Where-Object { $_.Name -notmatch 'plain' } | Select-Object -First 1
    if (-not $jar) { throw 'bootJar 산출물을 찾지 못함' }
    Copy-Item $jar.FullName (Join-Path $harness 'app.jar') -Force

    Write-Host "== 2/3 이미지 빌드: $appImage, exchange-patroni-db:local"
    Push-Location $harness
    try { Invoke-Native { & docker build -q -f Dockerfile.app -t $appImage . } '앱 이미지 빌드 실패' } finally { Pop-Location }
    Invoke-Native { & docker compose -p $project -f $compose --env-file $envFile build } 'DB 이미지 빌드 실패'
}

Write-Host '== 3/3 compose up'
$profileArgs = if ($Obs) { @('--profile', 'obs') } else { @() }
Invoke-Native { & docker compose -p $project -f $compose --env-file $envFile @profileArgs up -d --wait } 'compose up 실패'

Invoke-Native { & docker exec patroni-db-1 patronictl -c /patroni/patroni.yml list } 'patronictl list 실패'
Write-Host '거래 상태(LB):'
curl.exe -s http://127.0.0.1:59180/api/v1/status
Write-Host ''
