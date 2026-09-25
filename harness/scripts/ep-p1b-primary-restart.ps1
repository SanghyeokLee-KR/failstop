# E-P1b Primary 빠른 재기동(Patroni 설계 8-3). docker kill 직후 docker start 한다(리더 키 TTL 안). docker restart 는 정상
# 종료라 Patroni 가 리더 키를 놓아 계획 전환이 되므로 쓰지 않는다. 기대: 전환 없음(타임라인 불변), 크래시 복구 뒤 재확인 전
# 표시와 확정 응답 보류, 짧은 중단 뒤 자동 재개.
param([switch] $Build, [switch] $KeepStack, [switch] $NoK6)
. (Join-Path $PSScriptRoot 'ep-lib.ps1')
$inject = {
    param($tl)
    $primary = Get-EpPrimaryContainer
    Assert-EpContainer $primary
    & docker kill $primary | Out-Null
    $fails = @(Start-EpContainer $primary)
    return @{ injected = $primary; role_injected = 'primary-restart'; sync_member = $null; start_failures = $fails }
}
$code = @(Invoke-EpExperiment -Scenario 'E-P1b' -Inject $inject -HoldSec 5 -ExpectSuspension `
    -K6:(-not $NoK6) -Build:$Build -KeepStack:$KeepStack)
exit ([int]($code[-1]))
