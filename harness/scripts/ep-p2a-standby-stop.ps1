# E-P2a Standby 정지(Patroni 설계 8-3). 동기 Standby 컨테이너를 docker stop 하고 90초 뒤 docker start 한다.
# 기대: 쓰기 정지, 게이트 423, 조회 계속, 앱 리더 유지, 복귀 뒤 자동 재개. 출금 요청기와 풀·in-flight 표본을 더한다.
param([switch] $Build, [switch] $KeepStack, [switch] $NoK6)
. (Join-Path $PSScriptRoot 'ep-lib.ps1')
$inject = {
    param($tl)
    $standby = Get-EpStandbyContainer
    Assert-EpContainer $standby
    # docker stop 은 정상 종료를 기다린다. T0 는 명령 직전이다.
    & docker stop $standby | Out-Null
    return @{ injected = $standby; role_injected = 'standby'; sync_member = $standby }
}
$recover = { param($tl) $tl.start_failures = @(Start-EpContainer $tl.injected) }
$code = @(Invoke-EpExperiment -Scenario 'E-P2a' -Inject $inject -Recover $recover -HoldSec 90 -ExpectSuspension -Pool -Withdraw `
    -K6:(-not $NoK6) -Build:$Build -KeepStack:$KeepStack)
exit ([int]($code[-1]))
