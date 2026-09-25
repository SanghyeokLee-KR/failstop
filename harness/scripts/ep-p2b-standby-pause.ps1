# E-P2b Standby 소리 없는 정지(Patroni 설계 8-3). docker pause 60초 뒤 unpause. Patroni 는 한동안 Standby 를 동기로 적은 채이고
# 탐지기가 탐지 백엔드의 SyncRep 대기로 알아챈다. '*' 로 바뀌기 전에 T_suspend 가 오는지 기록한다(extra.suspend_before_sync_loss_s).
param([switch] $Build, [switch] $KeepStack, [switch] $NoK6)
. (Join-Path $PSScriptRoot 'ep-lib.ps1')
$inject = {
    param($tl)
    $standby = Get-EpStandbyContainer
    Assert-EpContainer $standby
    & docker pause $standby | Out-Null
    return @{ injected = $standby; role_injected = 'standby'; sync_member = $standby }
}
$recover = { param($tl) & docker unpause $tl.injected | Out-Null }
$code = @(Invoke-EpExperiment -Scenario 'E-P2b' -Inject $inject -Recover $recover -HoldSec 60 -ExpectSuspension -Pool -Withdraw `
    -K6:(-not $NoK6) -Build:$Build -KeepStack:$KeepStack)
exit ([int]($code[-1]))
