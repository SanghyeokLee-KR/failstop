# E-P3b 비대칭 격리(Patroni 설계 8-3). 현재 Primary 와 같은 역할의 etcd 를 DB 망(patroni-db)에서만 뗀다. 앱 망은 유지한다.
# 옛 Primary 는 앱에서 닿지만 Standby·etcd 에서 떨어진다. 60초 뒤 다시 붙인다.
param([switch] $Build, [switch] $KeepStack, [switch] $NoK6)
. (Join-Path $PSScriptRoot 'ep-lib.ps1')
$inject = {
    param($tl)
    $primary = Get-EpPrimaryContainer
    $etcd = Get-EpEtcdFor $primary
    Assert-EpContainer $primary; Assert-EpContainer $etcd
    & docker network disconnect $Global:EP.DbNetwork $primary | Out-Null
    & docker network disconnect $Global:EP.DbNetwork $etcd | Out-Null
    return @{ injected = $primary; role_injected = 'primary'; sync_member = $primary; isolated = @($primary, $etcd) }
}
$recover = {
    param($tl)
    $n = $tl.injected.Replace('patroni-db-', '')
    & docker network connect --alias "db-$n-repl" $Global:EP.DbNetwork $tl.injected | Out-Null
    & docker network connect $Global:EP.DbNetwork (Get-EpEtcdFor $tl.injected) | Out-Null
}
$code = @(Invoke-EpExperiment -Scenario 'E-P3b' -Inject $inject -Recover $recover -HoldSec 60 -ExpectEpochChange -ExpectSuspension `
    -K6:(-not $NoK6) -Build:$Build -KeepStack:$KeepStack)
exit ([int]($code[-1]))
