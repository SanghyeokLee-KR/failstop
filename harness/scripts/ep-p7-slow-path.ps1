# E-P7 한 노드 경로만 느림(Patroni 설계 8-3, 검토 R3). patroni-toxiproxy 의 api-1 리스너(pg-api1)에 양방향 지연 3000ms 를 60초
# 건다. 시작 전에 api-1 이 리더여야 한다(갓 세운 스택은 compose 순서로 api-1 이 리더다). 기대: api-1 은 SUSPENDED 가 되지 않고
# (탐지 백엔드가 SyncRep 에 있지 않다) 리스를 잃어 api-2 가 승격한다.
param([switch] $Build, [switch] $KeepStack, [switch] $NoK6)
. (Join-Path $PSScriptRoot 'ep-lib.ps1')
$inject = {
    param($tl)
    if ($tl.pre.app_leader -ne 'patroni-api-1') { throw "api-1 이 리더가 아니다($($tl.pre.app_leader)). 스택을 다시 세운다." }
    & docker exec patroni-toxiproxy /toxiproxy-cli toxic add -t latency -a latency=3000 -u -n ep7-up pg-api1 | Out-Null
    & docker exec patroni-toxiproxy /toxiproxy-cli toxic add -t latency -a latency=3000 -d -n ep7-down pg-api1 | Out-Null
    return @{ injected = 'pg-api1'; role_injected = 'path'; sync_member = $null }
}
$recover = {
    param($tl)
    & docker exec patroni-toxiproxy /toxiproxy-cli toxic remove -n ep7-up pg-api1 | Out-Null
    & docker exec patroni-toxiproxy /toxiproxy-cli toxic remove -n ep7-down pg-api1 | Out-Null
}
$code = @(Invoke-EpExperiment -Scenario 'E-P7' -Inject $inject -Recover $recover -HoldSec 60 -ExpectEpochChange `
    -K6:(-not $NoK6) -Build:$Build -KeepStack:$KeepStack)
exit ([int]($code[-1]))
