# ADR 0034 S7 관문 스택 실험 Q1~Q16 실행기 초안(마켓분할_리더_S7_설계 5-1·5-3). 아직 돌린 적이 없다(NOT RUN).
# S6 관문이 끝난 뒤 메인 세션이 돈다. 실행할 때 이 폴더를 harness/evidence/pml-S7-<YYYYMMDD-HHMM>/ 로 옮겨 적고 -EvidenceDir 로 준다.
#
# 순서는 판정 중요도다(설계 5-3): Q1(E12) → Q2(E4+W6) → Q3a·Q3b(E7) → Q4(되돌리기·재진입) → Q5·Q6·Q7·Q8·Q9·Q10·Q11·Q16·Q13·Q15
# → Q12(처리량 기록) → Q14(C22' 비용, 스택 없음). Q8c(대조 칸)는 -Only 로만 돈다. 실험마다 harness/scripts/s7-p3-gate.ps1 을 따로 부르고,
# 그 스크립트가 실험마다 새 스택을 세우고 Q0 준비를 한 뒤 -p exchange-pml 이 붙은 down -v 로 내린다. 한 실험의 FAIL 은 다음 실험을
# 막지 않는다. 안전 수칙은 설계 5-0 이다. 이 실행기는 시작 전에 다음을 확인하고 하나라도 아니면 멈춘다.
#   CHAOS_PROJECT=exchange-pml, COMPOSE_PROJECT_NAME=exchange-pml, CHAOS_APP_IMAGE=exchange-chaos-app:pml, chaos- 컨테이너 없음,
#   이미지 exchange-chaos-app:pml 있음.
# 이미지는 up.ps1 -P3 -Obs 가 한 번 구운 것을 모든 실험이 같은 id 로 쓴다. 이 실행기는 이미지를 굽지 않는다.
# 비밀 파일(harness/.env.chaos, harness/load/secrets/*.json)은 하위 실행기가 경로로만 넘긴다. 여기서는 열지 않는다.
# 사용: pwsh -NoProfile -File harness/evidence/pml-S7-gate-draft/run-gate.ps1 -EvidenceDir <폴더> [-Only Q1,Q2,...]
# 종료코드: 0 전 실험 PASS(기록 칸은 RECORD), 1 그 밖, 5 시작 전 조건 미충족.
param(
    [Parameter(Mandatory)][string] $EvidenceDir,
    [string] $Only = 'Q1,Q2,Q3a,Q3b,Q4,Q5,Q6,Q7,Q8,Q9,Q10,Q11,Q16,Q13,Q15,Q12,Q14'
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$gate = Join-Path $repo 'harness\scripts\s7-p3-gate.ps1'
$image = 'exchange-chaos-app:pml'
$wanted = @($Only.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })

if ($PSVersionTable.PSVersion.Major -lt 7) { Write-Host 'pwsh 7 로 실행한다.'; exit 5 }
if ($env:CHAOS_PROJECT -ne 'exchange-pml' -or $env:COMPOSE_PROJECT_NAME -ne 'exchange-pml' -or $env:CHAOS_APP_IMAGE -ne $image) {
    Write-Host '설계 5-1 의 환경 변수(CHAOS_PROJECT·COMPOSE_PROJECT_NAME=exchange-pml, CHAOS_APP_IMAGE=exchange-chaos-app:pml)를 먼저 둔다.'; exit 5
}
if (@(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}').Count -gt 0) { Write-Host 'chaos- 컨테이너가 이미 있다. S6 관문이 끝났는지 본다.'; exit 5 }
if (-not (& docker image inspect $image --format '{{.Id}}' 2>$null)) { Write-Host "이미지가 없다: $image. up.ps1 -P3 -Obs 로 한 번 굽는다."; exit 5 }
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
# 이미지 id, HEAD, jar 해시를 한 번 남긴다(설계 5-1). 모든 실험이 같은 이미지 id 를 쓴다.
$jar = Join-Path $repo 'harness\app.jar'
$images = [ordered]@{
    image = $image; imageId = (& docker image inspect $image --format '{{.Id}}' | Out-String).Trim()
    head = (& git -C $repo rev-parse HEAD | Out-String).Trim()
    jarSha256 = $(if (Test-Path $jar) { (Get-FileHash $jar -Algorithm SHA256).Hash.ToLower() } else { $null })
    at = (Get-Date).ToUniversalTime().ToString('o')
}
[System.IO.File]::WriteAllText((Join-Path $EvidenceDir 'images.json'), ($images | ConvertTo-Json -Depth 3), [System.Text.UTF8Encoding]::new($false))

$results = [ordered]@{}
foreach ($q in $wanted) {
    $dir = Join-Path $EvidenceDir $q
    Remove-Item Env:CHAOS_COMPOSE_OVERLAYS -ErrorAction SilentlyContinue
    & pwsh -NoProfile -File $gate -Experiment $q -EvidenceDir $dir
    $rc = $LASTEXITCODE
    $verdict = $null
    $gateFile = Join-Path $dir 'gate.json'
    if (Test-Path $gateFile) { $verdict = (Get-Content $gateFile -Raw -Encoding utf8 | ConvertFrom-Json).verdict }
    $results[$q] = [ordered]@{ rc = $rc; verdict = $verdict }
    Write-Host ("{0} rc={1} verdict={2}" -f $q, $rc, $verdict)
    if (@(& docker ps -a --filter 'name=^chaos-' --format '{{.Names}}').Count -gt 0) {
        Write-Host "경고: $q 뒤에 chaos- 컨테이너가 남았다. 다음 실험을 시작하지 않는다." -ForegroundColor Yellow
        break
    }
}
Remove-Item Env:CHAOS_COMPOSE_OVERLAYS -ErrorAction SilentlyContinue
[System.IO.File]::WriteAllText((Join-Path $EvidenceDir 'gate-results.json'), ($results | ConvertTo-Json -Depth 4),
    [System.Text.UTF8Encoding]::new($false))
if (@($results.Values | Where-Object { $_.rc -ne 0 }).Count -eq 0) { exit 0 }
exit 1
