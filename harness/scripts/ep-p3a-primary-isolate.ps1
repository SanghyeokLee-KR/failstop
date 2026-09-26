# E-P3a Primary 호스트 전체 격리(Patroni 설계 8-3). 현재 Primary 컨테이너와 같은 역할의 etcd 를 두 망에서 떼고 60초 뒤 붙인다.
# 다시 붙일 때 망 별칭(db-N-repl, db-N-app)을 되살린다. 주소가 바뀔 수 있어 pg_hba 는 망 단위로 받는다.
param([switch] $Build, [switch] $KeepStack, [switch] $NoK6)
. (Join-Path $PSScriptRoot 'ep-lib.ps1')
$inject = {
    param($tl)
    $primary = Get-EpPrimaryContainer
    $etcd = Get-EpEtcdFor $primary
    Assert-EpContainer $primary; Assert-EpContainer $etcd
    & docker network disconnect $Global:EP.DbNetwork $primary | Out-Null
    & docker network disconnect $Global:EP.AppNetwork $primary | Out-Null
    & docker network disconnect $Global:EP.DbNetwork $etcd | Out-Null
    return @{ injected = $primary; role_injected = 'primary'; sync_member = $primary; isolated = @($primary, $etcd) }
}
$recover = {
    param($tl)
    $n = $tl.injected.Replace('patroni-db-', '')
    & docker network connect --alias "db-$n-repl" $Global:EP.DbNetwork $tl.injected | Out-Null
    & docker network connect --alias "db-$n-app" $Global:EP.AppNetwork $tl.injected | Out-Null
    & docker network connect $Global:EP.DbNetwork (Get-EpEtcdFor $tl.injected) | Out-Null
}
$code = @(Invoke-EpExperiment -Scenario 'E-P3a' -Inject $inject -Recover $recover -HoldSec 60 -ExpectEpochChange -ExpectSuspension `
    -K6:(-not $NoK6) -Build:$Build -KeepStack:$KeepStack)
exit ([int]($code[-1]))
