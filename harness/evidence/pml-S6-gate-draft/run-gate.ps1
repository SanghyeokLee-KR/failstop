# ADR 0034 S6 관문 스택 실험 W0~W5 실행기 초안(마켓분할_리더_S6_설계 7-1·7-2). 아직 돌린 적이 없다(NOT RUN).
# S5 를 병합한 트리에서 메인 세션이 돈다. 실행할 때 이 폴더를 harness/evidence/pml-S6-<YYYYMMDD-HHMM>/ 로 옮겨 적고 -EvidenceDir 로 준다.
#
# 순서: W0(기본 스택, 오버레이 없음) → W1~W4(중계 오버레이, s6-relay-outage.ps1) → W5(중계 오버레이, adr0022-stage5-chaos.ps1 -Relay).
# 실험마다 스택을 새로 세우고 P2 로 전환한다. 안전 수칙은 설계 7-0 이다. 이 실행기는 시작 전에 다음을 확인하고 하나라도 아니면 멈춘다.
#   CHAOS_PROJECT=exchange-pml, COMPOSE_PROJECT_NAME=exchange-pml, CHAOS_APP_IMAGE=exchange-chaos-app:pml, chaos- 컨테이너 없음.
# 이미지는 up.ps1 이 한 번 구운 것을 모든 실험이 같은 id 로 쓴다. 이 실행기는 이미지를 굽지 않는다.
# 비밀 파일(harness/.env.chaos)은 하위 실행기가 compose 에 경로로만 넘긴다. 여기서는 열지 않는다.
# 사용: pwsh -NoProfile -File harness/evidence/pml-S6-gate-draft/run-gate.ps1 -EvidenceDir <폴더> [-Only W0,W1,...]
# 종료코드: 0 전 실험 PASS, 1 그 밖, 5 시작 전 조건 미충족.
param(
    [Parameter(Mandatory)][string] $EvidenceDir,
    [string] $Only = 'W0,W1,W2,W3,W4,W5'
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$scripts = Join-Path $repo 'harness\scripts'
$image = 'exchange-chaos-app:pml'
$wanted = @($Only.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })

if ($PSVersionTable.PSVersion.Major -lt 7) { Write-Host 'pwsh 7 로 실행한다.'; exit 5 }
if ($env:CHAOS_PROJECT -ne 'exchange-pml' -or $env:COMPOSE_PROJECT_NAME -ne 'exchange-pml' -or $env:CHAOS_APP_IMAGE -ne $image) {
    Write-Host '설계 7-1 의 환경 변수(CHAOS_PROJECT·COMPOSE_PROJECT_NAME=exchange-pml, CHAOS_APP_IMAGE=exchange-chaos-app:pml)를 먼저 둔다.'; exit 5
}
if (@(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}').Count -gt 0) { Write-Host 'chaos- 컨테이너가 이미 있다.'; exit 5 }
if (-not (& docker image inspect $image --format '{{.Id}}' 2>$null)) { Write-Host "이미지가 없다: $image. up.ps1 로 한 번 굽는다."; exit 5 }
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null

$results = [ordered]@{}
foreach ($w in $wanted) {
    $dir = Join-Path $EvidenceDir $w
    switch ($w) {
        'W0' {
            # 기본 모드 회귀. 오버레이 없이 E4·E7 을 한 번씩 돌고 WS 게이트 축(simple)을 본다.
            Remove-Item Env:CHAOS_COMPOSE_OVERLAYS -ErrorAction SilentlyContinue
            & pwsh -NoProfile -File (Join-Path $scripts 'adr0022-stage5-chaos.ps1') -Image $image -Experiments 'E4,E7' -Protocol 2 `
                -PartitionAxes -WsGateAxes -EvidenceDir $dir
        }
        { $_ -in 'W1', 'W2', 'W3', 'W4' } {
            $env:CHAOS_COMPOSE_OVERLAYS = 'harness/docker-compose.relay.yml'
            & pwsh -NoProfile -File (Join-Path $scripts 's6-relay-outage.ps1') -Image $image -Experiment $w -EvidenceDir $dir
        }
        'W5' {
            # 리더 kill(P2, 중계). 실행기가 오버레이 확인을 통과해야 관찰자를 띄우고 E4 를 시작한다.
            $env:CHAOS_COMPOSE_OVERLAYS = 'harness/docker-compose.relay.yml'
            & pwsh -NoProfile -File (Join-Path $scripts 'adr0022-stage5-chaos.ps1') -Image $image -Experiments 'E4' -Protocol 2 `
                -PartitionAxes -Relay -WsGateAxes -EvidenceDir $dir
        }
        default { Write-Host "이 초안에 없는 실험: $w"; $LASTEXITCODE = 5 }
    }
    $results[$w] = [ordered]@{ rc = $LASTEXITCODE; state = $(switch ($LASTEXITCODE) { 0 { 'PASS' } 5 { 'BLOCKED' } default { 'FAIL' } }) }
    Write-Host ("{0} rc={1}" -f $w, $LASTEXITCODE)
}
Remove-Item Env:CHAOS_COMPOSE_OVERLAYS -ErrorAction SilentlyContinue
[System.IO.File]::WriteAllText((Join-Path $EvidenceDir 'gate-results.json'), ($results | ConvertTo-Json -Depth 4),
    [System.Text.UTF8Encoding]::new($false))
if (@($results.Values | Where-Object { $_.rc -ne 0 }).Count -eq 0) { exit 0 }
exit 1
