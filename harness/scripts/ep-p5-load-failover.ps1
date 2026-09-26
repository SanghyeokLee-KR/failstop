# E-P5 부하 중 Primary 강제 종료(Patroni 설계 8-3). 봇 40 OPS, k6 접수 주행, 출금 요청기를 띄운 채 E-P1 주입을 한다.
# 결과를 모르는 접수가 생기고 V47 이 새 Primary 에서 판정한다.
param([switch] $Build, [switch] $KeepStack, [switch] $NoK6)
. (Join-Path $PSScriptRoot 'ep-lib.ps1')
$inject = {
    param($tl)
    $primary = Get-EpPrimaryContainer
    Assert-EpContainer $primary
    & docker kill $primary | Out-Null
    return @{ injected = $primary; role_injected = 'primary'; sync_member = $primary }
}
$recover = { param($tl) $tl.start_failures = @(Start-EpContainer $tl.injected) }
$code = @(Invoke-EpExperiment -Scenario 'E-P5' -Inject $inject -Recover $recover -HoldSec 60 -ExpectEpochChange -ExpectSuspension `
    -BotsOps 40 -Withdraw -Pool -K6:(-not $NoK6) -Build:$Build -KeepStack:$KeepStack)
exit ([int]($code[-1]))
