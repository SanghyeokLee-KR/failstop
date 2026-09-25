# 실행기 한 단계를 부르는 감싸개. 앞뒤로 호스트의 Testcontainers 컨테이너 수(다른 세션의 서버 시험)를 host-concurrency.txt 에 남기고,
# 실행기 출력을 <라벨>-runner.log 에 남긴다. 사용: pwsh -NoProfile -File step.ps1 -Label <라벨> -- <run-s4b-gate.ps1 인자>
param(
    [Parameter(Mandatory = $true)][string] $Label,
    [Parameter(ValueFromRemainingArguments = $true)][string[]] $Rest
)
$ErrorActionPreference = 'Continue'
$here = $PSScriptRoot
$env:CHAOS_PROJECT = 'exchange-pml'
$env:COMPOSE_PROJECT_NAME = 'exchange-pml'
$env:CHAOS_APP_IMAGE = 'exchange-chaos-app:pml'
function Write-Concurrency([string] $Tag) {
    $tc = @(& docker ps -q --filter 'label=org.testcontainers=true').Count
    $all = @(& docker ps -q).Count
    $cpu = (Get-CimInstance Win32_Processor | Measure-Object LoadPercentage -Average).Average
    Add-Content -Path (Join-Path $here 'host-concurrency.txt') -Value ('{0} {1} {2} testcontainers={3} containers={4} cpu={5}' -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'), $Label, $Tag, $tc, $all, $cpu) -Encoding utf8
}
Write-Concurrency 'before'
$args2 = @($Rest | Where-Object { $_ -ne '--' })
& pwsh -NoProfile -File (Join-Path $here 'run-s4b-gate.ps1') @args2 *> (Join-Path $here "$Label-runner.log")
$rc = $LASTEXITCODE
Write-Concurrency "after rc=$rc"
exit $rc
