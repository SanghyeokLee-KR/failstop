# E-P1 Primary 강제 종료(Patroni 설계 8-3). 현재 Primary 컨테이너를 docker kill 하고 60초 뒤 docker start 한다.
# 기대: 다른 노드 승격, 엄격 모드라 쓰기 중단('*'), 옛 노드 되감기 복귀 뒤 자동 재개, 재개 뒤 앱 리더 epoch 정확히 +1.
# PASS 조건과 시각은 harness/scale/patroni_axes.py 의 E-P1 축이다.
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
$code = @(Invoke-EpExperiment -Scenario 'E-P1' -Inject $inject -Recover $recover -HoldSec 60 -ExpectEpochChange -ExpectSuspension `
    -K6:(-not $NoK6) -Build:$Build -KeepStack:$KeepStack)
exit ([int]($code[-1]))
