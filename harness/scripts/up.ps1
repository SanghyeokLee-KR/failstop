# 격리 카오스 스택 기동. 데모 스택을 건드리지 않는다(분리 포트·DB·네트워크).
#   1) 호스트에서 :app:bootJar 빌드(프로젝트는 ASCII 경로에 있어야 한다)
#   2) app.jar를 빌드 컨텍스트로 복사 → 이미지 빌드
#   3) compose up --wait
# 사용: pwsh -File scripts/up.ps1 [-Obs]
param([switch] $Obs)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')

# Windows PowerShell 5.1은 EAP=Stop에서 native exe가 stderr(정상 진행출력)를 내면 NativeCommandError로
# 승격시켜 종료코드 0에도 throw한다. build·compose 같은 noisy 호출은 이 구간에서만 EAP를 낮추고
# 종료코드로 판정한다. pwsh 동작·판정 결과는 불변(종료코드 기준 동일).
function Invoke-Native {
    param([scriptblock] $Command, [string] $FailMessage)
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Command } finally { $ErrorActionPreference = $eap }
    if ($LASTEXITCODE -ne 0) { throw $FailMessage }
}

$server = Resolve-Path (Join-Path $PSScriptRoot '..\..\server')
$harness = Resolve-Path (Join-Path $PSScriptRoot '..')

# .env.chaos 부재 시 빈 마스터키로 StartupSecretValidator가 기동을 막고 compose --env-file도 깨진다.
# 빌드 전에 먼저 잡아 example 복사를 안내한다.
if (-not (Test-Path $Global:CHAOS.EnvFile)) {
    throw ".env.chaos 부재. harness/.env.chaos.example를 harness/.env.chaos로 복사하고 CHAOS_MASTER_KEY_BASE64 등 값을 채운 뒤 다시 실행한다."
}

Write-Section '1/3 bootJar 빌드'
Push-Location $server
try {
    Invoke-Native { & .\gradlew.bat :app:bootJar -q } 'bootJar 빌드 실패'
} finally { Pop-Location }

$jar = Get-ChildItem (Join-Path $server 'app\build\libs') -Filter '*.jar' |
    Where-Object { $_.Name -notmatch 'plain' } | Select-Object -First 1
if (-not $jar) { throw 'bootJar 산출물을 찾지 못함' }
Copy-Item $jar.FullName (Join-Path $harness 'app.jar') -Force
Write-Host "jar: $($jar.Name) → app.jar"

Write-Section '2/3 이미지 빌드'
$env = Read-EnvFile $Global:CHAOS.EnvFile
$image = $env['CHAOS_APP_IMAGE']
Push-Location $harness
try {
    Invoke-Native { & docker build -f Dockerfile.app -t $image . } '이미지 빌드 실패'
} finally { Pop-Location }

Write-Section '3/3 compose up'
if ($Obs) {
    Invoke-Native { Invoke-Compose --profile obs up -d --wait } 'compose up 실패'
} else {
    Invoke-Native { Invoke-Compose up -d --wait } 'compose up 실패'
}

Write-Section '상태'
Invoke-Compose ps
Write-Host ''
Write-Host '리더 리스:' -NoNewline
Invoke-Psql "SELECT holder, epoch, lease_until > now() AS alive FROM leader_lease WHERE resource='matching-engine'"
